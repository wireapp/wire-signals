package com.wire.signals

import java.util.concurrent.atomic.AtomicReference

import com.wire.signals.utils._
import org.threeten.bp.{Duration, Instant}
import scala.concurrent.duration.{FiniteDuration, _}

import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future}

package object testutils {

  type Publisher[E] = SourceStream[E]

  object Publisher {
    def apply[E](ec: Option[ExecutionContext]): Publisher[E] = new SourceStream[E] {
      override val executionContext: Option[ExecutionContext] = ec
    }
  }

  implicit class SignalToSink[A](val signal: Signal[A]) extends AnyVal {
    def sink: SignalSink[A] = returning(new SignalSink[A])(_.subscribe(signal)(EventContext.Global))
  }

  class SignalSink[A] {
    @volatile private var sub = Option.empty[Subscription]

    def subscribe(s: Signal[A])(implicit ctx: EventContext): Unit = sub = Some(s(v => value = Some(v)))

    def unsubscribe(): Unit = sub.foreach { s =>
      s.destroy()
      sub = None
    }

    @volatile private[testutils] var value = Option.empty[A]

    def current: Option[A] = value
  }

  implicit class EnrichedInt(val a: Int) extends AnyVal {
    def times(f: => Unit): Unit = (1 to a).foreach(_ => f)
  }

  implicit class RichInstant(val a: Instant) extends AnyVal {
    def until(b: Instant): Duration = Duration.ofMillis(b.toEpochMilli - a.toEpochMilli)
  }

  implicit class RichOption[A](val opt: Option[A]) extends AnyVal {
    @inline final def fold2[B](ifEmpty: => B, f: A => B): B = if (opt.isEmpty) ifEmpty else f(opt.get) // option's catamorphism with better type inference properties than the one provided by the std lib
    def mapFuture[B](f: A => Future[B])(implicit ec: ExecutionContext): Future[Option[B]] = flatMapFuture(f(_).map(Some(_)))

    def flatMapFuture[B](f: A => Future[Option[B]]): Future[Option[B]] = fold2(Future.successful(None), f(_))
  }

  @tailrec
  def compareAndSet[A](ref: AtomicReference[A])(updater: A => A): A = {
    val current = ref.get
    val updated = updater(current)
    if (ref.compareAndSet(current, updated)) updated
    else compareAndSet(ref)(updater)
  }

  def withDelay[T](body: => T, delay: FiniteDuration = 300.millis)(implicit ec: ExecutionContext): CancellableFuture[T] =
    CancellableFuture.delayed(delay)(body)

  val DefaultTimeout: FiniteDuration = 5.seconds

  def result[A](future: Future[A])(implicit duration: FiniteDuration = DefaultTimeout): A =
    Await.result(future, duration)
}
