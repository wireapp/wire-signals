package com.wire.signals

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

class RefreshingSignal[A](loader: => CancellableFuture[A], refreshEvent: EventStream[_]) extends Signal[A] {
  private val queue = new SerialDispatchQueue(name = "RefreshingSignal")

  @volatile private var loadFuture = CancellableFuture.cancelled[Unit]()
  @volatile private var subscription = Option.empty[Subscription]

  private def reload(): Unit = subscription.foreach { _ =>
    loadFuture.cancel()
    val p = Promise[Unit]
    val thisReload = CancellableFuture.lift(p.future)
    loadFuture = thisReload
    loader.onComplete {
      case Success(v) if loadFuture eq thisReload =>
        p.success(set(Some(v), Some(Threading.executionContext)))
      case Failure(ex) if loadFuture eq thisReload =>
        //error(l"Error while loading RefreshingSignal", ex)
        p.failure(ex)
      case _ =>
    }(queue)
  }

  override protected def onWire(): Unit = {
    super.onWire()
    Future {
      subscription = Some(refreshEvent.on(queue)(_ => reload())(EventContext.Global))
      reload()
    }(queue)
  }

  override protected def onUnwire(): Unit = {
    super.onUnwire()
    Future {
      subscription.foreach(_.unsubscribe())
      subscription = None
      loadFuture.cancel()
      value = None
    }(queue)
  }
}

object RefreshingSignal {
  def apply[A](loader: => Future[A], refreshEvent: EventStream[_]): RefreshingSignal[A] =
    new RefreshingSignal(CancellableFuture.lift(loader), refreshEvent)
}