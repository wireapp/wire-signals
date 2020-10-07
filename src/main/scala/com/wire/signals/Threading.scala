package com.wire.signals

import java.util.{Timer, TimerTask}

import com.wire.signals.DispatchQueue.UNLIMITED
import com.wire.signals.utils.returning

import scala.concurrent.ExecutionContext

object Threading {
  implicit lazy val defaultContext: ExecutionContext = apply()
  final val Cpus: Int = math.max(2, Runtime.getRuntime.availableProcessors)

  private final val timer: Timer = new Timer()
  private lazy val Default: DispatchQueue = DispatchQueue(UNLIMITED, ExecutionContext.global, None)

  private var _instance = Option.empty[DispatchQueue]

  def setAsDefault(queue: DispatchQueue): Unit = {
    _instance = Some(queue)
  }

  def apply(): DispatchQueue = _instance match {
    case Some(queue) => queue
    case None        => Default
  }

  def schedule(f: () => Any, delay: Long): TimerTask =
    returning(new TimerTask {
      override def run(): Unit = f()
    }) {
      timer.schedule(_, delay)
    }
}
