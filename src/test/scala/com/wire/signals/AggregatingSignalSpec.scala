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

import org.scalatest._
import Threading._
import testutils._

import scala.concurrent.Promise

class AggregatingSignalSpec extends FeatureSpec with Matchers with OptionValues with BeforeAndAfter {

  feature("Aggregating incremental updates to an initial value") {
    scenario("new aggregator, no subscribers")(withAggregator { env =>
      import env._
      aggregator.value shouldBe None
      finishLoading()
      publisher ! "meep"
      aggregator.value shouldBe None
    })

    scenario("one subscriber")(withAggregator { env =>
      import env._
      val sub = subscribe()
      sub.value shouldBe None
      aggregator.value shouldBe None

      finishLoading()

      withDelay {
        sub.value.value shouldBe Seq(42)
      }
      aggregator.value.value shouldBe Seq(42)

      publisher ! "meep"

      withDelay {
        sub.value.value shouldBe Seq(42, 4)
      }
      aggregator.value.value shouldBe Seq(42, 4)

      aggregator.unsubscribeAll()

      publisher ! "yay"

      sub.value.value shouldBe Seq(42, 4)
      aggregator.value.value shouldBe Seq(42, 4)
    })

    scenario("events while subscribed but still loading")(withAggregator { env =>
      import env._
      val sub = subscribe()
      sub.value shouldBe None
      aggregator.value shouldBe None

      publisher ! "meep"
      publisher ! "moop"
      publisher ! "eek"

      Thread.sleep(333L)

      sub.value shouldBe None
      aggregator.value shouldBe None

      publisher ! "!"
      finishLoading()
      publisher ! "supercalifragilisticexpialidocious"

      withDelay {
        sub.value.value shouldBe Seq(42, 4, 4, 3, 1, 34)
      }
      aggregator.value.value shouldBe Seq(42, 4, 4, 3, 1, 34)
    })

    scenario("reload on re-wire")(withAggregator { env =>
      import env._
      val sub = subscribe()
      finishLoading()

      publisher ! "wow"
      publisher ! "such"
      publisher ! "publish"

      withDelay {
        sub.value.value shouldBe Seq(42, 3, 4, 7)
      }
      Thread.sleep(333L)
      aggregator.value.value shouldBe Seq(42, 3, 4, 7)

      aggregator.unsubscribeAll()

      sub.value.value shouldBe Seq(42, 3, 4, 7)
      aggregator.value.value shouldBe Seq(42, 3, 4, 7)

      publisher ! "publisher"

      sub.value.value shouldBe Seq(42, 3, 4, 7)
      aggregator.value.value shouldBe Seq(42, 3, 4, 7)

      promise = Promise[Seq[Int]]
      val sub2 = subscribe()

      sub2.value.value shouldBe Seq(42, 3, 4, 7)
      aggregator.value.value shouldBe Seq(42, 3, 4, 7)

      publisher ! "much amaze"

      sub2.value.value shouldBe Seq(42, 3, 4, 7)
      aggregator.value.value shouldBe Seq(42, 3, 4, 7)

      finishLoading(Seq(42, 3, 4, 7, 9))

      withDelay {
        sub2.value.value shouldBe Seq(42, 3, 4, 7, 9, 10)
      }
      aggregator.value.value shouldBe Seq(42, 3, 4, 7, 9, 10)
      sub.value.value shouldBe Seq(42, 3, 4, 7)

      publisher ! "much"
      publisher ! "amaze"

      withDelay {
        sub2.value.value shouldBe Seq(42, 3, 4, 7, 9, 10, 4, 5)
      }
      aggregator.value.value shouldBe Seq(42, 3, 4, 7, 9, 10, 4, 5)
    })
  }

  class Fixture {
    var promise: Promise[Seq[Int]] = Promise[Seq[Int]]
    val publisher: Publisher[String] = Publisher[String](None)

    def finishLoading(v: Seq[Int] = Seq(42)): Promise[Seq[Int]] = promise.success(v)

    private def loader = promise.future

    val aggregator = new AggregatingSignal[String, Seq[Int]](publisher, loader, (b, a) => b :+ a.length)

    case class Sub() {
      @volatile var value: Option[Seq[Int]] = None
    }

    def subscribe(): Sub = {
      val sub = Sub()
      aggregator { i => sub.value = Some(i) }
      sub
    }
  }

  def withAggregator(f: Fixture => Unit): Unit = {
    val fixture = new Fixture
    try f(fixture)
    finally fixture.aggregator.unsubscribeAll()
  }
}
