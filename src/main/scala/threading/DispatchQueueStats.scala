package threading

import scala.collection.mutable
import scala.concurrent.ExecutionContext

object DispatchQueueStats {

  var debug: Boolean = false

  val stats = new mutable.HashMap[String, QueueStats]

  def apply(queue: String, executor: ExecutionContext): ExecutionContext =
    if (debug) {
      new ExecutionContext {
        override def reportFailure(cause: Throwable): Unit = executor.reportFailure(cause)
        override def execute(runnable: Runnable): Unit = executor.execute(DispatchQueueStats(queue, runnable))
      }
    } else executor

  def apply(queue: String, task: Runnable): Runnable = if (debug) { new StatsRunnable(task, queue) } else task

  def debug[A](queue: String)(f: => A): A = {
    val start = System.nanoTime()
    val res = f
    add(queue, start, start, System.nanoTime())
    res
  }

  def reset() = synchronized { stats.clear() }

  def add(queue: String, init: Long, start: Long, done: Long) = DispatchQueueStats.synchronized {
    stats.getOrElseUpdate(queue, QueueStats(queue)).add(init, start, done)
  }

  def printStats(minTasks: Int = 10) = report(minTasks) foreach println

  def report(minTasks: Int = 10) =
    stats.values.toSeq.sortBy(_.totalExecution).reverse.filter(s => s.count > minTasks || s.total > 1000000).map(_.report)

  case class QueueStats(queue: String) {

    var count = 0
    var total = 0L // total time in micro seconds
    var totalWait = 0L
    var totalExecution = 0L

    def add(initNanos: Long, startNanos: Long, doneNanos: Long): Unit = {
      count += 1
      total += (doneNanos - initNanos) / 1000
      totalWait += (startNanos - initNanos) / 1000
      totalExecution += (doneNanos - startNanos) / 1000
    }

    def report = QueueReport(queue, count, total, totalWait, totalExecution)
  }

  class StatsRunnable(task: Runnable, queue: String) extends Runnable {
    val init: Long = System.nanoTime()

    override def run(): Unit = {
      val start = System.nanoTime()
      try {
        task.run()
      } finally {
        DispatchQueueStats.add(queue, init, start, System.nanoTime())
      }
    }
  }
}

case class QueueReport(queue: String, count: Int, total: Long, totalWait: Long, totalExecution: Long) {

  def time(us: Long) = f"${us / 1000000}'${us / 1000 % 1000}%03d'${us % 1000}%03d µs"

  def stat(label: String, sum: Long) =  s"\t$label ${time(sum)} [${time(sum/count)}]"

  override def toString: String =
    s"""QueueStats[$queue] - tasks: $count
       |   ${stat("total:     ", total)}
       |   ${stat("execution: ", totalExecution)}
       |   ${stat("wait:      ", totalWait)}
       |""".stripMargin
}
