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

import scala.ref.WeakReference

object Subscription {
  /** A function type of any function which can consume events.
    *
    * @tparam E The type of the event, emitted by an [[EventSource]] and consumed by the subscriber.
    */
  type Subscriber[-E] = E => Unit
}

/** When you add a new listener to your [[EventStream]] or [[Signal]], in return you get a [[Subscription]].
  * A subscription can then be used to inform the source about changes in the condition of the connection:
  * should it be enabled or disabled, should the lsitener be subscribed or (temporarily) unsubscribed,
  * or should the subscription be permanently destroyed.
  *
  * It is important to destroy subscriptions when they are no longer needed, e.g. at the end of the life
  * of an object which listens to the source of events. Otherwise you may face a hidden memory leak where
  * no longer used data cannot be GC-ed because it is still referenced by the source of events.
  *
  * @see [[EventContext]]
  *
  * Implement this trait together with writing a new event source if you want to change how your event source
  * reacts to the aforementioned events. For an example of how to do it on a small scale, please
  * @see [[CancellableFuture.withAutoCanceling]]
  */
trait Subscription {
  /** Should be called automatically when a new listener is added to the event source.
    * Can be called manually if the listener unsubscribed, but is still enabled,
    * and now it wants again to receive events.
    */
  def subscribe(): Unit

  /** Use to stop receiving events but still maintain the possibility to re-subscribe. */
  def unsubscribe(): Unit

  /** Should be called when the subscription is no longer needed. */
  def destroy(): Unit

  /** You can think of `enable()`/`disable()` as of `subscribe()`/`unsubscribe()` on a higher level.
    * In the default implementation, `enable()` is called automatically when the subscription is created,
    * and it calls `subscribe()`. Later, the listener can `unsubscribe()` but the subscription will stay enabled,
    * whereas if the listener calls `disable()`, it will in turn call `unsubscribe()`, but a simple re-subscribing
    * will not work - instead, the listener will have to work `enable()` again.
    *
    * In practice, `enable()`/`disable()` make sense if you work with custom [[EventContext]].
    * Otherwise you can use `subscribe()`/`unsubscribe()` instead.
    *
    * @see [[EventContext]]
    */
  def enable(): Unit

  /** You can think of `enable()`/`disable()` as of `subscribe()`/`unsubscribe()` on a higher level.
    * In the default implementation, `enable()` is called automatically when the subscription is created,
    * and it calls `subscribe()`. Later, the listener can `disable()` the subscription, which will call `unsubscribe()`.
    * If the listener simply calls `unsubscribe()` without disabling, nothing will happen.
    *
    * In practice, usually you should use `enable()`/`disable()` for temporarily stopping and restarting listening events,
    * and use `subscribe()`/`unsubscribe()` only in custom event contexts.
    *
    * @see [[EventContext]]
    */
  def disable(): Unit

  /** In the default implementation, you can call this to prevent the listener from unsubscribing.
    * The subscription will stay subscribed until destroyed.
    */
  def disablePauseWithContext(): Unit
}

/** Provides the default implementation of the [[Subscription]] trait.
  * Exposes two new abstract methods: `onSubscribe` and `onUnsubscribe`. A typical way to implement them is
  * to have a reference to the source of events which implements the [[Observable]] trait and call `subscribe(this)`
  * on that source (where `this` is the subscription).
  *
  * For examples:
  * @see [[Signal.SignalSubscription]]
  * @see [[EventStream.EventStreamSubscription]]
  *
  * @param context A weak reference to the event context within which the subscription lives.
  */
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
