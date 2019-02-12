import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import org.threeten.bp
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit
import threading.CancellableFuture

import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

package object utils {
  @tailrec
  def compareAndSet[A](ref: AtomicReference[A])(updater: A => A): A = {
    val current = ref.get
    val updated = updater(current)
    if (ref.compareAndSet(current, updated)) updated
    else compareAndSet(ref)(updater)
  }

  def withDelay[T](body: => T, delay: FiniteDuration = 300.millis)(implicit ec: ExecutionContext): CancellableFuture[T] = CancellableFuture.delayed(delay)(body)

  val DefaultTimeout: FiniteDuration = 5.seconds

  @deprecated("await does not fail tests if the provided future fails! Better to just always use result and discard the return value")
  def await(future: Future[_])(implicit duration: FiniteDuration = DefaultTimeout): Unit =
    Await.ready(future, duration)

  def result[A](future: Future[A])(implicit duration: FiniteDuration = DefaultTimeout): A =
    Await.result(future, duration)

  implicit class EnrichedInt(val a: Int) extends AnyVal {
    def times(f: => Unit): Unit = (1 to a) foreach (_ => f)
  }

  implicit class RichInstant(val a: bp.Instant) extends AnyVal {
    def javaDate: Date = new Date(a.toEpochMilli)
    def until(b: bp.Instant): bp.Duration = bp.Duration.ofMillis(b.toEpochMilli - a.toEpochMilli)
    def -(d: FiniteDuration) = a.minusNanos(d.toNanos)
    def +(d: FiniteDuration): Instant = a.plusNanos(d.toNanos)
    def +(d: bp.Duration): Instant = a.plusNanos(d.toNanos)
    def isAfter(d: Date): Boolean = a.toEpochMilli > d.getTime
    def isBefore(d: Date): Boolean = a.toEpochMilli < d.getTime
    def isToday: Boolean = a.truncatedTo(ChronoUnit.DAYS) == Instant.now.truncatedTo(ChronoUnit.DAYS)
    def max(b: bp.Instant) = if (a isBefore b) b else a
    def max(b: Date) = if (a.toEpochMilli < b.getTime) b else a
    def min(b: bp.Instant) = if (a isBefore b) a else b
    def min(b: Date) = if (a.toEpochMilli < b.getTime) a else b
    def >=(b: bp.Instant) = !a.isBefore(b)
    def <=(b: bp.Instant) = !a.isAfter(b)
    def remainingUntil(b: bp.Instant): FiniteDuration = if (a isBefore b) FiniteDuration(b.toEpochMilli - a.toEpochMilli, TimeUnit.MILLISECONDS) else Duration.Zero
    def toFiniteDuration: FiniteDuration = FiniteDuration(a.toEpochMilli, MILLISECONDS)
  }

  implicit class RichOption[A](val opt: Option[A]) extends AnyVal {
    @inline final def fold2[B](ifEmpty: => B, f: A => B): B = if (opt.isEmpty) ifEmpty else f(opt.get) // option's catamorphism with better type inference properties than the one provided by the std lib
    def mapFuture[B](f: A => Future[B])(implicit ec: ExecutionContext): Future[Option[B]] = flatMapFuture(f(_).map(Some(_)))
    def flatMapFuture[B](f: A => Future[Option[B]]): Future[Option[B]] = fold2(Future.successful(None), f(_))
  }

  object RichOption {
    def sequence[A](opts: Iterable[Option[A]]): Option[List[A]] = traverse(opts)(identity)
    def traverse[A,B](opts: Iterable[A])(f: A => Option[B]): Option[List[B]] =
      opts.foldRight(Option(List.empty[B])) { (value, acc) =>
        for {
          a <- acc
          v <- f(value)
        } yield v :: a
      }
  }
}
