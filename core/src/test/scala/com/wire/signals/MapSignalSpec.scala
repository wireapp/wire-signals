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

import com.wire.signals.testutils.andThen

class MapSignalSpec extends munit.FunSuite {
  private var received = Seq[Int]()
  private val capture = (value: Int) => received = received :+ value

  override def beforeEach(context: BeforeEach): Unit = {
    received = Seq.empty[Int]
  }

  test("Normal mapping") {
    val s = Signal(1)
    val m = s map (_ * 2)
    m.onCurrent(capture)

    Seq(2, 3, 1) foreach (s ! _)
    assertEquals(received, Seq(2, 4, 6, 2))
  }

  test("Mapping nulls") {
    @volatile var vv: Option[String] = Some("invalid")
    val s = Signal("start")
    val m = s.map(Option(_))
    m.foreach { vv = _ }
    andThen()
    assertEquals(vv, Some("start"))
    s ! "meep"
    andThen()
    assertEquals(vv, Some("meep"))
    s ! null
    andThen()
    assertEquals(vv, None)
    s ! "moo"
    andThen()
    assertEquals(vv, Some("moo"))
  }

  test("Chained mapping") {
    val s = Signal(1)
    val m = s.map(_ * 2).map(_ * 3)
    m.onCurrent(capture)
    Seq(2, 3, 1).foreach(s ! _)
    assertEquals(received, Seq(6, 12, 18, 6))
  }

  test("No subscribers will be left behind") {
    val s = Signal(1)
    val f = s map (_ * 2)
    val sub = f.onCurrent(capture)
    Seq(2, 3) foreach (s ! _)
    assert(s.hasSubscribers)
    assert(f.hasSubscribers)
    sub.destroy()
    assert(!s.hasSubscribers)
    assert(!f.hasSubscribers)
    s ! 4
    assertEquals(received, Seq(2, 4, 6))
  }

  test("wire and un-wire a mapped signal") {
    lazy val s1 = Signal[Int](0)
    lazy val s = s1.map { _ => 1 }

    assert(s1.wired) // source and const signals have autowiring disabled, so they're "wired"by default

    val o = s.foreach { _ => () }

    assert(s.wired)

    o.disable()

    assert(!s.wired)

    o.enable()
    assert(s.wired)

    o.destroy()
    assert(!s.wired)
  }
}
