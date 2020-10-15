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

/** `Subscribable` is a trait implemented by event streams and signals to define how should they react to that
  * a subscription to them is created.
  **
  * I think the easiest way to explain how `Subscribable` works will be with an example.
  * Let's say we create an event stream of ints:
  *
  * `val stream = EventStream[Int]`
  *
  * This line will create a `SourceStream[Int]` which is a subclass of `EventStream[Int]` which in turn implements
  * `EventSource[Int]` and `Subscribable[EventSubscriber[Int]]`. The `Subscribable` trait can have any type as its
  * parameter, the only restriction being that the class which implements `Subscribable` should know how to notify
  * "source subscribers" of this type when a new event comes (`Subscribable` doesn't care what method will be used
  * to do it). In this case, the "source subscriber" is [[EventSubscriber]].
  *
  * Please not that [[SourceSubscriber]] is a trait or a class type. This is different from another generic type called
  * [[Subscription.Subscriber]] which is a function type used to create a [[Subscription]]. We will get to it in a second.
  *
  * So far, there are no event subscribers yet, which means that if we publish anything to this stream, nothing will happen.
  * What we need is to subscribe to it with, for example:
  *
  * `stream { number => ... }`
  *
  * A few things happen here. `{ number => ... }` is a function of the type `Int => Unit`, a.k.a `Subscription.Subscriber[Int]`.
  * It is used to create a subscription to the stream, together with two other arguments, which here get default values:
  * 1. [[EventContext]] - here the global event context is used.
  * 2. An option of [[scala.concurrent.ExecutionContext]] - here `None` is used, indicating that the subscriber function
  *    will always be executed in the execution context in which the event was published (no execution context switching).
  * If we wanted to specify the execution context, not leave out the event context, and not ignore the result subscription,
  * that line would look like this:
  * `val sub: Subscription = stream.on(executionContext){ number => ... }(EventContext.Global)`
  * Just after the subscription is created by the `apply` or `on` method, it is being enabled. And if the specified event
  * context is started (the global event context always is), this in turn calls `Subscription.subscribe` in the subscription
  * subclass instance created by the event stream. And that in turn calls `Subscribable.subscribe` for the event stream.
  *
  * Then, since this is the first subscription created, `Subscribable.subscribe` will call `EventStream.onWire`.
  *
  * The number of jumps between methods is quite big and it raises the question if we actually need all this flexibility.
  * If not, I hope this description will help us in simplifying this chain of function calls.
  */
trait Subscribable[SourceSubscriber] {
  private object subscribersMonitor

  private[this] var autowiring = true
  @volatile private[signals] var wired = false
  @volatile private[this] var subscribers = Set.empty[SourceSubscriber]

  /** This method will be called on creating the first subscription or on `disableAutoWiring`.
    * If the implementing class stashes intermediate computation, this should trigger their execution.
    */
  protected def onWire(): Unit

  /** This method will be called on removing the last subscription if `disableAutoWiring` was not called.
    */
  protected def onUnwire(): Unit

  /** Adds a new subscriber instance. The implementing class should handle notifying this subscriber
    * when a new event arrives. If this is the first subscriber, and `disableAutowiring` wasn't called previous,
    * this will trigger a call to `onWire`.
    *
    * @param subscriber An instance of a subscriber class, known to the class implementing this `Subscribable`
    */
  def subscribe(subscriber: SourceSubscriber): Unit = subscribersMonitor.synchronized {
    subscribers += subscriber
    if (!wired) {
      wired = true
      onWire()
    }
  }

  /** Removes a previously registered subscriber instance.
    * If this is the last subscriber, and `disableAutowiring` wasn't called preeviously, this will trigger a call to `onUnwire`.
    *
    * @param subscriber An instance of a subscriber class, known to the class implementing this `Subscribable`
    */
  def unsubscribe(subscriber: SourceSubscriber): Unit = subscribersMonitor.synchronized {
    subscribers -= subscriber
    if (wired && autowiring && subscribers.isEmpty) {
      wired = false
      onUnwire()
    }
  }

  /** The class which implements this `Subscribable` can use this method to notify all the subscribers that a new event
    * arrived.
    *
    * @param call A function that will perform some action on each subscriber
    */
  protected def notifySubscribers(call: SourceSubscriber => Unit): Unit = subscribers.foreach(call)

  /** Checks if there are any subscribers registered in this `Subscribable`.
    *
    * @return true if any subscribers are registered, false otherwise
    */
  def hasSubscribers: Boolean = subscribers.nonEmpty

  /** Empties the set of subscribers and calls `unWire` if `disableAutowiring` wasn't called before.
    */
  def unsubscribeAll(): Unit = subscribersMonitor.synchronized {
    subscribers = Set.empty
    if (wired && autowiring) {
      wired = false
      onUnwire()
    }
  }

  /** Typically, a newly created event streams and signals are lazy in the sense that till there are no subscriptions to them,
    * they will not execute any intermediate computations (e.g. assembled to it through maps, flatMaps, etc). After all,
    * those computations would be ignored at the end. Only when a subscription is created, the computations are performed
    * for the first time.
    * `disableAutowiring` enforces those computations even if there are no subscribers. It can be useful if e.g. the computations
    * perform side-effects or if it's important from the performance point of view to have the intermediate results ready
    * when the subscriber is created.
    *
    * @return The current instance, so that `disableAutoworing` can be chained with other method calls.
    */
  def disableAutowiring(): this.type = subscribersMonitor.synchronized {
    autowiring = false
    if (!wired) {
      wired = true
      onWire()
    }
    this
  }
}
