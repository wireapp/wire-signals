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

import com.wire.signals.testutils.result

class FilterSignalSpec extends munit.FunSuite {

  test("Value of a filtered signal") {
    val source = Signal(1)
    val chain = source.filter(_ % 2 == 0)
    assertEquals(chain.currentValue, None)
    source ! 2
    assertEquals(result(chain.future), 2)
    source ! 3
    assertEquals(chain.currentValue, None)
    source ! 4
    assertEquals(result(chain.future), 4)

  }

  test("Subscribing to a filtered signal") {
    val source = Signal(1)
    val chain = source.filter(_ % 2 == 0)
    val fan = Follower(chain).subscribed
    assertEquals(fan.lastReceived, None)
    source ! 2
    assertEquals(fan.received, Vector(2))
    source ! 3
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()
    assertEquals(fan.received, Vector(2))
    fan.subscribed
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()
    source ! 4
    assertEquals(fan.received, Vector(2))
    fan.subscribed
    assertEquals(fan.received, Vector(2, 4))
  }

  test("Possibly stale value after re-wiring") {
    val source = Signal(1)
    val chain = source.filter(_ % 2 == 0).map(identity)
    val fan = Follower(chain).subscribed
    source ! 2
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()

    (3 to 7) foreach source.!

    assertEquals(chain.currentValue, None)
    fan.subscribed
    assertEquals(fan.received, Vector(2))
    source ! 8
    assertEquals(fan.received, Vector(2, 8))
  }
}
