package com.wire.signals

import com.wire.signals.Subscription.Subscriber
import com.wire.signals.utils.returning

import scala.concurrent.ExecutionContext

/** A source of events of the type `E` which [[Subscription.Subscriber]]s can attach themselves too,
  * creating [[Subscription]]s in result.
  *
  * @tparam E The type of events emitted by the event source.
  */
trait EventSource[E] {
  protected[signals] val executionContext = Option.empty[ExecutionContext]

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
    * @param eventContext an [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return a [[Subscription]] representing the created connection between the [[EventSource]] and the [[Subscription.Subscriber]]
    */
  def on(ec: ExecutionContext)(subscriber: Subscriber[E])(implicit eventContext: EventContext = EventContext.Global): Subscription

  /** Creates a [[Subscription]] to a [[Subscription.Subscriber]] which will consume events in the same `ExecutionContext` as
    * the one in which the events are being emitted.
    *
    * @see [[EventSource.on]]
    *
    * The [[Subscription]] will be automatically enabled ([[Subscription.enable]]).
    *
    * @param subscriber [[Subscription.Subscriber]] - a function which consumes the event
    * @param eventContext an [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return a [[Subscription]] representing the created connection between the [[EventSource]] and the [[Subscription.Subscriber]]
    */
  def apply(subscriber: Subscriber[E])(implicit eventContext: EventContext = EventContext.Global): Subscription
}

/** [[Subscription]]s created for a [[ForcedEventSource]] cannot be unsubscribed.
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
  *   val eventStream = new SourceStream[Boolean] with ForcedEventSource[Boolean]
  *   val subscription = eventStream { b => /* ... */ }
  *   subscription.unsubscribe()
  * }}}
  * here [[Subscription.unsubscribe]] will do nothing.
  *
  * @tparam E The type of events emitted by the event source.
  */
trait ForcedEventSource[E] extends EventSource[E] {
  abstract override def on(ec: ExecutionContext)(subscriber: Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.on(ec)(subscriber))(_.disablePauseWithContext())

  abstract override def apply(subscriber: Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.apply(subscriber))(_.disablePauseWithContext())
}
