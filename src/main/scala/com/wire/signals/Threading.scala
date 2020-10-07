package com.wire.signals

import java.util.{Timer, TimerTask}

import com.wire.signals.DispatchQueue.UNLIMITED
import com.wire.signals.utils.returning

import scala.concurrent.ExecutionContext

object Threading {
  implicit lazy val defaultContext: ExecutionContext = apply()
  final val Cpus: Int = math.max(2, Runtime.getRuntime.availableProcessors)

  private final lazy val baseContext: DispatchQueue = DispatchQueue(UNLIMITED, ExecutionContext.global, None)
  private final val timer: Timer = new Timer()

  final def schedule(f: () => Any, delay: Long = 0L): TimerTask =
    returning(new TimerTask { override def run(): Unit = f() }) {
      timer.schedule(_, delay)
    }

  private var _instance = Option.empty[DispatchQueue]

  final def setAsDefault(queue: DispatchQueue): Unit = _instance = Some(queue)

  final def apply(): DispatchQueue = _instance.getOrElse(baseContext)
}
