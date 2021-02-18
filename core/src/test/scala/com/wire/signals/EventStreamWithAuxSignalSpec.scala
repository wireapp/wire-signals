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

class EventStreamWithAuxSignalSpec extends munit.FunSuite {
  private lazy val aux = new SourceSignal[Int]
  private lazy val e = new SourceStream[String]()
  private lazy val r = new EventStreamWithAuxSignal(e, aux)

  private var events = List.empty[(String, Option[Int])]

  test("Subscribe, send stuff, unsubscribe, send more stuff") {
    val sub = r.onCurrent { r =>
      events = r :: events
    }(EventContext.Global)

    assertEquals(events, List.empty)

    e ! "meep"
    assertEquals(events, List(("meep", None)))

    aux ! 1
    assertEquals(events, List(("meep", None)))

    e ! "foo"
    assertEquals(events, List(("foo", Some(1)), ("meep", None)))

    e ! "meep"
    assertEquals(events, List(("meep", Some(1)), ("foo", Some(1)), ("meep", None)))

    aux ! 2
    assertEquals(events.size, 3)

    e ! "meep"
    assertEquals(events.size, 4)
    assertEquals(events.head, ("meep", Some(2)))

    sub.unsubscribe()

    e ! "foo"
    aux ! 3

    assertEquals(events.size, 4)
  }
}
