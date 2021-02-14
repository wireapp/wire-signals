package com.wire.signals

import com.wire.signals.Signal.SignalSubscriber

import scala.concurrent.ExecutionContext

/** It's a private subclass of [[Signal]] but it's moved to here to make it easier to take a look at it.
  * The `flatMap` method on signals is pretty complicated. If it was possible to simplify it (or just make it faster)
  * it might have a very good effect on the performance of the whole library.
  *
  * @param source The parent signal.
  * @param f A function which for every value of the parent signal produces a new signal.
  * @tparam A The value type of the parent signal.
  * @tparam B The value type of the signal produced by `f`.
  */
final private[signals] class FlatMapSignal[A, B](source: Signal[A], f: A => Signal[B])
  extends Signal[B] with SignalSubscriber {
  private object wiringMonitor

  private var sourceValue: Option[A] = None
  private var mapped: Signal[B] = Signal.empty[B]

  private val subscriber = new SignalSubscriber {
    /** @todo Is this synchronization needed, is it enough? What if we just got unwired ? */
    override def changed(currentContext: Option[ExecutionContext]): Unit = {
      val changed = wiringMonitor.synchronized {
        val next = source.value
        if (sourceValue != next) {
          sourceValue = next

          mapped.unsubscribe(FlatMapSignal.this)
          mapped = next.map(f).getOrElse(Signal.empty[B])
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
      mapped = next.map(f).getOrElse(Signal.empty[B])
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
