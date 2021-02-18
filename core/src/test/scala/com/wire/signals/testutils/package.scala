package com.wire.signals

import java.util.concurrent.atomic.AtomicReference

import java.util.Random
import scala.concurrent.duration.{FiniteDuration, _}
import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

package object testutils {
  private val localRandom = new ThreadLocal[Random] {
    override def initialValue: Random = new Random
  }

  def random: Random = localRandom.get

  implicit class SignalToSink[A](val signal: Signal[A]) extends AnyVal {
    def sink: SignalSink[A] = returning(new SignalSink[A])(_.subscribe(signal)(EventContext.Global))
  }

  class SignalSink[A] {
    @volatile private var sub = Option.empty[Subscription]

    def subscribe(s: Signal[A])(implicit ctx: EventContext = EventContext.Global): Unit = sub = Some(s(v => value = Some(v)))

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

  implicit class RichOption[A](val opt: Option[A]) extends AnyVal {
    @inline final def fold2[B](ifEmpty: => B, f: A => B): B = if (opt.isEmpty) ifEmpty else f(opt.get)
    // option's catamorphism with better type inference properties than the one provided by the std lib
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

  def tryResult[A](future: Future[A])(implicit duration: FiniteDuration = DefaultTimeout): Try[A] =
    try {
      Try(Await.result(future, duration))
    } catch {
      case t: Throwable => Failure(t)
    }


  def waitForResult[V](signal: Signal[V], expected: V, timeout: FiniteDuration): Boolean = {
    val offset = System.currentTimeMillis()
    while (System.currentTimeMillis() - offset < timeout.toMillis) {
      Try(result(signal.head)(timeout)) match {
        case Success(obtained) if obtained == expected => return true
        case Failure(_: TimeoutException) => return false
        case Failure(ex) =>
          println(s"waitForResult failed waiting for $expected, with exception: ${ex.getMessage} (${ex.getClass.getCanonicalName})")
        case _ =>
      }
      Thread.sleep(100)
    }
    false
  }

  def waitForResult[V](signal: Signal[V], expected: V): Boolean = {
    waitForResult(signal, expected, DefaultTimeout)
  }

  def waitForResult[E](stream: EventStream[E], expected: E, timeout: FiniteDuration): Boolean = {
    val offset = System.currentTimeMillis()
    while (System.currentTimeMillis() - offset < timeout.toMillis) {
      Try(result(stream.next)(timeout)) match {
        case Success(obtained) if obtained == expected => return true
        case Failure(_: TimeoutException) => return false
        case Failure(ex) =>
          println(s"waitForResult failed waiting for $expected, with exception: ${ex.getMessage} (${ex.getClass.getCanonicalName})")
        case _ =>
      }
      Thread.sleep(100)
    }
    false
  }

  def waitForResult[E](stream: EventStream[E], expected: E): Boolean = {
    waitForResult(stream, expected, DefaultTimeout)
  }

  /**
    * Very useful for checking that something DOESN'T happen (e.g., ensure that a signal doesn't get updated after
    * performing a series of actions)
    */
  def awaitAllTasks(implicit timeout: FiniteDuration = DefaultTimeout, dq: DispatchQueue): Unit = {
    if (!tasksCompletedAfterWait)
      throw new TimeoutException(s"Background tasks didn't complete in ${timeout.toSeconds} seconds")
  }

  def tasksRemaining(implicit dq: DispatchQueue): Boolean = dq.hasRemainingTasks

  private def tasksCompletedAfterWait(implicit timeout: FiniteDuration = DefaultTimeout, dq: DispatchQueue) = {
    val start = System.currentTimeMillis()
    val before = start + timeout.toMillis
    while(tasksRemaining && System.currentTimeMillis() < before) Thread.sleep(10)
    !tasksRemaining
  }

  def andThen(millis: Long = 100): Unit = Thread.sleep(millis)
}
