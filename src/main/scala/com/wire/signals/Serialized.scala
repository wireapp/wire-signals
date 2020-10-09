package com.wire.signals

import scala.collection.mutable
import scala.concurrent.Future

object Serialized {
  private implicit lazy val dispatcher: DispatchQueue = SerialDispatchQueue("Serialized")

  private val locks = mutable.HashMap[String, Future[_]]()

  def apply[A](key: String)(body: => CancellableFuture[A]): CancellableFuture[A] = dispatcher {
    val future = locks.get(key).fold(body) { lock =>
      CancellableFuture.lift(lock.recover { case _ => }) flatMap (_ => body)
    }
    val lock = future.future
    locks += (key -> lock)
    future.onComplete { _ => if (locks.get(key).contains(lock)) locks -= key }
    future
  }.flatten

  def future[A](key: String)(body: => Future[A]): Future[A] = {
    val future = locks.get(key).fold(body) { lock =>
      lock.recover { case _ => }.flatMap(_ => body)
    }
    locks += (key -> future)
    future.onComplete { _ => if (locks.get(key).contains(future)) locks -= key }
    future
  }
}
