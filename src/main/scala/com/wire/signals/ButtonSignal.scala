package com.wire.signals

final class ButtonSignal[A](val service: Signal[A], val buttonState: Signal[Boolean])(onClick: (A, Boolean) => Unit)
  extends ProxySignal[Boolean](service, buttonState) {

  def press(): Unit = if (wired) {
    (service.value, buttonState.value) match {
      case (Some(s), Some(b)) => onClick(s, b)
      case _ =>
    }
  }

  override protected def computeValue(current: Option[Boolean]): Option[Boolean] = buttonState.value
}

object ButtonSignal {
  def apply[A](service: Signal[A], buttonState: Signal[Boolean])(onClick: (A, Boolean) => Unit): ButtonSignal[A]
    = new ButtonSignal(service, buttonState)(onClick)
}