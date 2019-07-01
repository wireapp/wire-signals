package com.wire.signals

import scala.collection.mutable
import scala.concurrent.Future

object Serialized {
  private implicit val dispatcher: SerialDispatchQueue = new SerialDispatchQueue(name = "Serializing")

  private val locks = new mutable.HashMap[Any, Future[_]]

  def apply[A](key: Any*)(body: => CancellableFuture[A]): CancellableFuture[A] = dispatcher {
    val future = locks.get(key).fold(body) { lock =>
      CancellableFuture.lift(lock.recover { case _ => }) flatMap (_ => body)
    }
    val lock = future.future
    locks += (key -> lock)
    future.onComplete { _ => if (locks.get(key).contains(lock)) locks -= key }
    future
  }.flatten

  def future[A](key: Any*)(body: => Future[A]): Future[A] = Future {
    val future = locks.get(key).fold(body) { lock =>
      lock.recover { case _ => }.flatMap(_ => body)
    }
    locks += (key -> future)
    future.onComplete { _ => if (locks.get(key).contains(future)) locks -= key }
    future
  }.flatten
}
