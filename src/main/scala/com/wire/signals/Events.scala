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

import com.wire.signals.utils.returning

import scala.concurrent.{ExecutionContext, Future}
import scala.ref.WeakReference
import scala.util.Try

object Events {
  type Subscriber[-E] = E => Unit

  def removeObserver[T <: AnyRef](xs: Vector[T], x: T): Vector[T] = {
    val (pre, post) = xs.span(_ ne x)
    if (post.isEmpty) pre else pre ++ post.tail
  }
}

trait Subscription {
  def enable(): Unit

  def disable(): Unit

  def destroy(): Unit

  def disablePauseWithContext(): Unit

  def subscribe(): Unit

  def unsubscribe(): Unit
}

trait EventSource[E] {
  val executionContext = Option.empty[ExecutionContext]

  def on(ec: ExecutionContext)(subscriber: Events.Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription

  def apply(subscriber: Events.Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription
}

trait ForcedEventSource[E] extends EventSource[E] {
  abstract override def on(ec: ExecutionContext)(subscriber: Events.Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.on(ec)(subscriber))(_.disablePauseWithContext())

  abstract override def apply(subscriber: Events.Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.apply(subscriber))(_.disablePauseWithContext())
}

abstract class BaseSubscription(context: WeakReference[EventContext]) extends Subscription {
  @volatile protected[signals] var subscribed = false
  private var enabled = false
  private var pauseWithContext = true

  context.get.foreach(_.register(this))

  protected[signals] def onSubscribe(): Unit

  protected[signals] def onUnsubscribe(): Unit

  def subscribe(): Unit = if (enabled && !subscribed) {
    subscribed = true
    onSubscribe()
  }

  def unsubscribe(): Unit = if (subscribed && (pauseWithContext || !enabled)) {
    subscribed = false
    onUnsubscribe()
  }

  def enable(): Unit = context.get.foreach { context =>
    enabled = true
    if (context.isContextStarted) subscribe()
  }

  def disable(): Unit = {
    enabled = false
    if (subscribed) unsubscribe()
  }

  def destroy(): Unit = {
    disable()
    context.get.foreach(_.unregister(this))
  }

  def disablePauseWithContext(): Unit = {
    pauseWithContext = false
    subscribe()
  }
}

final class SignalSubscription[E](source: Signal[E],
                                 subscriber: Events.Subscriber[E],
                                 executionContext: Option[ExecutionContext] = None
                                )(implicit context: WeakReference[EventContext])
  extends BaseSubscription(context) with SignalListener {

  override def changed(currentContext: Option[ExecutionContext]): Unit = synchronized {
    source.value.foreach { event =>
      if (subscribed)
        executionContext match {
          case Some(ec) if !currentContext.orElse(source.executionContext).contains(ec) =>
            Future(if (subscribed) Try(subscriber(event)))(ec)
          case _ =>
            subscriber(event)
        }
    }
  }

  override protected[signals] def onSubscribe(): Unit = {
    source.subscribe(this)
    changed(None) // refresh listener with current value
  }

  override protected[signals] def onUnsubscribe(): Unit = source.unsubscribe(this)
}

final class StreamSubscription[E](source: EventStream[E],
                                 subscriber: Events.Subscriber[E],
                                 executionContext: Option[ExecutionContext] = None
                                )(implicit context: WeakReference[EventContext])
  extends BaseSubscription(context) with EventListener[E] {

  override def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
    if (subscribed)
      executionContext match {
        case Some(ec) if !currentContext.orElse(source.executionContext).contains(ec) =>
          Future(if (subscribed) Try(subscriber(event)))(ec)
        case _ =>
          subscriber(event)
      }

  override protected[signals] def onSubscribe(): Unit = source.subscribe(this)

  override protected[signals] def onUnsubscribe(): Unit = source.unsubscribe(this)
}
