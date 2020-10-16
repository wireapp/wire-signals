/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.wire.signals

import org.threeten.bp.{Clock, Instant}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import org.threeten.bp.Instant.now

import scala.concurrent.ExecutionContext

object ClockSignal {
  /** Creates a new clock signal which will update its value to the current instant once per given time interval.
    *
    * @param interval The duration of the time interval between two "ticks" of the clock signal.
    * @param clock The clock configured to produce the new values of the signal.
    * @param ec The execution context in which the clock signal works. If the context is busy, the value of the signal
    *           may be updated with a delay.
    * @return A new clock signal with the value type [[Instant]]
    */
  def apply(interval: FiniteDuration, clock: Clock = Clock.systemUTC())(implicit ec: ExecutionContext = Threading.defaultContext): ClockSignal =
    new ClockSignal(interval, clock)
}

/** A signal which once every given time `interval` updates its value to the current instant. Can be used to periodically
  * perform some actions. The initial value of the clock signal will be computed the moment the first subscriber function
  * is registered to it, or immediately if `disableAutowiring` is used.
  *
  * @see [[Clock]]
  * @see [[FiniteDuration]]
  * @see [[Instant]]
  *
  * @todo Move to the extensions project.
  *
  * @param interval The duration of the time interval between two "ticks" of the clock signal.
  * @param clock The clock configured to produce the new values of the signal.
  * @param ec The execution context in which the clock signal works. If the context is busy, the value of the signal
  *           may be updated with a delay.
  */
final class ClockSignal(val interval: FiniteDuration, val clock: Clock)
                       (implicit ec: ExecutionContext)
  extends SourceSignal[Instant](Some(now(clock))) {
  private var delay = CancellableFuture.successful(())

  /** Called automatically once per `interval` but can also be called manually to force the update of the clock signal's value. */
  def refresh(): Unit = if (wired) {
    publish(now(clock))
    delay.cancel()
    delay = CancellableFuture.delayed(interval)(refresh())
  }

  /** Used to force a refresh in tests when the clock is advanced */
  def checkAndRefresh()(implicit ec: ExecutionContext): Unit =
    if (interval <= (now(clock).toEpochMilli - value.getOrElse(Instant.EPOCH).toEpochMilli).millis) refresh()

  override def onWire(): Unit = refresh()
}
