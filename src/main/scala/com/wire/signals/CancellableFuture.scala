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

import java.util.TimerTask

import com.wire.signals.utils.returning

import scala.collection.generic.CanBuild
import scala.concurrent._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.ref.WeakReference
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

/** CancellableFuture is an object that for all practical uses works like a future
  * but enables the user to cancel the operation. A cancelled future fails with
  * `CancellableFuture.CancelException` so the listener can differentiate between this
  * and other failure reasons.
  *
  * @see https://github.com/wireapp/wire-signals/wiki/Overview
  *
  * @param promise The promise a new cancellable future wraps around.
  *                Note that usually you will create a new cancellable future by lifting a future or simply by
  *                providing the expression to be executed.
  */
class CancellableFuture[+T](promise: Promise[T]) extends Awaitable[T] { self =>
  import CancellableFuture._

  /** Gives direct access to the underlying future.
    */
  @inline def future: Future[T] = promise.future

  /** Tries to cancel the future. If successful, the future fails with `CancellableFuture.CancelException`
    *
    * @return true if the future was cancelled, false if it was completed beforehand (with success or failure).
    */
  def cancel(): Boolean = fail(CancelException)

  /** Tries to fails the future with the given exception as the failure's reason.
    *
    * @return `true` if the future was cancelled, `false` if it was completed beforehand (with success or failure).
    */
  def fail(ex: Exception): Boolean = promise.tryFailure(ex)

  /** Same as `Future.onComplete`.
    */
  @inline def onComplete[U](f: Try[T] => U)(implicit executor: ExecutionContext): Unit = future.onComplete(f)

  /** Same as `Future.foreach`.
    */
  @inline def foreach[U](pf: T => U)(implicit executor: ExecutionContext): Unit = future.foreach(pf)

  /** When the future is cancelled (NOT failed with any other reason) the provided function is executed.
    * If the future is already cancelled, this will either be applied immediately or be scheduled
    * asynchronously (same as in `Future.onComplete`).
    */
  def onCancelled(body: => Unit)(implicit executor: ExecutionContext): Unit = future.onComplete {
    case Failure(CancelException) => body
    case _ =>
  }

