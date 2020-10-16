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

object EventContext {
  /** Creates a new default implementation of an [[EventContext]]
    *
    * @return A default implementation of the [[EventContext]]
    */
  def apply(): EventContext = new BaseEventContext

  object Implicits {
    implicit val global: EventContext = EventContext.Global
  }

  /** A dummy global [[EventContext]] used when no other event context is specified.
    * It does not maintain its subscriptions, it's always started, it can't be stopped or destroyed,
    * and it lives for the lifetime of the program.
    */
  final object Global extends EventContext {
    override def register(subscription: Subscription): Boolean = true
    override def unregister(subscription: Subscription): Unit = {}
    override def start(): Unit = {}
    override def stop(): Unit = {}
    override def destroy(): Unit = {}
    override def isContextStarted: Boolean = true
    override def isContextDestroyed: Boolean = false
  }
}

/** When you subscribe to an [[EventSource]] in return you receive a [[Subscription]]. You can use that subscription
  * to unsubscribe from the event source or to temporarily pause receiving events. But managing a big number of
  * subscriptions to different event sources can be tricky. [[EventContext]] comes to the rescue.
  *
  * By default, every subscription is registered in a "dummy" [[EventContext.Global]] which lives for the lifetime
  * of the whole program and does nothing. But if instead you will create a new EventContext and use it explicitly
  * when subscribing or you will set it as an implicit parameter, taking over EventContext.Global, the subscription
  * will be registered within this new one. It will allow you to manage all registered subscriptions at once and
  * all registered subscriptions will be destroyed when the event context lifetime ends.
  *
  * Usage of methods in the trait are explained as they are implemented in the default implementation.
  * All operations on an [[EventContext]] are synchronized.
  *
  * @see [[EventSource]]
  */
trait EventContext {
  /** An[[EventContext]] has to be started before it can register subscriptions.
    * A newly created one is started by default.
    * If the event context maintains subscriptions, they will be re-subscribed.
    */
  def start(): Unit

  /** Unsubscribes all subscriptions and prevents registering new ones before the event context is started again.
    * The subscriptions are not destroyed and will be re-subscribed when the consecutive `start()` is called.
    */
  def stop(): Unit

  /** Destroys all subscriptions. A destroyed [[EventContext]] cannot be used again.
    * This method is called automatically when the event context lifetime ends.
    */
  def destroy(): Unit

  /** Registers a new [[Subscription]] within the [[EventContext]] if the event context is not destroyed.
    * (But it does not have to be started). If the event context is started, the new subscription will be
    * automatically subscribed. If not, it will be subscribed on the consecutive call to `start()`.
    *
    * @param subscription The subscription to be registered
    * @return true if the subscription is registered, false otherwise
    *         (e.g. if the event context is destroyed or the subscription is already registered)
    */
  def register(subscription: Subscription): Boolean

  /** Unregisters an already registered [[Subscription]]. The subscription is not unsubscribed or destroyed.
    * Does nothing if the [[EventContext]] does not contain the given subscription.
    *
    * @param subscription The subscription to be unregistered
    */
  def unregister(subscription: Subscription): Unit

  def isContextStarted: Boolean
  def isContextDestroyed: Boolean
}

class BaseEventContext extends EventContext {
  private object lock

  private[this] var started = true
  private[this] var destroyed = false
  private[this] var subscriptions = Set.empty[Subscription]

  override protected def finalize(): Unit = {
    lock.synchronized {
      if (!destroyed) destroy()
    }
    super.finalize()
  }

  override def start(): Unit = lock.synchronized {
    if (!started) {
      started = true
      subscriptions.foreach(_.subscribe())
    }
  }

  override def stop(): Unit = lock.synchronized {
    if (started) {
      started = false
      subscriptions.foreach(_.unsubscribe())
    }
  }

  override def destroy(): Unit = lock.synchronized {
    destroyed = true
    val subscriptionsToDestroy = subscriptions
    subscriptions = Set.empty
    subscriptionsToDestroy.foreach(_.destroy())
  }

  override def register(subscription: Subscription): Boolean = lock.synchronized {
    if (!destroyed && !subscriptions.contains(subscription)) {
      subscriptions += subscription
      if (started) subscription.subscribe()
      true
    } else false
  }

  override def unregister(subscription: Subscription): Unit = lock.synchronized(subscriptions -= subscription)

  override def isContextStarted: Boolean = lock.synchronized(started && !destroyed)

  override def isContextDestroyed: Boolean = lock.synchronized(destroyed)
}
