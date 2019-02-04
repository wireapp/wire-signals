package threading

import scala.concurrent.ExecutionContext

trait TimerTask {
  def run(): Unit
  def cancel(): Unit
}

trait Timer {
  def schedule(task: TimerTask, delay: Long): Unit
  def purge(): Unit
}

trait Threading {
  def mainThread: ExecutionContext
  def timer: Timer

  def createTask(f: () => Any): TimerTask

  implicit val executionContext: ExecutionContext = mainThread
}

object Threading {
  private var _instance = Option.empty[Threading]

  def set(threading: Threading): Unit = {
    _instance = Some(threading)
  }

  def apply(): Threading = _instance.get
}
