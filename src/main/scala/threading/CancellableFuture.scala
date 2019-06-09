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
package threading

import java.util.TimerTask

import signals.{BaseSubscription, EventContext}

import scala.collection.generic.CanBuild
import scala.concurrent._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.ref.WeakReference
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

class CancellableFuture[+A](promise: Promise[A]) extends Awaitable[A] { self =>
  import threading.CancellableFuture._
  
  val future = promise.future
  
  def cancel(): Boolean =
    promise.tryFailure(new CancelException(s"cancel")) // TODO: switch to DefaultCancelFuture for performance (once this is stable)

  def fail(ex: Exception): Boolean =
    promise.tryFailure(ex)

  def onComplete[B](f: Try[A] => B)(implicit executor: ExecutionContext): Unit =
    future.onComplete(f)

  def onSuccess[U](pf: PartialFunction[A, U])(implicit executor: ExecutionContext): Unit =
    future.onSuccess(pf)

  def onFailure[U](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit =
    future.onFailure(pf)

  def onCancelled(body: => Unit)(implicit executor: ExecutionContext): Unit =
    future.onFailure {
      case _: CancelException => body
    }

  def map[B](f: A => B)(implicit executor: ExecutionContext): CancellableFuture[B] = {
    val p = Promise[B]()
    @volatile var cancelFunc = Option(() => self.cancel())
    future.onComplete { v =>
      cancelFunc = None
      p.tryComplete(v.flatMap(res => Try(f(res))))
    }
    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          Future(cancelFunc.foreach(_()))(CancellableFuture.internalExecutionContext)
          true
        } else false
      }
    }
  }

  def filter(f: (A) => Boolean)(implicit executor: ExecutionContext): CancellableFuture[A] =
    flatMap { res =>
      if (f(res)) CancellableFuture.successful(res)
      else CancellableFuture.failed(new NoSuchElementException(s"CancellableFuture.filter failed"))
    }

  final def withFilter(p: A => Boolean)(implicit executor: ExecutionContext): CancellableFuture[A] =
    filter(p)(executor)

  def flatMap[B](f: A => CancellableFuture[B])(implicit executor: ExecutionContext): CancellableFuture[B] = {
    val p = Promise[B]()
    @volatile var cancelFunc = Option(() => self.cancel())

    self.future onComplete { res =>
      cancelFunc = None
      if (!p.isCompleted) res match {
        case f: Failure[_] => p tryComplete f.asInstanceOf[Failure[B]]
        case Success(v) =>
          Try(f(v)) match {
            case Success(fut) =>
              cancelFunc = Option(() => fut.cancel())
              fut onComplete { res =>
                cancelFunc = None
                p tryComplete res
              }
              if (p.isCompleted) fut.cancel()
            case Failure(t) =>
              p tryFailure t
          }
      }
    }

    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          Future(cancelFunc.foreach(_()))(CancellableFuture.internalExecutionContext)
          true
        } else false
      }
    }
  }

  def recover[U >: A](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext) = recoverWith(pf.andThen(CancellableFuture.successful))

  def recoverWith[U >: A](pf: PartialFunction[Throwable, CancellableFuture[U]])(implicit executor: ExecutionContext): CancellableFuture[U] = {
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
            p tryComplete res
          }
          if (p.isCompleted) fut.cancel()
        case other =>
          p tryComplete other
      }
    }
    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          Future(cancelFunc.foreach(_()))(CancellableFuture.internalExecutionContext)
          true
        } else false
      }
    }
  }

  def flatten[B](implicit executor: ExecutionContext, evidence: A <:< CancellableFuture[B]): CancellableFuture[B] =
    flatMap(x => x)

  def zip[B](other: CancellableFuture[B])(implicit executor: ExecutionContext): CancellableFuture[(A, B)] =
    CancellableFuture.zip(self, other)

  @throws[InterruptedException](classOf[InterruptedException])
  @throws[TimeoutException](classOf[TimeoutException])
  override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
    future.ready(atMost)
    this
  }

  @throws[Exception](classOf[Exception])
  override def result(atMost: Duration)(implicit permit: CanAwait): A = future.result(atMost)

  // TODO: timeout should generate different exception
  def withTimeout(timeout: FiniteDuration): CancellableFuture[A] = {
    implicit val ec = CancellableFuture.internalExecutionContext
    val f = CancellableFuture.delayed(timeout)(this.fail(new TimeoutException(s"timedOut($timeout)")))
    onComplete(_ => f.cancel())
    this
  }

  //TODO: think on this possibility
  def withAutoCanceling(implicit eventContext: EventContext): Unit = {
    eventContext.register(new BaseSubscription(WeakReference(eventContext)) {
      override def onUnsubscribe(): Unit = {
        cancel()
        eventContext.unregister(this)
      }
      override def onSubscribe(): Unit = {} // should not happen
    })
  }

}

object CancellableFuture {
  private[threading] def internalExecutionContext: ExecutionContext = Threading().mainThread

  import language.implicitConversions
  implicit def to_future[A](f: CancellableFuture[A]): Future[A] = f.future

