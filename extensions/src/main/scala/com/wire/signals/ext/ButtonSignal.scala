package com.wire.signals.ext

import com.wire.signals.{ProxySignal, Signal}

object ButtonSignal {
  /** Creates a new button signal from the parent signal, a secondary signal indicating the state of a pressed/released
    * button, and a function which performs a side-effect action every time the button signal is "clicked" on.
    *
    * @param source The parent signal.
    * @param buttonState A secondary signal simulating a toggle or a button that can be pressed and released.
    * @param onClick A function which takes the current value of the parent signal and the button state, and performs a side-effect action.
    * @tparam V The value type of the parent signal and the first half of the tuple `(V, Boolean)` of the button signal.
    * @return A new button signal with the value type `(V, Boolean)`.
    */
  def apply[V](source: Signal[V], buttonState: Signal[Boolean])(onClick: (V, Boolean) => Unit): ButtonSignal[V]
  = new ButtonSignal(source, buttonState)(onClick)
}

/** A signal that combines the parent signal with another one which carries a boolean value. The other signal, called
  * `buttonState` can simulate a toggle or a button that can be pressed and released. The button signal can then be
  * "clicked" by the user which triggers the provided `onClick` function performing a side-effect. In short, on top of
  * being able to subscribe to it, the user can manually trigger an action.
  *
  * The button signal updates every time the button state changes, but *not* when the parent signal's value changes but
  * the button state stays the same. This can be marginally more useful in UI than the more generic way of creating
  * a new signal with `val buttonSignal = Signal.zip(source, buttonState){ ... }` and then using
  * `buttonSignal.future.foreach { ... }` instead of `buttonSignal.click()`.
  *
  * @todo Move to the extensions project.
  *
  * @param source The parent signal.
  * @param buttonState A secondary signal simulating a toggle or a button that can be pressed and released.
  * @param onClick A function which takes the current value of the parent signal and the button state, and performs a side-effect action.
  * @tparam V The value type of the parent signal and the first half of the tuple `(V, Boolean)` of the button signal.
  */
final class ButtonSignal[V](val source: Signal[V], val buttonState: Signal[Boolean])(onClick: (V, Boolean) => Unit)
  extends ProxySignal[Boolean](source, buttonState) {

  /** Triggers the call of the `onClick` function with the current values of both the parent signal and the button state
    * signal, assuming that they are both initialized. */
  def click(): Unit = if (wired) {
    (source.value, buttonState.value) match {
      case (Some(s), Some(b)) => onClick(s, b)
      case _ =>
    }
  }

  override protected def computeValue(current: Option[Boolean]): Option[Boolean] = buttonState.value
}
