package com.wire.signals

import com.wire.signals.Signal.SignalSubscriber

import scala.concurrent.ExecutionContext

object ConstSignal {
  /** Creates a const signal holding the given value.
    *
    * @see also `Signal.const`
    *
    * @param v The value of the signal.
    * @tparam V The type of the value.
    * @return A new const signal with the given value.
    */
  def apply[V](v: V): ConstSignal[V] = new ConstSignal(Option(v))
}

/** A signal holding an immutable value.
  * Using const signals in flatMap chains should have better performance compared to source signals with the same value.
  * Since the value never changes, the subscriber function will be called only in the moment of subscription, but never
  * after that, so there's no need to keep the subscription.
  */
final class ConstSignal[V] private[signals] (v: Option[V]) extends Signal[V](v) with NoAutowiring {
  override def subscribe(subscriber: SignalSubscriber): Unit = {}

  override def unsubscribe(subscriber: SignalSubscriber): Unit = {}

  override protected[signals] def update(f: Option[V] => Option[V], ec: Option[ExecutionContext]): Boolean = false

  override protected[signals] def set(v: Option[V], ec: Option[ExecutionContext]): Boolean = false
}

