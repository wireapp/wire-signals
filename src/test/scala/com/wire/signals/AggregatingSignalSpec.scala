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

import Threading._
import testutils._

import scala.concurrent.Promise

class AggregatingSignalSpec extends munit.FunSuite {
  test("new aggregator, no subscribers")(withAggregator { env =>
    import env._
    assertEquals(aggregator.value, None)
    finishLoading()
    publisher ! "meep"
    assertEquals(aggregator.value, None)
  })

  test("one subscriber")(withAggregator { env =>
    import env._
    val sub = subscribe()
    assertEquals(sub.value, None)
    assertEquals(aggregator.value, None)

    finishLoading()

    withDelay {
      assertEquals(sub.value.get, Seq(42))
    }
    assertEquals(result(aggregator.future), Seq(42))

    publisher ! "meep"

    withDelay {
      assertEquals(sub.value.get, Seq(42, 4))
    }
    assertEquals(result(aggregator.future), Seq(42, 4))

    aggregator.unsubscribeAll()

    publisher ! "yay"

    andThen()

    assertEquals(sub.value.get, Seq(42, 4))
    assertEquals(result(aggregator.future), Seq(42, 4))
  })

  test("events while subscribed but still loading")(withAggregator { env =>
    import env._
    val sub = subscribe()
    assertEquals(sub.value, None)
    assertEquals(aggregator.value, None)

    publisher ! "meep"
    publisher ! "moop"
    publisher ! "eek"

    andThen()

    assertEquals(sub.value, None)
    assertEquals(aggregator.value, None)

    publisher ! "!"
    finishLoading()
    publisher ! "supercalifragilisticexpialidocious"

    withDelay {
      assertEquals(sub.value.get, Seq(42, 4, 4, 3, 1, 34))
      assertEquals(result(aggregator.future), Seq(42, 4, 4, 3, 1, 34))
    }
  })

  test("reload on re-wire")(withAggregator { env =>
    import env._
    val sub = subscribe()
    finishLoading()

    publisher ! "wow"
    publisher ! "such"
    publisher ! "publish"

    withDelay {
      assertEquals(sub.value.get, Seq(42, 3, 4, 7))
    }

    andThen()

    assertEquals(result(aggregator.future), Seq(42, 3, 4, 7))

    aggregator.unsubscribeAll()

    // still holds to the last computed value after unsubscribing
    assertEquals(sub.value.get, Seq(42, 3, 4, 7))
    assertEquals(result(aggregator.future), Seq(42, 3, 4, 7))

    // triggers reload
    publisher ! "publisher"

    andThen()
    // still the old value
    assertEquals(sub.value.get, Seq(42, 3, 4, 7))
    // a new value after reload
    assertEquals(result(aggregator.future), Seq(42, 9))

    promise = Promise[Seq[Int]]()
    val sub2 = subscribe()

    assertEquals(sub2.value.get,Seq(42, 9))
    assertEquals(result(aggregator.future), Seq(42, 9))

    publisher ! "much amaze"

    assertEquals(sub2.value.get, Seq(42, 9, 10))
    assertEquals(result(aggregator.future), Seq(42, 9, 10))

    finishLoading(Seq(42, 3, 4, 7, 9))

    withDelay {
      assertEquals(sub2.value.get, Seq(42, 3, 4, 7, 9, 10))
    }
    assertEquals(result(aggregator.future), Seq(42, 9, 10))
    assertEquals(sub.value.get, Seq(42, 3, 4, 7))

    publisher ! "much"
    publisher ! "amaze"

    withDelay {
      assertEquals(sub2.value.get, Seq(42, 3, 4, 7, 9, 10, 4, 5))
    }
    assertEquals(result(aggregator.future), Seq(42, 9, 10, 4, 5))
  })

  final class Sub(@volatile var value: Option[Seq[Int]] = None)

  object Sub {
    def apply(): Sub = new Sub()
  }

  final class AggregatingFixture {
    var promise: Promise[Seq[Int]] = Promise[Seq[Int]]()
    val publisher: SourceStream[String] = new SourceStream[String]()

    def finishLoading(v: Seq[Int] = Seq(42)): Promise[Seq[Int]] = promise.success(v)

    private def loader = promise.future

    val aggregator = new AggregatingSignal[String, Seq[Int]](
      loader,
      publisher,
      (b, a) => b :+ a.length
    )

    def subscribe(): Sub = {
      val sub = Sub()
      aggregator { i => sub.value = Some(i) }
      sub
    }
  }

  def withAggregator(f: AggregatingFixture => Unit): Unit = {
    val fixture = new AggregatingFixture
    try f(fixture)
    finally fixture.aggregator.unsubscribeAll()
  }
}
