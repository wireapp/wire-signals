package com.wire.signals

import java.util.concurrent.atomic.AtomicBoolean

import com.wire.signals.CancellableFuture.delayed

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class ThrottlingSignal[A](source: Signal[A], delay: FiniteDuration) extends ProxySignal[A](source) {

  import scala.concurrent.duration._

  private val waiting = new AtomicBoolean(false)
  @volatile private var lastDispatched = 0L

  override protected def computeValue(current: Option[A]): Option[A] = source.value

  override protected[signals] def notifySubscribers(ec: Option[ExecutionContext]): Unit =
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
