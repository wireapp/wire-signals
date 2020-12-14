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

class FlatMapSignalSpec extends munit.FunSuite {

  private var received = Vector.empty[Int]
  private val capture = (value: Int) => received :+= value

  override def beforeEach(context: BeforeEach): Unit = {
    received = Vector.empty[Int]
  }

  test("Normal flatmapping") {
    val s = Signal(0)
    val s1 = Signal(1)
    val s2 = Signal(2)

    val fm = s.flatMap { Seq(s1, s2) }
    fm(capture)

    assertEquals(fm.value, Some(1))
    s ! 1
    assertEquals(fm.value, Some(2))
    s1 ! 3
    assertEquals(fm.value, Some(2))
    s2 ! 4
    assertEquals(fm.value, Some(4))
    assertEquals(received, Vector(1, 2, 4))
  }

  test("Chained flatmapping") {
    val s = Seq.fill(6)(Signal(0))

    val fm = s(0).flatMap { Seq(s(1), s(2)) }.flatMap { Seq(s(3), s(4), s(5)) }
    fm(capture)

    s(5) ! 5
    s(2) ! 2
    s(0) ! 1

    assertEquals(fm.value, Some(5))
    assertEquals(received, Vector(0, 5))
  }

  test("FlatMapping an empty signal") {
    val signal = Signal[Int]()
    val chain = signal.flatMap(_ => Signal(42))

    assert(chain.empty)
    signal ! Int.MaxValue
    assertEquals(result(chain.future), 42)
  }

  test("FlatMapping to an empty signal") {
    val signal = Signal(0)
    val signalA = Signal[String]()
    val signalB = Signal[String]()
    val chain = signal.flatMap(n => if (n % 2 == 0) signalA else signalB)
    val fan = Follower(chain).subscribed
    assert(chain.empty)
    assertEquals(fan.received, Vector.empty)

    signalA ! "a"
    assertEquals(result(chain.future), "a")
    assertEquals(fan.received, Vector("a"))

    signal ! 1
    assert(chain.empty)
    assertEquals(fan.received, Vector("a"))

    signalA ! "aa"
    assert(chain.empty)
    assertEquals(fan.received, Vector("a"))

    signalB ! "b"
    assertEquals(result(chain.future), "b")
    assertEquals(fan.received, Vector("a", "b"))
  }


  test("No subscribers will be left behind") {
    val s = Signal(0)
    val s1 = Signal(1)
    val s2 = Signal(2)

    val fm = s.flatMap { Seq(s1, s2) }
    val sub = fm(capture)

    s1 ! 3
    s2 ! 4

    assert(s.hasSubscribers)
    assert(s1.hasSubscribers)
    assert(!s2.hasSubscribers)
    assert(fm.hasSubscribers)

    sub.destroy()
    assert(!s.hasSubscribers)
    assert(!s1.hasSubscribers)
    assert(!s2.hasSubscribers)
    assert(!fm.hasSubscribers)

    s1 ! 5
    s ! 1
    s2 ! 6
    assertEquals(received, Vector(1, 3))
  }

  test("wire and un-wire both source signals") {
    val s1 = new IntSignal
    val s2 = new IntSignal
    val s = s1.flatMap { _ => s2 }

    assert(!s1.isWired)
    assert(!s2.isWired)
    val o = s { _ => () }

    assert(s1.isWired)
    assert(s2.isWired)

    o.disable()
    assert(!s1.isWired)
    assert(!s2.isWired)
  }

  test("un-wire discarded signal on change") {
    val s = new IntSignal(0)
    val s1 = new IntSignal(1)
    val s2 = new IntSignal(2)

    val fm = s flatMap Seq(s1, s2)
    val o = fm(_ => ())

    assert(s.isWired)
    assert(s1.isWired)
    assert(!s2.isWired)

    s ! 1

    assert(s.isWired)
    assert(!s1.isWired)
    assert(s2.isWired)

    o.destroy()
    assert(!s.isWired)
    assert(!s1.isWired)
    assert(!s2.isWired)
  }

  test("update value when wired") {
    val s = new IntSignal(0)
    val fm = s.flatMap(Signal(_))

    assertEquals(s.value, Some(0))
    assertEquals(fm.value, None)

    s ! 1
    assertEquals(s.value, Some(1))
    assertEquals(fm.value, None) // not updated because signal is not autowired
    val o = fm(_ => ())

    assertEquals(fm.value, Some(1)) // updated when wiring
  }

  test("possibly stale value after re-wiring") {
    val source = Signal(1)
    val chain = source.flatMap(n => if (n % 2 == 0) Signal(n) else Signal[Int]()).map(identity)
    val fan = Follower(chain).subscribed
    source ! 2
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()

    (3 to 7).foreach { source ! _ }

    assert(chain.empty)
    fan.subscribed
    assertEquals(fan.received, Vector(2))
    source ! 8
    assertEquals(fan.received, Vector(2, 8))
  }
}
