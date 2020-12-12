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

import java.util.concurrent.atomic.AtomicReference

import com.wire.signals.testutils._

import org.threeten.bp.Instant

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import Threading.defaultContext
import CancellableFuture.delayed

class ThrottledSignalSpec extends munit.FunSuite {

  test("throttle serial events") {
    100 times spying { spy =>
      val s = Signal(1)
      val m = s.throttle(2.millis)
      m(spy.capture)
      assertEquals(spy.received.get.map(_._1), Vector[Int](1))

      (2 to 3) foreach { v =>
        Thread.sleep(1)
        s ! v
        s ! v + 10
      }

      withDelay {
        assertEquals(spy.received.get.last._1, 13)
      }
    }
  }

  test("throttle parallel events")(spying { spy =>
    import FiniteDuration.FiniteDurationIsOrdered, spy._

    received.set(Vector.empty[(Int, Instant)])
    val s = Signal[Int]()
    val m = s.throttle(50.millis)
    m(capture)

    val updates = Future.sequence((1 to 10000).map(n => delayed(random.nextInt(500).millis) {
      s ! n
      s ! n + 1000
    }.future))

    Await.result(updates, 5.seconds)
    val sorted = received.get.map(_._2).sorted
    val interval = sorted.zip(sorted.tail).map { case (aa, bb) => (aa until bb).toMillis.millis }

    interval.foreach { time => assert(time >= 45.millis) }
  })

  test("wire and un-wire throttled signal") {
    lazy val s = new IntSignal(0)
    val m = s.throttle(100.millis)
    assert(!s.isWired)

    val o = m { _ => () }
    assert(s.isWired)

    o.disable()
    assert(!s.isWired)

    o.enable()
    assert(s.isWired)

    o.destroy()
    assert(!s.isWired)
  }

  class Spy {
    val received = new AtomicReference(Vector.empty[(Int, Instant)])
    val capture: Int => Unit = { value => compareAndSet(received)(_ :+ (value -> Instant.now)) }
  }

  def spying(f: Spy => Unit): Unit = f(new Spy)
}
