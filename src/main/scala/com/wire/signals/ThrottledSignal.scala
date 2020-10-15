package com.wire.signals

import java.util.concurrent.atomic.AtomicBoolean

import com.wire.signals.CancellableFuture.delayed

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/** A signal which publishes changes of its parent signal but no more often than once during a given time interval.
  * The initial value of the parent signal will be published immediately. The first change to it will happen at the earliest
  * after the given delay. If the parent signal changes its value more often, the intermediate values will be ignored.
  *
  * Use it e.g. for optimization of a signal chain when there is no need to react immediately to all changes to the original signal.
  * For example. changes in the UI could be displayed only with the speed that allows for comfortable usage of the app by the user,
  * but not faster.
  *
  * @todo Check if when the original value changes once during the delay interval, but not again after it, will that one change
  *       be noticed. I think it should be.
  *
  * @param source The original signal providing the value and changes to it.
  * @param delay The time interval used for publishing. No more than one change of the value per `delay` will be published.
  * @tparam V The value type of the signal.
  */
class ThrottledSignal[V](val source: Signal[V], val delay: FiniteDuration) extends ProxySignal[V](source) {
  import scala.concurrent.duration._

  private val waiting = new AtomicBoolean(false)
  @volatile private var lastDispatched = 0L

  override protected def computeValue(current: Option[V]): Option[V] = source.value

  override protected def notifySubscribers(ec: Option[ExecutionContext]): Unit =
    if (waiting.compareAndSet(false, true)) {
      val context = ec.getOrElse(Threading.defaultContext)
      val d = math.max(0, lastDispatched - System.currentTimeMillis() + delay.toMillis)
      delayed(d.millis) {
        lastDispatched = System.currentTimeMillis()
        waiting.set(false)
        super.notifySubscribers(Some(context))
      }(context)
    }
}

object ThrottledSignal {
  /** Creates a new throttled signal which publishes changes to the original signal no more often than once during the time interval.
    *
    * @see [[Signal.throttled]]
    *
    * @param source The original signal providing the value and changes to it.
    * @param delay The time interval used for publishing. No more than one change of the value per `delay` will be published.
    * @tparam V The value type of the signal.
    * @return The new throttled signal of the same type as the original one.
    */
  def apply[V](source: Signal[V], delay: FiniteDuration): ThrottledSignal[V] = new ThrottledSignal(source, delay)
}