  /** Creates a new cancellable future by applying the `f` function to the successful result
    * of this one. If this future is completed with an exception then the new future will
    * also contain this exception. If the operation after the cancelling was provided,
    * it will be carried over to the new cancellable future.
    */
  def map[U](f: T => U)(implicit executor: ExecutionContext): CancellableFuture[U] = {
    val p = Promise[U]()
    @volatile var cancelFunc = Option(() => self.cancel())

    future.onComplete { v =>
      cancelFunc = None
      p.tryComplete(v.flatMap(res => Try(f(res))))
    }

    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          Future(cancelFunc.foreach(_ ()))(executor)
          true
        } else false
      }
    }
  }

  /** Creates a new cancellable future by applying the predicate `p` to the current one.
    * If the original future completes with success, but the result does not satisfies
    * the predicate, the resulting future will fail with a `NoSuchElementException`.
    */
  def filter(p: T => Boolean)(implicit executor: ExecutionContext): CancellableFuture[T] = flatMap { res =>
    if (p(res)) CancellableFuture.successful(res)
    else CancellableFuture.failed(new NoSuchElementException(s"CancellableFuture.filter predicate is not satisfied"))
  }

  /** Used by for-comprehensions.
    */
  final def withFilter(p: T => Boolean)(implicit executor: ExecutionContext): CancellableFuture[T] = filter(p)(executor)

  /** Creates a new cancellable future by mapping the value of the current one, if the given partial function
    * is defined at that value. Otherwise, the resulting cancellable future will fail with a `NoSuchElementException`.
    *
    * @todo Test if the cancel operation is carried over.
    */
  def collect[S](pf: PartialFunction[T, S])(implicit executor: ExecutionContext): CancellableFuture[S] =
    flatMap {
      case r if pf.isDefinedAt(r) => CancellableFuture(pf.apply(r))
      case t => CancellableFuture.failed(new NoSuchElementException("CancellableFuture.collect partial function is not defined at: " + t))
    }

  /** Creates a new cancellable future by applying a function to the successful result of this future,
    * and returns the result of the function as the new future.
    *  If this future fails or is cancelled, the cancel operation and the result is carried over.
    */
  def flatMap[S](f: T => CancellableFuture[S])(implicit executor: ExecutionContext): CancellableFuture[S] = {
    val p = Promise[S]()
    @volatile var cancelFunc = Option(() => self.cancel())

    self.future onComplete { res =>
      cancelFunc = None
      if (!p.isCompleted) res match {
        case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[S]])
        case Success(v) =>
          Try(f(v)) match {
            case Success(fut) =>
              cancelFunc = Option(() => fut.cancel())
              fut onComplete { res =>
                cancelFunc = None
                p.tryComplete(res)
              }
              if (p.isCompleted) fut.cancel()
            case Failure(t) =>
              p.tryFailure(t)
          }
      }
    }

    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          Future(cancelFunc.foreach(_()))(executor)
          true
        } else false
      }
    }
  }

  /** Creates a new cancellable future that will handle any matching throwable that this future might contain.
    * If there is no match, or if this future contains a valid result then the new future will contain the same.
    * Works also if the current future is cancelled.
    */
  def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): CancellableFuture[U] =
    recoverWith(pf.andThen(CancellableFuture.successful))

  /** Creates a new cancellable future that will handle any matching throwable that the current one
    * might contain by assigning it a value of another future. Works also if the current future is cancelled.
    *
    * If there is no match, or if this cancellable future contains a valid result then the new future will
    * contain the same result.
    */
  def recoverWith[U >: T](pf: PartialFunction[Throwable, CancellableFuture[U]])
                         (implicit executor: ExecutionContext): CancellableFuture[U] = {
    val p = Promise[U]()
    @volatile var cancelFunc = Option(() => self.cancel())

    future.onComplete { res =>
      cancelFunc = None
      if (!p.isCompleted) res match {
        case Failure(t) if pf.isDefinedAt(t) =>
          val fut = pf.applyOrElse(t, (_: Throwable) => this)
          cancelFunc = Some(() => fut.cancel())
          fut onComplete { res =>
            cancelFunc = None
            p.tryComplete(res)
          }
          if (p.isCompleted) fut.cancel()
        case other =>
          p.tryComplete(other)
      }
    }

    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          Future(cancelFunc.foreach(_ ()))(executor)
          true
        } else false
      }
    }
  }

  /** Flattens a nested cancellable future.
    */
  @inline def flatten[S](implicit executor: ExecutionContext, evidence: T <:< CancellableFuture[S]): CancellableFuture[S] =
    flatMap(x => x)

  /** Creates a new CancellableFuture from the current one and the provided one.
    * The new future completes with success only if both original futures complete with success.
    * If any of the fails or is cancelled, the resulting future fails or is cancelled as well.
    * If the user cancels the resulting future, both original futures are cancelled.
    */
  @inline def zip[U](other: CancellableFuture[U])(implicit executor: ExecutionContext): CancellableFuture[(T, U)] =
    CancellableFuture.zip(self, other)

  /** Same as `Future.ready`.
    *'''''This method should not be called directly; use `Await.ready` instead.'''''
    */
  @throws[InterruptedException](classOf[InterruptedException])
  @throws[TimeoutException](classOf[TimeoutException])
  override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
    future.ready(atMost)
    this
  }

  /** Same as `Future.result`.
    * '''''This method should not be called directly; use `Await.result` instead.'''''
    */
  @throws[Exception](classOf[Exception])
  override def result(atMost: Duration)(implicit permit: CanAwait): T = future.result(atMost)

  /** Fails the future after the given timeout.
    *
    * @todo timeout should generate different exception
    *
    * @param timeout FiniteDuration
    * @throws TimeoutException if the future is not completed after the given timeout
    * @return The current cancellable future
    */
  def withTimeout(timeout: FiniteDuration)(implicit ec: ExecutionContext): CancellableFuture[T] = {
    val f = CancellableFuture.delayed(timeout)(this.fail(new TimeoutException(s"timedOut($timeout)")))
    onComplete(_ => f.cancel())
    this
  }

  /** Registers the cancellable future in the given event context.
    * When the event context is stopped, the future will be cancelled.
    * The subscription is also returned so it can be managed manually.
    *
    * @see com.wire.signals.EventContext
    * @see com.wire.signals.Events
    */
  def withAutoCanceling(implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(new BaseSubscription(WeakReference(eventContext)) {
      override def onUnsubscribe(): Unit = {
        cancel()
        eventContext.unregister(this)
      }

      override def onSubscribe(): Unit = {}
    })(eventContext.register)
}

