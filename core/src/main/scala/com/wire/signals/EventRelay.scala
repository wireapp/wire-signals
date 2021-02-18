package com.wire.signals

import com.wire.signals.Subscription.Subscriber

import scala.concurrent.ExecutionContext

trait EventRelay[E, S] {
  private object subscribersMonitor

  private[this] var autowiring = true
  @volatile private[this] var subscribers = Set.empty[S]

  /** This method will be called on creating the first subscription or on `disableAutoWiring`.
    * If the implementing class stashes intermediate computation, this should trigger their execution.
    */
  protected def onWire(): Unit

  /** This method will be called on removing the last subscription if `disableAutoWiring` was not called.
    */
  protected def onUnwire(): Unit

  /** Creates a [[Subscription]] to a [[Subscription.Subscriber]] which will consume events in the given `ExecutionContext`.
    * In simpler terms: A subscriber is a function which will receive events from the event source. For every event,
    * the function will be executed in the given execution context - not necessarily the same as the one used for
    * emitting the event. This allows for easy communication between parts of the program working in different
    * execution contexts, e.g. the user interface and the database.
    *
    * The [[Subscription]] will be automatically enabled ([[Subscription.enable]]).
    *
    * @param ec An `ExecutionContext` in which the [[Subscription.Subscriber]] function will be executed.
    * @param subscriber [[Subscription.Subscriber]] - a function which consumes the event
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the event source and the [[Subscription.Subscriber]]
    */
  def on(ec: ExecutionContext)(subscriber: Subscriber[E])(implicit eventContext: EventContext = EventContext.Global): Subscription


  /** Creates a [[Subscription]] to a [[Subscription.Subscriber]] which will consume events in the same `ExecutionContext` as
    * the one in which the events are being emitted.
    *
    * @see [[EventRelay.on]]
    *
    * The [[Subscription]] will be automatically enabled ([[Subscription.enable]]).
    *
    * @param subscriber [[Subscription.Subscriber]] - a function which consumes the event
    * @param eventContext an [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return a [[Subscription]] representing the created connection between the [[EventRelay]] and the [[Subscription.Subscriber]]
    */
  def onCurrent(subscriber: Subscriber[E])(implicit eventContext: EventContext = EventContext.Global): Subscription

  /** An alias for the `on` method with the default [[scala.concurrent.ExecutionContext]]. */
  final def foreach(op: Subscriber[E])
                   (implicit executionContext: ExecutionContext = Threading.defaultContext,
                    eventContext:     EventContext     = EventContext.Global
                   ): Subscription =
    on(executionContext)(op)(eventContext)

  /** Adds a new subscriber instance. The implementing class should handle notifying this subscriber
    * when a new event arrives. If this is the first subscriber, and `disableAutowiring` wasn't called previous,
    * this will trigger a call to `onWire`.
    *
    * @param subscriber An instance of a subscriber class, known to the class implementing this `EventRelay`
    */
  def subscribe(subscriber: S): Unit = subscribersMonitor.synchronized {
    val wiredAlready = wired
    subscribers += subscriber
    if (!wiredAlready) onWire()
  }

  /** Removes a previously registered subscriber instance.
    * If this is the last subscriber, and `disableAutowiring` wasn't called previously, this will trigger a call to `onUnwire`.
    *
    * @param subscriber An instance of a subscriber class, known to the class implementing this `EventRelay`
    */
  def unsubscribe(subscriber: S): Unit = subscribersMonitor.synchronized {
    subscribers -= subscriber
    if (autowiring && !hasSubscribers) onUnwire()
  }

  /** The class which implements this `EventRelay` can use this method to notify all the subscribers that a new event
    * arrived.
    *
    * @param call A function that will perform some action on each subscriber
    */
  protected def notifySubscribers(call: S => Unit): Unit = subscribers.foreach(call)

  /** Checks if there are any subscribers registered in this `EventRelay`.
    *
    * @return true if any subscribers are registered, false otherwise
    */
  @inline final def hasSubscribers: Boolean = subscribers.nonEmpty

  /** Empties the set of subscribers and calls `unWire` if `disableAutowiring` wasn't called before.
    */
  def unsubscribeAll(): Unit = subscribersMonitor.synchronized {
    subscribers = Set.empty
    if (autowiring) onUnwire()
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
    if (subscribers.isEmpty) onWire()
    this
  }

  @inline final def wired: Boolean = hasSubscribers || !autowiring
}


/** [[Subscription]]s created for a [[ForcedEventRelay]] cannot be unsubscribed.
  * They will stay subscribed until destroyed.
  *
  * You can use it as a tag when creating a new event source, e.g.
  * {{{
  *   val eventStream = new SourceStream[Boolean]
  *   val subscription = eventStream { b => /* ... */ }
  *   subscription.unsubscribe()
  * }}}
  * here [[Subscription.unsubscribe]] will temporarily unsubscribe the subscription
  * (i.e. the [[Subscription.Subscriber]] will stop receiving events until the consecutive call to [[Subscription.subscribe]])
  * but
  * {{{
  *   val eventStream = new SourceStream[Boolean] with ForcedEventRelay[Boolean, SignalSubscriber]
  *   val subscription = eventStream { b => /* ... */ }
  *   subscription.unsubscribe()
  * }}}
  * here [[Subscription.unsubscribe]] will do nothing.
  *
  * @tparam E The type of events emitted by the event source.
  */

trait ForcedEventRelay[E, S] extends EventRelay[E, S] {
  abstract override def on(ec: ExecutionContext)(subscriber: Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.on(ec)(subscriber))(_.disablePauseWithContext())

  abstract override def onCurrent(subscriber: Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.onCurrent(subscriber))(_.disablePauseWithContext())
}
