package com.wire.signals

import java.security.SecureRandom
import java.util.concurrent.{ConcurrentLinkedQueue, ExecutorService}
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

trait DispatchQueue extends ExecutionContext {

  val name: String = "queue_" + DispatchQueue.nextInt().toHexString

  /**
    * Executes a task on this queue.
    *
    * @param task - operation to perform on this queue.
    */
  def apply[A](task: => A): CancellableFuture[A] = CancellableFuture(task)(this)

  override def reportFailure(t: Throwable): Unit = {} // TODO: Allow for tracking

  //used for waiting in tests
  def hasRemainingTasks: Boolean = false
}

object DispatchQueue {
  private lazy val random = new SecureRandom()

  private[signals] def nextInt(): Int = random.nextInt

  def apply(concurrentTasks: Int, executor: ExecutionContext, name: Option[String]): DispatchQueue =
    concurrentTasks match {
      case 0 => new UnlimitedDispatchQueue(executor, name)
      case 1 => new SerialDispatchQueue(executor, name)
      case _ => new LimitedDispatchQueue(concurrentTasks, executor, name)
    }

  def apply(concurrentTasks: Int, executor: ExecutionContext): DispatchQueue = apply(concurrentTasks, executor, None)
  def apply(concurrentTasks: Int, name: String): DispatchQueue = apply(concurrentTasks, Threading.executionContext, Some(name))
  def apply(concurrentTasks: Int): DispatchQueue = apply(concurrentTasks, Threading.executionContext, None)
  def apply(name: String): DispatchQueue = apply(0, Threading.executionContext, Some(name))
  def apply(): DispatchQueue = apply(0, Threading.executionContext, None)

  def apply(concurrentTasks: Int, service: ExecutorService, name: Option[String]): DispatchQueue =
    apply(
      concurrentTasks,
      new ExecutionContext {
        override def execute(runnable: Runnable): Unit = service.execute(runnable)
        override def reportFailure(cause: Throwable): Unit = {}
      },
      name
    )

  def apply(concurrentTasks: Int, service: ExecutorService): DispatchQueue = apply(concurrentTasks, service, None)
  def apply(concurrentTasks: Int, service: ExecutorService, name: String): DispatchQueue = apply(concurrentTasks, service, Some(name))
}

class UnlimitedDispatchQueue(executor: ExecutionContext,
                             private val _name: Option[String] = None) extends DispatchQueue {
  override val name: String = _name.getOrElse("UnlimitedQueue")
  override def execute(runnable: Runnable): Unit = executor.execute(runnable)
}

/**
  * Execution context limiting number of concurrently executing tasks.
  * All tasks are executed on parent execution context.
  */
class LimitedDispatchQueue(concurrencyLimit: Int,
                           parent: ExecutionContext,
                           private val _name: Option[String])
  extends DispatchQueue {
  override val name: String = _name.getOrElse("LimitedQueue")
  require(concurrencyLimit > 0, "concurrencyLimit should be greater than 0")

  override def execute(runnable: Runnable): Unit = Executor.dispatch(runnable)

  override def reportFailure(cause: Throwable): Unit = parent.reportFailure(cause)

  private object Executor extends Runnable {

    val queue = new ConcurrentLinkedQueue[Runnable]
    val runningCount = new AtomicInteger(0)

    def dispatch(runnable: Runnable): Unit = {
      queue.add(runnable)
      dispatchExecutor()
    }

    @tailrec
    def dispatchExecutor(): Unit =
      if (runningCount.getAndIncrement < concurrencyLimit)
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
  /**
    * Maximum number of tasks to execute in single batch.
    * Used to prevent starving of other contexts using common parent.
    */
  val MaxBatchSize = 100
}

class SerialDispatchQueue(executor: ExecutionContext,
                          private val _name: Option[String])
  extends LimitedDispatchQueue(1, executor, _name) {
  override val name: String = "serial_" + DispatchQueue.nextInt().toHexString
}

object SerialDispatchQueue {
  def apply(): DispatchQueue = DispatchQueue(1)
  def apply(name: String): DispatchQueue = DispatchQueue(1, name)
}

