package com.wire.signals

import com.wire.signals.utils.returning

import scala.concurrent.ExecutionContext

final private[signals] class FlatMapLatestEventStream[E, V](source: EventStream[E], f: E => EventStream[V])
  extends EventStream[V] with EventSubscriber[E] {
  @volatile private var mapped: Option[EventStream[V]] = None

  private val subscriber = new EventSubscriber[V] {
    override protected[signals] def onEvent(event: V, currentContext: Option[ExecutionContext]): Unit =
      dispatch(event, currentContext)
  }

  override protected[signals] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit = {
    mapped.foreach(_.unsubscribe(subscriber))
    mapped = Some(returning(f(event))(_.subscribe(subscriber)))
  }

  override protected def onWire(): Unit = source.subscribe(this)

  override protected def onUnwire(): Unit = {
    mapped.foreach(_.unsubscribe(subscriber))
    mapped = None
    source.unsubscribe(this)
  }
}
