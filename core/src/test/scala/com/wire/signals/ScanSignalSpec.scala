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

import com.wire.signals.testutils.{andThen, result}

class ScanSignalSpec extends munit.FunSuite {
  private var received = Seq[Int]()
  private val capture = (value: Int) => received = received :+ value

  override def beforeEach(context: BeforeEach): Unit = {
    received = Seq.empty[Int]
  }

  test("Normal scanning") {
    val s = Signal(1)
    val scanned = s.scan(0)(_ + _)
    assertEquals(result(scanned.future), 1)

    scanned.onCurrent(capture)
    assertEquals(result(scanned.future), 1)
    assertEquals(received, Seq(1))

    Seq(2, 3, 1).foreach(s ! _)

    andThen()

    assertEquals(received, Seq(1, 3, 6, 7))
    assertEquals(result(scanned.future), 7)
  }

  test("disable autowiring when fetching current value") {
    val s = Signal(1)
    val scanned = s.scan(0)(_ + _)
    assertEquals(result(scanned.future), 1)

    andThen()

    Seq(2, 3, 1).foreach(s ! _)
    assertEquals(result(scanned.future), 7)
  }

  test("Chained scanning") {
    val s = Signal(1)
    val scanned = s.scan(0)(_ + _).scan(1)(_ * _)
    assertEquals(result(scanned.future), 1)

    scanned.onCurrent(capture)
    Seq(2, 3, 1).foreach(s ! _)

    andThen()

    assertEquals(result(scanned.future), 3 * 6 *7)
    assertEquals(received, Seq(1, 3, 3 * 6, 3 * 6 * 7))
  }

  test("No subscribers will be left behind") {
    val s = Signal(1)
    val scanned = s.scan(0)(_ + _)
    val sub = scanned.onCurrent(capture)
    Seq(2, 3) foreach (s ! _)
    assert(s.hasSubscribers)
    assert(scanned.hasSubscribers)
    sub.destroy()
    assert(!s.hasSubscribers)
    assert(!scanned.hasSubscribers)
    s ! 4
    assertEquals(received, Seq(1, 3, 6))
  }
}
