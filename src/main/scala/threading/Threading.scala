package threading

import java.util.concurrent.{ExecutorService, Executors}

import scala.concurrent.ExecutionContext
import java.util.{Timer, TimerTask}

import utils.returning

trait Threading {
  def mainThread: ExecutionContext

  def schedule(f: () => Any, delay: Long): TimerTask

  implicit val executionContext: ExecutionContext = mainThread
}

class DefaultThreading(override val mainThread: ExecutionContext = ExecutionContext.global) extends Threading {
  private val timer: Timer = new Timer()

  override def schedule(f: () => Any, delay: Long): TimerTask = {
    val newTask = new TimerTask {
      override def run(): Unit = f()
    }

    timer.schedule(newTask, delay)

    newTask
  }
}

object Threading {
  private var _instance = Option.empty[Threading]

  def set(threading: Threading): Unit = {
    _instance = Some(threading)
  }

  def apply(): Threading = _instance match {
    case Some(threading) => threading
    case None =>
      returning(new DefaultThreading) { t => _instance = Option(t) }
  }

  implicit lazy val executionContext: ExecutionContext = apply().mainThread

  val Cpus = math.max(2, Runtime.getRuntime.availableProcessors())

  def executionContext(service: ExecutorService): ExecutionContext = new ExecutionContext {
    override def reportFailure(cause: Throwable): Unit = {
      //super.reportFailure(cause)
      //      exception(cause, "ExecutionContext failed") TODO make threading mockable and then inject tracking
    }
    override def execute(runnable: Runnable): Unit = service.execute(runnable)
  }

  /**
    * Thread pool for non-blocking background tasks.
    */
  val ThreadPool: DispatchQueue = new LimitedDispatchQueue(Cpus, executionContext(Executors.newCachedThreadPool()), "CpuThreadPool")

}
