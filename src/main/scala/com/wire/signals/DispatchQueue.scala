package com.wire.signals

import java.util.concurrent.{ConcurrentLinkedQueue, ExecutorService}
import java.util.concurrent.atomic.AtomicInteger

import com.wire.signals.DispatchQueue.{SERIAL, UNLIMITED, nextInt}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

/** A thin wrapper over Scala's [[ExecutionContext]] allowing us to differentiate between the default execution context
  * which tries to run asynchronously as many tasks as possible, and limited execution contexts, allowed to run only
  * up to a given number of tasks at once.
  *
  * @see [[ExecutionContext]]
  */
trait DispatchQueue extends ExecutionContext {
  val name: String = s"queue_${nextInt()}"

  /** Executes a task on this queue.
    *
    * @param task - operation to perform on this queue.
    */
  def apply[A](task: => A): CancellableFuture[A] = CancellableFuture(task)(this)

  /** @todo Currently not used. Left here as a reminder that in the future we may provide logging functionality.
    *
    * @param t - the error that occured when executing a task on this queue.
    */
  override def reportFailure(t: Throwable): Unit = {}

  /** If the queue is a limited one, some tasks may need to wait before being executed.
    *
    * @return true if there is a task waiting in the queue to be executed after one of the current one finishes, false otherwise.
    */
  def hasRemainingTasks: Boolean = false
}

object DispatchQueue {
  /** Used in place on the `concurrentTasks` parameter in one of the `DispatchQueue.apply` method,
    * `UNLIMITED` indicates that the queue should be an unlimited one. But take a look on `UnlimitedDispatchQueue.apply`
    * before you decide to use it.
    *
    * @see [[UnlimitedDispatchQueue]]
    */
  final val UNLIMITED = 0
  /** Used in place on the `concurrentTasks` parameter in one of the `DispatchQueue.apply` method,
    * `SERIAL` indicates that the queue should be a serial one. But take a look on `SerialDispatchQueue.apply`
    * before you decide to use it.
    *
    * @see [[SerialDispatchQueue]]
    */
  final val SERIAL = 1

  private lazy val atom = new AtomicInteger()

  private[signals] def nextInt(): Int = atom.incrementAndGet()

  private def createDispatchQueue(concurrentTasks: Int, executor: ExecutionContext, name: Option[String]): DispatchQueue =
    concurrentTasks match {
      case UNLIMITED => new UnlimitedDispatchQueue(executor, name)
      case SERIAL    => new SerialDispatchQueue(executor, name)
      case _         => new LimitedDispatchQueue(concurrentTasks, executor, name)
    }

  /** Creates a dispatch queue with a generated name.
    *
    * @param concurrentTasks - the maximum number of concurrent tasks the queue is allowed to run.
    *                        Can be `UNLIMITED`, `SERIAL`, or an arbitrary positive number bigger than 1.
    * @param executor - the underlying execution context
    * @return a new dispatch queue, either unlimited, serial, or limited.
    */
  def apply(concurrentTasks: Int, executor: ExecutionContext): DispatchQueue =
    createDispatchQueue(concurrentTasks, executor, None)


  /** Creates a dispatch queue with a given name.
    *
    * @param concurrentTasks - the maximum number of concurrent tasks the queue is allowed to run.
    *                        Can be `UNLIMITED`, `SERIAL`, or an arbitrary positive number bigger than 1.
    * @param executor - the underlying execution context
    * @param name - the name of the queue; might be later used e.g. in logging
    * @return a new dispatch queue, either unlimited, serial, or limited.
    */
  def apply(concurrentTasks: Int, executor: ExecutionContext, name: String): DispatchQueue =
    createDispatchQueue(concurrentTasks, executor, Some(name))

  private def createDispatchQueue(concurrentTasks: Int, service: ExecutorService, name: Option[String]): DispatchQueue =
    createDispatchQueue(
      concurrentTasks,
      new ExecutionContext {
        override def execute(runnable: Runnable): Unit = service.execute(runnable)
        override def reportFailure(cause: Throwable): Unit = {}
      },
      name
    )

  /** Creates a dispatch queue with a generated name, given an executor service instead of an execution context.
    * @see [[ExecutorService]]
    *
    * @param concurrentTasks - the maximum number of concurrent tasks the queue is allowed to run.
    *                        Can be `UNLIMITED`, `SERIAL`, or an arbitrary positive number bigger than 1.
    * @param service - the underlying executor service. The dispatch queue will create a new execution context, using the service.
    * @return a new dispatch queue, either unlimited, serial, or limited.
    */
  def apply(concurrentTasks: Int, service: ExecutorService): DispatchQueue =
    createDispatchQueue(concurrentTasks, service, None)

  /** Creates a dispatch queue with a given name, given an executor service instead of an execution context.
    * @see [[ExecutorService]]
    *
    * @param concurrentTasks - the maximum number of concurrent tasks the queue is allowed to run.
    *                        Can be `UNLIMITED`, `SERIAL`, or an arbitrary positive number bigger than 1.
    * @param service - the underlying executor service. The dispatch queue will create a new execution context, using the service.
    * @param name - the name of the queue; might be later used e.g. in logging.
    * @return a new dispatch queue, either unlimited, serial, or limited.
    */
  def apply(concurrentTasks: Int, service: ExecutorService, name: String): DispatchQueue =
    createDispatchQueue(concurrentTasks, service, Some(name))
}

