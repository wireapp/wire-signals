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
package com.wire.signals.testutils

import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.matchers.Matcher
import org.scalatest.time.{Nanoseconds, Span}
import org.threeten.bp.Instant

import scala.annotation.tailrec
import scala.concurrent.duration._

object Matchers {

  import DefaultPatience.{PatienceConfig, scaled, spanScaleFactor}
  import org.scalatest.Matchers._
  import org.scalatest.OptionValues._

  private[testutils] def patience(timeout: FiniteDuration = 5.seconds, interval: FiniteDuration = 20.millis): PatienceConfig =
    DefaultPatience.PatienceConfig(scaled(Span(timeout.toNanos, Nanoseconds)), scaled(Span(interval.toNanos, Nanoseconds)))

  implicit class FiniteDurationSyntax(val t: FiniteDuration) extends AnyVal {
    def tolerance: Tolerance = Tolerance(t)
  }

  def idle(someTime: FiniteDuration)(implicit p: PatienceConfig): Unit =
    retryUntilRightOrTimeout(Left(()))(p.copy(timeout = scaled(Span(someTime.toNanos, Nanoseconds))))

  final case class Tolerance(t: FiniteDuration)

  def beRoughly(d: Instant)(implicit tolerance: Tolerance): Matcher[Option[Instant]] =
    (be >= (d.toEpochMilli - (tolerance.t * spanScaleFactor).toMillis) and be <= (d.toEpochMilli + (tolerance.t * spanScaleFactor).toMillis))
      .compose(_.value.toEpochMilli)

  private def retryUntilRightOrTimeout[A, B](f: => Either[A, B])(p: PatienceConfig): Either[A, B] = {
    val startAt = System.nanoTime

    @tailrec def attempt(): Either[A, B] = {
      val result = f
      if (result.isRight || System.nanoTime - startAt > p.timeout.totalNanos) result
      else {
        Thread.sleep(p.interval.millisPart, p.interval.nanosPart)
        attempt()
      }
    }

    attempt()
  }
}

object DefaultPatience extends PatienceConfiguration {
  override implicit lazy val patienceConfig: PatienceConfig = Matchers.patience()
}
