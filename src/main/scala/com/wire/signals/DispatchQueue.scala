package com.wire.signals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import com.wire.signals.utils.ZSecureRandom

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

trait DispatchQueue extends ExecutionContext {

  val name: String = "queue_" + ZSecureRandom.nextInt().toHexString

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
  def apply(concurrentTasks: Int = 0, executor: ExecutionContext = Threading.executionContext): DispatchQueue = concurrentTasks match {
    case 0 => new UnlimitedDispatchQueue(executor)
    case 1 => new SerialDispatchQueue(executor)
    case _ => new LimitedDispatchQueue(concurrentTasks, executor)
  }
}

class UnlimitedDispatchQueue(executor: ExecutionContext = Threading.executionContext,
                             override val name: String = "UnlimitedQueue") extends DispatchQueue {
  override def execute(runnable: Runnable): Unit = executor.execute(DispatchQueueStats(name, runnable))
}

/**
  * Execution context limiting number of concurrently executing tasks.
  * All tasks are executed on parent execution context.
  */
class LimitedDispatchQueue(concurrencyLimit: Int = 1,
                           parent: ExecutionContext = Threading.executionContext,
                           override val name: String = "LimitedQueue") extends DispatchQueue {
  require(concurrencyLimit > 0, "concurrencyLimit should be greater than 0")

  override def execute(runnable: Runnable): Unit = Executor.dispatch(runnable)

  override def reportFailure(cause: Throwable): Unit = parent.reportFailure(cause)

  private object Executor extends Runnable {

    val queue = new ConcurrentLinkedQueue[Runnable]
    val runningCount = new AtomicInteger(0)

    def dispatch(runnable: Runnable): Unit = {
      queue.add(DispatchQueueStats(name, runnable))
      dispatchExecutor()
    }

    def dispatchExecutor(): Unit = {
      if (runningCount.getAndIncrement < concurrencyLimit)
        parent.execute(this)
      else if (runningCount.decrementAndGet() < concurrencyLimit && !queue.isEmpty)
        dispatchExecutor() // to prevent race condition when executor has just finished
    }

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

class SerialDispatchQueue(executor: ExecutionContext = Threading.executionContext,
                          override val name: String = "serial_" + ZSecureRandom.nextInt().toHexString)
  extends LimitedDispatchQueue(1, executor)

object SerialDispatchQueue {
  def apply(): SerialDispatchQueue = new SerialDispatchQueue(name = s"$SerialDispatchQueue")
}