object CancellableFuture {
  import language.implicitConversions
  implicit def toFuture[T](f: CancellableFuture[T]): Future[T] = f.future

  implicit class RichFuture[T](val future: Future[T]) extends AnyVal {
    def toCancellable: CancellableFuture[T] = CancellableFuture.lift(future)
  }

  /** When the cancellable future is cancelled, `CancelException` is provided as the reason
    * of failure of the underlying future.
    */
  case object CancelException extends Exception("Operation cancelled") with NoStackTrace

  private final class PromiseCompletingRunnable[T](body: => T) extends Runnable {
    val promise: Promise[T] = Promise[T]()

    override def run(): Unit = if (!promise.isCompleted) promise.tryComplete(Try(body))
  }

  /** Creates `CancellableFuture[T]` from the given function with the result type of `T`.
    *
    * @param body The function to be executed asynchronously
    * @param executor The execution context
    * @tparam T The result type of the given function
    * @return A cancellable future executing the function
    */
  def apply[T](body: => T)(implicit executor: ExecutionContext): CancellableFuture[T] =
    new CancellableFuture(
      returning(new PromiseCompletingRunnable(body))(executor.execute).promise
    )

  /** Turns a regular `Future[T]` into `CancellableFuture[T]`.
    *
    * @param future The future to be lifted
    * @param onCancel An optional function to be called if the new cancellable future is cancelled
    * @tparam T The future's result type
    * @return A new cancellable future wrapped over the original future
    */
  def lift[T](future: Future[T], onCancel: => Unit = ()): CancellableFuture[T] = {
    val p = Promise[T]()
    p.tryCompleteWith(future)
    new CancellableFuture(p) {
      override def cancel(): Boolean =
        if (super.cancel()) {
          onCancel
          true
        } else false
    }
  }

  /** Creates an empty cancellable future that will start its execution after the given time.
    * Typically used together with `map` or a similar method to execute computation with a delay.
    */
  def delay(duration: FiniteDuration): CancellableFuture[Unit] =
    if (duration <= Duration.Zero) successful(())
    else {
      val p = Promise[Unit]()
      val task = Threading.schedule(() => p.trySuccess(()), duration.toMillis)
      new CancellableFuture(p) {
        override def cancel(): Boolean = {
          task.cancel()
          super.cancel()
        }
      }
    }

  /** Creates an empty cancellable future which will repeat the mapped computation every given `duration`
    * until cancelled. The first computation is executed with `duration` delay. If the operation takes
    * longer than `duration` it will not be cancelled after the given time, but also the ability to cancel
    * it will be lost.
    * Typically used together with `map` or a similar method.
    *
    * @todo Test if it works as designed.
    */
  def repeat(duration: Duration)(implicit ec: ExecutionContext): CancellableFuture[Unit] = {
    if (duration <= Duration.Zero) successful(())
    else {
      val promise = Promise[Unit]()
      new CancellableFuture(promise) {
        @volatile
        private var currentTask: Option[TimerTask] = None
        startNewTimeoutLoop()

        private def startNewTimeoutLoop(): Unit = {
          currentTask = Some(Threading.schedule(
            () => startNewTimeoutLoop(),
            duration.toMillis
          ))
        }

        override def cancel(): Boolean = {
          // TODO: possible race condition
          currentTask.foreach(_.cancel())
          currentTask = None
          super.cancel()
        }
      }
    }
  }

  /** A utility method that combines `delay` with `map`.
    */
  def delayed[T](duration: FiniteDuration)(body: => T)(implicit executor: ExecutionContext): CancellableFuture[T] =
    if (duration <= Duration.Zero) CancellableFuture(body)
    else delay(duration).map { _ => body }

  /** Creates an already completed `CancellableFuture[T]` with the specified result.
    */
  def successful[T](res: T): CancellableFuture[T] = new CancellableFuture[T](Promise.successful(res)) {
    override def toString: String = s"CancellableFuture.successful($res)"
  }

