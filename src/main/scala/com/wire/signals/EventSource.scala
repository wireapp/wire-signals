package com.wire.signals

import com.wire.signals.Subscription.Subscriber
import com.wire.signals.utils.returning

import scala.concurrent.ExecutionContext

trait EventSource[E] { self =>
  val executionContext = Option.empty[ExecutionContext]

  def on(ec: ExecutionContext)
        (subscriber: Subscriber[E])
        (implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(createSubscription(subscriber, Some(ec), Some(eventContext)))(_.enable())

  def apply(subscriber: Subscriber[E])
           (implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(createSubscription(subscriber, None, Some(eventContext)))(_.enable())

  protected def createSubscription(subscriber: Subscriber[E], executionContext: Option[ExecutionContext], eventContext: Option[EventContext]): Subscription
}

trait ForcedEventSource[E] extends EventSource[E] {
  abstract override def on(ec: ExecutionContext)(subscriber: Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.on(ec)(subscriber))(_.disablePauseWithContext())

  abstract override def apply(subscriber: Subscriber[E])(implicit context: EventContext = EventContext.Global): Subscription =
    returning(super.apply(subscriber))(_.disablePauseWithContext())
}
