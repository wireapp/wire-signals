package com.wire.signals

import java.security.SecureRandom
import java.util.concurrent.{ConcurrentLinkedQueue, ExecutorService}
import java.util.concurrent.atomic.AtomicInteger

import com.wire.signals.DispatchQueue.{SERIAL, UNLIMITED, nextInt}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

trait DispatchQueue extends ExecutionContext {

  val name: String = s"queue_${nextInt().toHexString}"

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
  final val UNLIMITED = 0
  final val SERIAL    = 1

  private lazy val random = new SecureRandom()

  private[signals] def nextInt(): Int = random.nextInt

  def apply(concurrentTasks: Int, executor: ExecutionContext, name: Option[String]): DispatchQueue =
    concurrentTasks match {
      case UNLIMITED => new UnlimitedDispatchQueue(executor, name)
      case SERIAL    => new SerialDispatchQueue(executor, name)
      case _         => new LimitedDispatchQueue(concurrentTasks, executor, name)
    }

  def apply(concurrentTasks: Int, service: ExecutorService, name: Option[String]): DispatchQueue =
    apply(
      concurrentTasks,
      new ExecutionContext {
        override def execute(runnable: Runnable): Unit = service.execute(runnable)
        override def reportFailure(cause: Throwable): Unit = {}
      },
      name
    )
}

final class UnlimitedDispatchQueue private[signals] (executor: ExecutionContext, private val _name: Option[String] = None)
  extends DispatchQueue {
  override val name = _name.getOrElse(s"unlimited_${nextInt().toHexString}")
  override def execute(runnable: Runnable): Unit = executor.execute(runnable)
}

object UnlimitedDispatchQueue {
  def apply(): DispatchQueue = new UnlimitedDispatchQueue(Threading.executionContext, None)
  def apply(name: String): DispatchQueue = new UnlimitedDispatchQueue(Threading.executionContext, Some(name))
}

/**
  * Execution context limiting number of concurrently executing tasks.
  * All tasks are executed on parent execution context.
  */
class LimitedDispatchQueue private[signals] (concurrencyLimit: Int, parent: ExecutionContext, private val _name: Option[String])
  extends DispatchQueue {
  override val name = _name.getOrElse(s"limited_${nextInt().toHexString}")
  require(concurrencyLimit > UNLIMITED, s"concurrencyLimit should be greater than $UNLIMITED")

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

final class SerialDispatchQueue private[signals] (executor: ExecutionContext, private val _name: Option[String])
  extends LimitedDispatchQueue(SERIAL, executor, _name) {
  override val name: String = s"serial_${nextInt().toHexString}"
}

object SerialDispatchQueue {
  def apply(): DispatchQueue = new SerialDispatchQueue(Threading.executionContext, None)
  def apply(name: String): DispatchQueue = new SerialDispatchQueue(Threading.executionContext, Some(name))
}

