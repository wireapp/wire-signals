package com.wire.signals

import java.util.{Timer, TimerTask}

import scala.concurrent.ExecutionContext

object Threading {
  private val timer: Timer = new Timer()

  def schedule(f: () => Any, delay: Long): TimerTask = {
    val newTask = new TimerTask {
      override def run(): Unit = f()
    }

    timer.schedule(newTask, delay)

    newTask
  }

  private var _instance = Option.empty[DispatchQueue]

  def setAsDefault(queue: DispatchQueue): Unit = {
    _instance = Some(queue)
  }

  def apply(): DispatchQueue = _instance match {
    case Some(queue) => queue
    case None        => Default
  }

  implicit lazy val executionContext: ExecutionContext = apply()

  val Cpus: Int = math.max(2, Runtime.getRuntime.availableProcessors)

  val Default: DispatchQueue = DispatchQueue(0, ExecutionContext.global)
}
