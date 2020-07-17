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

import java.util.concurrent.TimeoutException

import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.matchers.Matcher
import org.scalatest.time.{Nanoseconds, Span}
import org.threeten.bp.Instant

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object Matchers {

  import DefaultPatience.{PatienceConfig, scaled, spanScaleFactor}
  import org.scalatest.Matchers._
  import org.scalatest.OptionValues._

  def patience(timeout: FiniteDuration = 5.seconds, interval: FiniteDuration = 20.millis): PatienceConfig =
    DefaultPatience.PatienceConfig(scaled(Span(timeout.toNanos, Nanoseconds)), scaled(Span(interval.toNanos, Nanoseconds)))

  implicit class FiniteDurationSyntax(val t: FiniteDuration) extends AnyVal {
    def timeout: PatienceConfig = patience(timeout = t)

    def tolerance: Tolerance = Tolerance(t)
  }

  implicit class FutureSyntax[A, B](val f: A)(implicit ev: A => Future[B]) {
    def afterwards[C](clue: => String = "")(g: B => C)(implicit p: PatienceConfig): C =
      g(retryUntilRightOrTimeout(f.value.fold2(Left(()), Right(_)))(p) match {
        case Left(()) => throw new TimeoutException(s"future did not complete in time ($p)${if (clue.nonEmpty) ": " else ""}$clue")
        case Right(Failure(l)) => throw l
        case Right(Success(r)) => r
      })

    def whenReady[C](clue: => String = "")(g: Try[B] => C)(implicit p: PatienceConfig): C =
      g(retryUntilRightOrTimeout(f.value.fold2(Left(()), Right(_)))(p) match {
        case Left(()) => throw new TimeoutException(s"future did not complete in time ($p)${if (clue.nonEmpty) ": " else ""}$clue")
        case Right(r) => r
      })

    def await(clue: => String = "")(implicit p: PatienceConfig): B = afterwards(clue)(identity)
  }

  implicit class PromiseSyntax[A](val pa: Promise[A]) extends AnyVal {
    def await(clue: => String = "")(implicit p: PatienceConfig): A = pa.future.await(clue)(p)
  }

  def soon[A](f: => A)(implicit p: PatienceConfig): A =
    retryUntilRightOrTimeout(try Right(f) catch {
      case NonFatal(e) => Left(e)
    })(p).fold(e => throw e, identity)

  def idle(someTime: FiniteDuration)(implicit p: PatienceConfig): Unit =
    retryUntilRightOrTimeout(Left(()))(p.copy(timeout = scaled(Span(someTime.toNanos, Nanoseconds))))

  case class Tolerance(t: FiniteDuration)

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
