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

import org.threeten.bp.Instant
import Instant.now

import scala.concurrent.duration._
import com.wire.signals.testutils._

class ClockSignalSpec extends munit.FunSuite {
  import Roughly._
  implicit val instantTolerance: Long = 100.millis.toMillis

  @inline def sleep(duration: Duration): Unit = Thread.sleep(duration.toMillis)

  test("Subscribe, unsubscribe, re-subscribe") {
    val signal = ClockSignal(1.millis)

    val v1 = result(signal.future)
    assert(v1.roughlyEquals(now()))

    sleep(200.millis)
    assert(result(signal.future).roughlyEquals(v1.plusMillis(200)))

    val sub1 = signal.sink
    assert(sub1.current.forall(_.roughlyEquals(now())))

    sub1.unsubscribe()

    val v2 = result(signal.future)
    val Some(v3) = sub1.current

    sleep(200.millis)

    assert(result(signal.future).roughlyEquals(v2.plusMillis(200)))
    assert(sub1.current.forall(_.roughlyEquals(v3)))

    val sub2 = signal.sink
    assert(sub2.current.forall(_.roughlyEquals(now())))

    sleep(200.millis)
    assert(result(signal.future).roughlyEquals(now()))
    assert(sub1.current.forall(_.roughlyEquals(v3)))
    assert(sub2.current.forall(_.roughlyEquals(now())))
  }
}
