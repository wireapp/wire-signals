package com.wire.signals

import scala.concurrent.ExecutionContext

/** The usual entry point for publishing values in signals.
  *
  * Create a new signal either using the default constructor or the `Signal.apply[V]()` method. The source signal exposes
  * methods you can use for changing its value. Then you can combine it with other signals and finally subscribe a function
  * to it which will be called initially, and then on each change of the signal's value.
  *
  * @tparam V the type of the value held by the signal.
  */
class SourceSignal[V](v: Option[V] = None) extends Signal[V](v) {
  /** Changes the value of the signal.
    *
    * The original `publish` method of the [[Signal]] class is `protected` to ensure that intermediate signals - those created
    * by methods like `map`, `flatMap`, `filter`, etc. - will not be used to directly change their values. The source signal
    * exposes this method for public use.
    *
    * @see [[Signal.publish]]
    *
    * @param value The new value of the signal.
    * @param ec The execution context used for dispatching. The default implementation ensures that if `ec` is the same as
    *           the execution context used to register the subscriber, the subscriber will be called immediately. Otherwise,
    *           a future working in the subscriber's execution context will be created and `ec` will be ignored.
    */
  override def publish(value: V, ec: ExecutionContext): Unit = super.publish(value, ec)

  /** Changes the value of the signal.
    *
    * The original `publish` method of the [[Signal]] class is `protected` to ensure that intermediate signals - those created
    * by methods like `map`, `flatMap`, `filter`, etc. - will not be used to directly change their values. The source signal
    * exposes this method for public use.
    *
    * @see [[Signal.publish]]
    *
    * @param value The new value of the signal.
    */
  override def publish(value: V): Unit = super.publish(value)

  /** An alias for the `publish` method. */
  @inline final def !(value: V): Unit = publish(value)

  /** Changes the value of the signal by applying the given function to the current value.
    * If the signal is empty, it will stay empty.
    *
    * @param f The function used to modify the signal's value.
    * @return true if the signal's value is actually changed to something different, and so the subscribers will be notified,
    *         false otherwise.
    */
  def mutate(f: V => V): Boolean = update(_.map(f))

  /** Changes the value of the signal by applying the given function to the current value.
    * If the signal is empty, it will stay empty.
    *
    * @param f The function used to modify the signal's value.
    * @param ec The execution context used for dispatching. The default implementation ensures that if `ec` is the same as
    *           the execution context used to register the subscriber, the subscriber will be called immediately. Otherwise,
    *           a future working in the subscriber's execution context will be created and `ec` will be ignored.
    * @return true if the signal's value is actually changed to something different, and so the subscribers will be notified,
    *         false otherwise.
    */
  def mutate(f: V => V, ec: ExecutionContext): Boolean = update(_.map(f), Some(ec))

  /** Changes the value of the signal by applying the given function to the current value.
    * If the signal is empty, it will be set to the given default value instead.
    *
    * @param f The function used to modify the signal's value.
    * @param default The default value used instead of `f` if the signal is empty.
    * @return true if the signal's value is actually changed to something different, and so the subscribers will be notified,
    *         false otherwise.
    */
  def mutateOrDefault(f: V => V, default: V): Boolean = update(_.map(f).orElse(Some(default)))
}
