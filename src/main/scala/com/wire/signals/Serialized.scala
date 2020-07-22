package com.wire.signals

import scala.collection.mutable
import scala.concurrent.Future

object Serialized {
  private implicit val dispatcher: DispatchQueue = SerialDispatchQueue("Serializing")

  private val locks = new mutable.HashMap[String, Future[_]]()

  def apply[A](key: String)(body: => CancellableFuture[A]): CancellableFuture[A] = dispatcher {
    val future = locks.get(key).fold(body) { lock =>
      CancellableFuture.lift(lock.recover { case _ => }).flatMap(_ => body)
    }
    addLock(key, future.future)
    future
  }.flatten

  def future[A](key: String)(body: => Future[A]): Future[A] = {
    val future = locks.get(key).fold(body) { lock =>
      lock.recover { case _ => }.flatMap(_ => body)
    }
    addLock(key, future)
  }

  private def addLock[A](key: String, future: Future[A]): Future[A] = {
    locks += (key -> future)
    future.onComplete { _ => if (locks.get(key).contains(future)) locks -= key }
    future
  }
}