/** A dispatch queue that simply passes all its tasks to its execution context.
  */
final class UnlimitedDispatchQueue private[signals] (executor: ExecutionContext, private val _name: Option[String] = None)
  extends DispatchQueue {
  override val name: String = _name.getOrElse(s"unlimited_${nextInt()}")
  override def execute(runnable: Runnable): Unit = executor.execute(runnable)
}

object UnlimitedDispatchQueue {
  /** Creates an unlimited dispatch queue with a generated name that uses the default execution context.
    * Don't use it to create a dispatch queue which you would later want to set as the default one, as this will
    * initialize the default one first (if it's not already initialized), so basically you could just do nothing
    * and have the same effect.
    *
    * @see [[Threading]]
    *
    * @return a new unlimited dispatch queue
    */
  def apply(): DispatchQueue = new UnlimitedDispatchQueue(Threading.defaultContext, None)

  /** Creates an unlimited dispatch queue with the given name that uses the default execution context.
    * Don't use it to create a dispatch queue which you would later want to set as the default one, as this will
    * initialize the default one first (if it's not already initialized), so basically you could just do nothing
    * and have the same effect.
    *
    * @see [[Threading]]
    *
    * @param name - the name of the queue; might be later used e.g. in logging.
    * @return a new unlimited dispatch queue
    */
  def apply(name: String): DispatchQueue = new UnlimitedDispatchQueue(Threading.defaultContext, Some(name))
}

/** A dispatch queue limiting number of concurrently executing tasks.
  * All tasks are executed on parent execution context, but only up to the `concurrencyLimit`.
  * New tasks, scheduled when the limit is reached, will wait in the queue until one of the current one finishes.
  * Create with one of `DispatchQueue.apply` methods.
  */
class LimitedDispatchQueue private[signals] (concurrencyLimit: Int, parent: ExecutionContext, private val _name: Option[String])
  extends DispatchQueue {
  override val name: String = _name.getOrElse(s"limited_${nextInt()}")

  /** Schedules a new runnable task to be executed. The task will be added to the queue and then a dispatch executor will run
    * to check if it can be taken from it and executed or if it has to wait until one of the running tasks finishes.
    *
    * @see [[Runnable]]
    *
    * @param runnable - a task to be executed
    */
  override def execute(runnable: Runnable): Unit = Executor.dispatch(runnable)

  override def reportFailure(cause: Throwable): Unit = parent.reportFailure(cause)

  private object Executor extends Runnable {
    val queue = new ConcurrentLinkedQueue[Runnable]
    val runningCount = new AtomicInteger(0)

    def dispatch(runnable: Runnable): Unit = {
      queue.add(runnable)
      dispatchExecutor()
    }

    // TODO: Is it ok to call this method in a loop without any delay? Shouldn't it sleep for a moment between calls?
    @tailrec
    def dispatchExecutor(): Unit =
      if (runningCount.getAndIncrement() < concurrencyLimit)
        parent.execute(this)
      else if (runningCount.decrementAndGet() < concurrencyLimit && !queue.isEmpty)
        dispatchExecutor() // to prevent race condition when executor has just finished

    override def run(): Unit = {
      @tailrec
      def executeBatch(counter: Int = 0): Unit = Option(queue.poll()) match {
        case None => // done
        case Some(runnable) =>
          try {
            runnable.run()
          } catch {
            case cause: Throwable => reportFailure(cause)
          }
          if (counter < LimitedDispatchQueue.MaxBatchSize) executeBatch(counter + 1)
      }

      executeBatch()

      if (runningCount.decrementAndGet() < concurrencyLimit && !queue.isEmpty) dispatchExecutor()
    }
  }

  override def hasRemainingTasks: Boolean = !Executor.queue.isEmpty || Executor.runningCount.get() > 0
}

object LimitedDispatchQueue {
  /** The maximum number of tasks to execute in a single batch. Used to prevent starving of other contexts using the common parent.
    * If more than `MaxBatchSize` tasks await execution, after this number of tasks are run the execution will stop, other parents
    * will be given an opportunity to run their tasks, and then it will come back here the rest of these tasks will be executed.
    */
  val MaxBatchSize = 100
}

/** A special case of a limited dispatch queue which allows for only one task to be executed at once.
  * Use when you want to enforce the tasks to be executed in the order they were scheduled.
  */
final class SerialDispatchQueue private[signals] (executor: ExecutionContext, private val _name: Option[String])
  extends LimitedDispatchQueue(SERIAL, executor, _name) {
  override val name: String = s"serial_${nextInt()}"
}

object SerialDispatchQueue {

  /** Creates a serial dispatch queue with a generated name that uses the default execution context.
    *
    * @see [[Threading]]
    *
    * @return a new serial dispatch queue
    */
  def apply(): DispatchQueue = new SerialDispatchQueue(Threading.defaultContext, None)

  /** Creates a serial dispatch queue with the given name that uses the default execution context.
    *
    * @see [[Threading]]
    *
    * @param name - the name of the queue; might be later used e.g. in logging.
    * @return a new serial dispatch queue
    */
  def apply(name: String): DispatchQueue = new SerialDispatchQueue(Threading.defaultContext, Some(name))
}
