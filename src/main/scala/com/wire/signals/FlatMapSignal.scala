package com.wire.signals

import scala.concurrent.ExecutionContext

final private[signals] class FlatMapSignal[A, B](source: Signal[A], f: A => Signal[B])
  extends Signal[B] with SignalSubscriber {
  private val Empty = Signal.empty[B]

  private object wiringMonitor

  private var sourceValue: Option[A] = None
  private var mapped: Signal[B] = Empty

  private val subscriber = new SignalSubscriber {
    // TODO: is this synchronization needed, is it enough? What if we just got unwired ?
    override def changed(currentContext: Option[ExecutionContext]): Unit = {
      val changed = wiringMonitor synchronized {
        val next = source.value
        if (sourceValue != next) {
          sourceValue = next

          mapped.unsubscribe(FlatMapSignal.this)
          mapped = next.map(f).getOrElse(Empty)
          mapped.subscribe(FlatMapSignal.this)
          true
        } else false
      }

      if (changed) set(mapped.value)
    }
  }

  override def onWire(): Unit = wiringMonitor.synchronized {
    source.subscribe(subscriber)

    val next = source.value
    if (sourceValue != next) {
      sourceValue = next
      mapped = next.map(f).getOrElse(Empty)
    }

    mapped.subscribe(this)
    value = mapped.value
  }

  override def onUnwire(): Unit = wiringMonitor.synchronized {
    source.unsubscribe(subscriber)
    mapped.unsubscribe(this)
  }

  override def changed(currentContext: Option[ExecutionContext]): Unit = set(mapped.value, currentContext)
}

object FlatMapSignal {

}