  class CancelException(msg: String) extends Exception(msg) with NoStackTrace

  case object DefaultCancelException extends CancelException("Operation cancelled")

  class PromiseCompletingRunnable[T](body: => T) extends Runnable {
    val promise = Promise[T]()

    override def run() =
      if (!promise.isCompleted) promise.tryComplete(Try(body))
  }
  
  def apply[A](body: => A)(implicit executor: ExecutionContext): CancellableFuture[A] = {
    val runnable = new PromiseCompletingRunnable[A](body)
    executor.execute(DispatchQueueStats(s"CancellableFuture", runnable))
    new CancellableFuture(runnable.promise)
  }

  def lift[A](future: Future[A], onCancel: => Unit = ()): CancellableFuture[A] = {
    val p = Promise[A]()
    p.tryCompleteWith(future)
    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          onCancel
          true
        } else false
      }
    }
  }

  def delay(d: FiniteDuration): CancellableFuture[Unit] = {
    if (d <= Duration.Zero) successful(())
    else {
      val p = Promise[Unit]()
      val task = Threading()schedule(() => p.trySuccess(()), d.toMillis)
      new CancellableFuture(p) {
        override def cancel(): Boolean = {
          task.cancel()
          super.cancel()
        }
      }
    }
  }

  def timeout(duration: Duration,
              interrupter: Option[CancellableFuture[_]] = None,
              shouldLoop: () => Boolean = () => false)
             (implicit ec: ExecutionContext): CancellableFuture[Unit] = {
    if (duration <= Duration.Zero || !interrupter.flatMap(_.future.value).forall(_.isSuccess)) {
      successful(())
    } else {
      val promise = Promise[Unit]()
      new CancellableFuture(promise) {
        interrupter.foreach(_.onComplete {
          case Success(_) =>
            doCleanup()
            promise.success(())
          case _ => //treat it like we should not interrupt
        })

        @volatile
        private var currentTask: TimerTask = _
        startNewTimeoutLoop()

        private def startNewTimeoutLoop(): Unit = {
          currentTask = Threading().schedule(() => {
            if (shouldLoop()) startNewTimeoutLoop()
            else {
              doCleanup()
              promise.success(())
            }
          }, duration.toMillis)
        }

        private def doCleanup(): Unit = {
          interrupter.foreach(_.cancel()) //do not forget to cancel interrupter
          if (currentTask != null) currentTask.cancel() //do not forget to to cancel scheduled task
        }

        override def cancel(): Boolean = {
          doCleanup()
          super.cancel()
        }
      }
    }
  }

  def delayed[A](d: FiniteDuration)(body: => A)(implicit executor: ExecutionContext) =
    if (d <= Duration.Zero) CancellableFuture(body)
    else delay(d) map { _ => body }

  def successful[A](res: A): CancellableFuture[A] = new CancellableFuture[A](Promise.successful(res)) {
    override def toString: String = s"CancellableFuture.successful($res)"
  }

  def failed[A](ex: Throwable): CancellableFuture[A] = new CancellableFuture[A](Promise.failed(ex)) {
    override def toString: String = s"CancellableFuture.failed($ex)"
  }

  def cancelled[A](): CancellableFuture[A] = failed(DefaultCancelException)

  def sequence[A](in: Seq[CancellableFuture[A]])(implicit executor: ExecutionContext): CancellableFuture[Seq[A]] = sequenceB[A, Seq[A]](in)

  def sequenceB[A, B](in: Traversable[CancellableFuture[A]])(implicit executor: ExecutionContext, cbf: CanBuild[A, B]): CancellableFuture[B] =
    in.foldLeft(successful(cbf())) {
      (fr, fa) => for (r <- fr; a <- fa) yield r += a
    } map (_.result())

  def traverse[A, B](in: Seq[A])(f: A => CancellableFuture[B])(implicit executor: ExecutionContext): CancellableFuture[Seq[B]] = sequence(in.map(f))
  
  def traverseSequential[A, B](in: Seq[A])(f: A => CancellableFuture[B])(implicit executor: ExecutionContext): CancellableFuture[Seq[B]] = {
    def processNext(remaining: Seq[A], acc: List[B] = Nil): CancellableFuture[Seq[B]] =
      if (remaining.isEmpty) CancellableFuture.successful(acc.reverse)
      else f(remaining.head) flatMap { res => processNext(remaining.tail, res :: acc) }

    processNext(in)
  }

  def zip[A, B](f1: CancellableFuture[A], f2: CancellableFuture[B])(implicit executor: ExecutionContext): CancellableFuture[(A, B)] = {
    val p = Promise[(A, B)]()

    p.tryCompleteWith((for (r1 <- f1; r2 <- f2) yield (r1, r2)).future)

    new CancellableFuture(p) {
      override def cancel(): Boolean = {
        if (super.cancel()) {
          Future {
            f1.cancel()
            f2.cancel()
          }(CancellableFuture.internalExecutionContext)
          true
        } else false
      }
    }
  }
}
