package com.wire.signals

case class ButtonSignal[A](service: Signal[A], buttonState: Signal[Boolean])(onClick: (A, Boolean) => Unit)
  extends ProxySignal[Boolean](service, buttonState) {

  def press(): Unit = if (wired) {
    (service.value, buttonState.value) match {
      case (Some(s), Some(b)) => onClick(s, b)
      case _ =>
    }
  }

  override protected def computeValue(current: Option[Boolean]): Option[Boolean] = buttonState.value
}