  /** Creates an already failed `CancellableFuture[T] `with the given throwable as the failure reason.
    */
  def failed[T](ex: Throwable): CancellableFuture[T] = new CancellableFuture[T](Promise.failed(ex)) {
    override def toString: String = s"CancellableFuture.failed($ex)"
  }

  /** Creates an already cancelled `CancellableFuture[T]`.
    */
  def cancelled[T](): CancellableFuture[T] = failed(CancelException)

  /** Creates a new `CancellableFuture[TraversableOnce[T]]` from a TraversableOnce of `CancellableFuture[T]`.
    * The original futures are executed asynchronously, but checking their results will be done in sequence.
    * I.e., if the sequence is (A, B) where A takes a long time to complete, and B completes faster,
    * the new cancellable future will anyway check the result of B only after A is completed.
    * In practice, it means that if any of the original cancellable futures fails or is cancelled, the resulting
    * one will first wait for all those before it and fail by itself only after reaching the failed one.
    *
    * @todo Cancelling the resulting future will NOT cancel the original ones. (FIX IT)
    */
  def sequence[T](in: TraversableOnce[CancellableFuture[T]])
                 (implicit executor: ExecutionContext): CancellableFuture[TraversableOnce[T]] =
    sequencePriv[T, TraversableOnce[T]](in)

  private final def sequencePriv[T, U](in: TraversableOnce[CancellableFuture[T]])
                                     (implicit executor: ExecutionContext, cbf: CanBuild[T, U]): CancellableFuture[U] =
    in.foldLeft(successful(cbf())) {
      (fr, fa) => for (r <- fr; a <- fa) yield r += a
    } map (_.result())

  /** Transforms a `TraversableOnce[T]` into a `CancellableFuture[TraversableOnce[U]]` using
    * the provided function `T => CancellableFuture[U]`. Each cancellable future will be executed
    * asynchronously. If any of the original cancellable futures fails or is cancelled, the resulting
    * one will first wait for all those before it and fail by itself only after reaching the failed one.
    *
    * @todo Cancelling the resulting future will NOT cancel the original ones. (FIX IT)
    */
  def traverse[T, U](in: TraversableOnce[T])(f: T => CancellableFuture[U])
                    (implicit executor: ExecutionContext): CancellableFuture[TraversableOnce[U]] =
    sequence(in.map(f))

  /** Transforms a `TraversableOnce[T]` into a `CancellableFuture[TraversableOnce[U]]` using
    * the provided function `T => CancellableFuture[U]`. Each cancellable future will be executed
    * synchronously. If any of the original cancellable futures fails or is cancelled, the resulting one
    * also fails and no consecutive original futures will be executed.
    *
    * @todo Cancelling the resulting future prevents all the consecutive original futures from being executed
    *       but the ongoing one is not cancelled. (FIX IT)
    */
  def traverseSequential[T, U](in: Traversable[T])(f: T => CancellableFuture[U])
                              (implicit executor: ExecutionContext): CancellableFuture[Traversable[U]] = {
    def processNext(remaining: Traversable[T], acc: List[U] = Nil): CancellableFuture[Traversable[U]] =
      if (remaining.isEmpty) CancellableFuture.successful(acc.reverse)
      else f(remaining.head).flatMap(res => processNext(remaining.tail, res :: acc))

    processNext(in)
  }

  /** Creates a new `CancellableFuture[(T, U)]` from two original cancellable futures, one of the type `T`,
    * and one of the type `U`. The new future will fail or be cancelled if any of the original ones fails.
    * Cancelling the new future will fail both original ones.
    */
  def zip[T, U](f1: CancellableFuture[T], f2: CancellableFuture[U])
               (implicit executor: ExecutionContext): CancellableFuture[(T, U)] = {
    val p = Promise[(T, U)]()

    p.tryCompleteWith((for (r1 <- f1; r2 <- f2) yield (r1, r2)).future)

    new CancellableFuture(p) {
      override def cancel(): Boolean =
        if (super.cancel()) {
          Future {
            f1.cancel()
            f2.cancel()
          }(executor)
          true
        } else false
    }
  }
}
