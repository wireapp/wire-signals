package com.wire.signals

import scala.concurrent.ExecutionContext

/**
  * Immutable signal value. Can be used whenever some constant or empty signal is needed.
  * Using immutable signals in flatMap chains should have better performance compared to regular signals with the same value.
  */
class ConstSignal[A](v: Option[A]) extends Signal[A](v) with NoAutowiring {
  override def subscribe(listener: SignalListener): Unit = ()

  override def unsubscribe(listener: SignalListener): Unit = ()

  override protected[signals] def update(f: Option[A] => Option[A], ec: Option[ExecutionContext]): Boolean =
    throw new UnsupportedOperationException("Const signal can not be updated")

  override protected[signals] def set(v: Option[A], ec: Option[ExecutionContext]): Unit =
    throw new UnsupportedOperationException("Const signal can not be changed")
}
