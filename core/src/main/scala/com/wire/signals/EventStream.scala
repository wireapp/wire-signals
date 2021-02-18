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

import com.wire.signals.EventStream.{EventStreamSubscription, EventSubscriber}
import com.wire.signals.Signal.SignalSubscriber

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.ref.WeakReference
import scala.util.{Failure, Success, Try}

object EventStream {
  private[signals] trait EventSubscriber[E] {
    // 'currentContext' is the context this method IS run in, NOT the context any subsequent methods SHOULD run in
    protected[signals] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit
  }

  final private class EventStreamSubscription[E](source:            EventStream[E],
                                                 f:                 E => Unit,
                                                 executionContext:  Option[ExecutionContext] = None
                                                )(implicit context: WeakReference[EventContext])
    extends BaseSubscription(context) with EventSubscriber[E] {

    override def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
      if (subscribed)
        executionContext match {
          case Some(ec) if !currentContext.contains(ec) => Future(if (subscribed) Try(f(event)))(ec)
          case _ => f(event)
        }

    override protected[signals] def onSubscribe(): Unit = source.subscribe(this)

    override protected[signals] def onUnsubscribe(): Unit = source.unsubscribe(this)
  }

  /** Creates a new [[SourceStream]] of events of the type `E`. A usual entry point for the event streams network.
    *
    * @tparam E The event type.
    * @return A new event stream of events of the type `E`.
    */
  def apply[E]() = new SourceStream[E]

  /** Creates a new event stream by joining together the original streams of the same type of events, `E`.
    * The resulting stream will emit all events published to any of the original streams.
    *
    * @param streams A variable arguments list of original event streams of the same event type.
    * @tparam E The event type.
    * @return A new event stream of events of type `E`.
    */
  def zip[E](streams: EventStream[E]*): EventStream[E] = new ZipEventStream(streams: _*)

  /** Creates a new event source from a signal of the same type of events.
    * The event source will publish a new event every time the value of the signal changes or its set of subscribers changes.
    *
    * @see [[Signal]]
    * @see [[Signal.onChanged]]
    *
    * @todo The difference between `EventStream.from(signal)` and `signal.onChanged` seems to be only that in the first case
    *       the new event stream will emit an event also when the set of subscribers of the original signal changes, not only
    *       when its value changes to something different. This might be misleading and I don't really see any practical use
    *       of such distinction. Maybe we should remove this method?
    *
    * @param signal The original signal.
    * @tparam E The type of events.
    * @return A new event stream, emitting an event corresponding to the value of the original signal.
    */
  def from[E](signal: Signal[E]): EventStream[E] with SignalSubscriber = new EventStream[E] with SignalSubscriber { stream =>
    override def changed(ec: Option[ExecutionContext]): Unit = stream.synchronized { signal.value.foreach(dispatch(_, ec)) }

    override protected def onWire(): Unit = {
      signal.subscribe(this)
      signal.value.foreach(dispatch(_, None))
    }
    override protected def onUnwire(): Unit = signal.unsubscribe(this)
  }

  /** Creates an event stream from a future. The event stream will emit one event if the future finishes with success, zero otherwise.
    *
    * @param future The [[scala.concurrent.Future]] treated as the source of the only event that can be emitted by the event source.
    * @param executionContext The [[scala.concurrent.ExecutionContext]] in which the event will be dispatched.
    * @tparam E The type of the event.
    * @return A new event stream.
    */
  def from[E](future: Future[E], executionContext: ExecutionContext): EventStream[E] = returning(new EventStream[E]) { stream =>
    future.foreach {
      stream.dispatch(_, Some(executionContext))
    }(executionContext)
  }

  /** A shorthand for creating an event stream from a future in the default execution context.
    *
    * @see [[Threading]]
    *
    * @param future The [[scala.concurrent.Future]] treated as the source of the only event that can be emitted by the event source.
    * @tparam E The type of the event.
    * @return A new event stream.
    */
  def from[E](future: Future[E]): EventStream[E] = from(future, Threading.defaultContext)

  /** A shorthand for creating an event stream from a cancellable future. */
  def from[E](future: CancellableFuture[E], executionContext: ExecutionContext): EventStream[E] = from(future.future, executionContext)


  /** A shorthand for creating an event stream from a cancellable future in the default execution context. */
  def from[E](future: CancellableFuture[E]): EventStream[E] = from(future.future)
}

/** An event stream of type `E` dispatches events (of type `E`) to all functions of type `(E) => Unit` which were registered in
  * the event stream as its subscribers. It doesn't have an internal state. It provides a handful of methods which enable
  * the user to create new event streams by means of composing the old ones, filtering them, etc., in a way similar to how
  * the user can operate on standard collections, as well as to interact with Scala futures, cancellable futures, and signals.
  * Please note that by default an event stream is not able to receive events from the outside - that functionality belongs
  * to [[SourceStream]].
  *
  * An event stream may also help in sending events from one execution context to another. For example, a source stream may
  * receive an event in one execution context, but the function which consumes it is registered with another execution context
  * specified. In that case the function won't be called immediately, but in a future executed in that execution context.
  *
  * @see [[scala.concurrent.ExecutionContext]]
  */
class EventStream[E] protected () extends EventRelay[E, EventSubscriber[E]] {

  /** Dispatches the event to all subscribers.
    *
    * @param event The event to be dispatched.
    * @param executionContext An option of the execution context used for dispatching. The default implementation
    *                         ensures that if `executionContext` is `None` or the same as the execution context used to register
    *                         the subscriber, the subscriber will be called immediately. Otherwise, a future working in the subscriber's
    *                         execution context will be created.
    */
  protected[signals] def dispatch(event: E, executionContext: Option[ExecutionContext]): Unit =
    notifySubscribers(_.onEvent(event, executionContext))

  /** Publishes the event to all subscribers using the current execution context.
    *
    * @param event The event to be published.
    */
  protected def publish(event: E): Unit = dispatch(event, None)

  /** Registers a subscriber in a specified execution context and returns the subscription. An optional event context can also
    * be provided by the user for managing the subscription instead of doing it manually. When an event is published in
    * the event stream, the subscriber function will be called in the given execution context instead of the one of the publisher.
    *
    * @see [[EventRelay]]
    *
    * @param ec An `ExecutionContext` in which the body function will be executed.
    * @param body A function which consumes the event
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the event stream and the body function
    */
  override def on(ec: ExecutionContext)
                 (body: E => Unit)
                 (implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(new EventStreamSubscription[E](this, body, Some(ec))(WeakReference(eventContext)))(_.enable())

  /** Registers a subscriber which will always be called in the same execution context in which the event was published.
    * An optional event context can be provided by the user for managing the subscription instead of doing it manually.
    *
    * @see [[EventRelay]]
    *
    * @param body A function which consumes the event
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the event stream and the body function
    */
  override def onCurrent(body: E => Unit)
                        (implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(new EventStreamSubscription[E](this, body, None)(WeakReference(eventContext)))(_.enable())

  /** Creates a new `EventStream[V]` by mapping events of the type `E` emitted by the original one.
    *
    * @param f The function mapping each event of type `E` into exactly one event of type `V`.
    * @tparam V The type of the resulting event.
    * @return A new event stream of type `V`.
    */
  final def map[V](f: E => V): EventStream[V] = new MapEventStream[E, V](this, f)

  /** Creates a new `EventStream[V]` by mapping each event of the original `EventStream[E]` to a new event stream and
    * switching to it. The usual use case is that the event from the original stream serves to make a decision which
    * next stream we should be listening to. When another event is emitted by the original stream, we may stay listening
    * to that second one, or change our previous decision.
    *
    * @param f The function mapping each event of type `E` to an event stream of type `V`.
    * @tparam V The type of the resulting event stream.
    * @return A new or already existing event stream to which we switch as the result of receiving the original event.
    */
  final def flatMap[V](f: E => EventStream[V]): EventStream[V] = new FlatMapEventStream[E, V](this, f)

  /** Creates a new `EventStream[V]` by mapping events of the type `E` emitted by the original one.
    *
    * @param f A function which for a given event of the type `E` will return a future of the type `V`. If the future finishes
    *          with success, the resulting event of the type `V` will be emitted by the new stream. Two events, coming one
    *          after another, are guaranteed to be mapped in the same order even if the processing for the second event
    *          finishes before the processing for the first one.
    * @tparam V The type of the resulting event.
    * @return A new event stream of type `V`.
    */
  final def mapAsync[V](f: E => Future[V]): EventStream[V] = new FutureEventStream[E, V](this, f)

  /** Creates a new `EventStream[E]` by filtering events emitted by the original one.
    *
    * @param f A filtering function which for each event emitted by the original stream returns true or false. Only events
    *          for which `f(event)` returns true will be emitted in the resulting stream.
    * @return A new event stream emitting only filtered events.
    */
  final def filter(f: E => Boolean): EventStream[E] = new FilterEventStream[E](this, f)

  /** An alias for `filter` used in the for/yield notation.  */
  @inline final def withFilter(f: E => Boolean): EventStream[E] = filter(f)

  /** Creates a new event stream of events of type `V` by applying a partial function which maps the original event of type `E`
    * to an event of type `V`. If the partial function doesn't work for the emitted event, nothing will be emitted in the
    * new event stream. Basically, it's filter + map.
    *
    * @param pf A partial function which for an original event of type `E` may produce an event of type `V`.
    * @tparam V The type of the resulting event.
    * @return A new event stream of type `V`.
    */
  final def collect[V](pf: PartialFunction[E, V]): EventStream[V] = new CollectEventStream[E, V](this, pf)

  /** Creates a new event stream of events of type `V` where each event is a result of applying a function which combines
    * the previous result of type `V` with the original event of type `E` that triggers the emission of the new one.
    *
    * @param zero The initial value of type `V` used to produce the first new event when the first original event comes in.
    * @param f The function which combines the previous result of type `V` with a new original event of type `E` to produce a new result of type `V`.
    * @tparam V The type of the resulting event.
    * @return A new event stream of type `V`.
    */
  final def scan[V](zero: V)(f: (V, E) => V): EventStream[V] = new ScanEventStream[E, V](this, zero, f)

  /** Creates a new event stream by merging the original stream with another one of the same type. The resulting stream
    * will emit events coming from both sources.
    *
    * @param stream The other event stream of the same type of events.
    * @return A new event stream, emitting events from both original streams.
    */
  final def zip(stream: EventStream[E]): EventStream[E] = new ZipEventStream[E](this, stream)

  /** A shorthand for registering a subscriber function in this event stream which only purpose is to publish events emitted
    * by this stream in a given [[SourceStream]]. The subscriber function will be called in the execution context of the
    * original publisher.
    *
    * @see [[SourceStream]]
    *
    * @param sourceStream The source stream in which events emitted by this stream will be published.
    * @param ec An [[EventContext]] which can be used to manage the subscription (optional).
    * @return A new [[Subscription]] to this event stream.
    */
  final def pipeTo(sourceStream: SourceStream[E])(implicit ec: EventContext = EventContext.Global): Subscription = onCurrent(sourceStream ! _)

  /** An alias for `pipeTo`. */
  @inline final def |(sourceStream: SourceStream[E])(implicit ec: EventContext = EventContext.Global): Subscription = pipeTo(sourceStream)

  /** Produces a [[CancellableFuture]] which will finish when the next event is emitted in the parent event stream.
    *
    * @param context Internally, the method creates a subscription to the event stream, and an [[EventContext]] can be provided
    *                to manage it. In practice it's rarely needed. The subscription will be destroyed when the returning
    *                future is finished or cancelled.
    * @return A cancellable future which will finish with the next event emitted by the event stream.
    */
  final def next(implicit context: EventContext = EventContext.Global): CancellableFuture[E] = {
    val p = Promise[E]()
    val o = onCurrent { p.trySuccess }
    p.future.onComplete(_ => o.destroy())(Threading.defaultContext)
    new CancellableFuture(p)
  }

  /** A shorthand for `next` which additionally unwraps the cancellable future */
  @inline final def future(implicit context: EventContext = EventContext.Global): Future[E] = next.future

  /** An alias to the `future` method. */
  @inline final def head: Future[E] = future

  /** Assuming that the event emitted by the stream can be interpreted as a boolean, this method creates a new event stream
    * of type `Unit` which emits unit events for each original event which is interpreted as true.
    *
    * @return A new event stream of units.
    */
  final def ifTrue(implicit ev: E =:= Boolean): EventStream[Unit] = collect { case true => () }

  /** Assuming that the event emitted by the stream can be interpreted as a boolean, this method creates a new event stream
    * of type `Unit` which emits unit events for each original event which is interpreted as false.
    *
    * @return A new event stream of units.
    */
  final def ifFalse(implicit ev: E =:= Boolean): EventStream[Unit] = collect { case false => () }

  /** By default, an event stream does not have the internal state so there's nothing to do in `onWire` and `onUnwire`*/
  override protected def onWire(): Unit = {}

  /** By default, an event stream does not have the internal state so there's nothing to do in `onWire` and `onUnwire`*/
  override protected def onUnwire(): Unit = {}
}

/** A superclass for all event streams which compose other event streams into one.
  *
  * @param sources A variable arguments list of event streams serving as sources of events for the resulting stream.
  * @tparam A The type of the events emitted by all the source streams.
  * @tparam E The type of the events emitted by the stream constructed from the sources.
  */
abstract class ProxyEventStream[A, E](sources: EventStream[A]*) extends EventStream[E] with EventSubscriber[A] {
  /** When the first subscriber is registered in this stream, subscribe the stream to all its sources. */
  override protected[signals] def onWire(): Unit = sources.foreach(_.subscribe(this))

  /** When the last subscriber is unregistered from this stream, unsubscribe the stream from all its sources. */
  override protected[signals] def onUnwire(): Unit = sources.foreach(_.unsubscribe(this))
}

final private[signals] class MapEventStream[E, V](source: EventStream[E], f: E => V)
  extends ProxyEventStream[E, V](source) {
  override protected[signals] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    dispatch(f(event), sourceContext)
}

final private[signals] class FutureEventStream[E, V](source: EventStream[E], f: E => Future[V])
  extends ProxyEventStream[E, V](source) {
  private val key = java.util.UUID.randomUUID()

  override protected[signals] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    Serialized.future(key.toString)(f(event)).andThen {
      case Success(v)                         => dispatch(v, sourceContext)
      case Failure(_: NoSuchElementException) => // do nothing to allow Future.filter/collect
      case Failure(_)                         =>
    }(sourceContext.getOrElse(Threading.defaultContext))
}

final private[signals] class CollectEventStream[E, V](source: EventStream[E], pf: PartialFunction[E, V])
  extends ProxyEventStream[E, V](source) {
  override protected[signals] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    if (pf.isDefinedAt(event)) dispatch(pf(event), sourceContext)
}

final private[signals] class FilterEventStream[E](source: EventStream[E], f: E => Boolean)
  extends ProxyEventStream[E, E](source) {
  override protected[signals] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    if (f(event)) dispatch(event, sourceContext)
}

final private[signals] class ZipEventStream[E](sources: EventStream[E]*)
  extends ProxyEventStream[E, E](sources: _*) {
  override protected[signals] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    dispatch(event, sourceContext)
}

final private[signals] class ScanEventStream[E, V](source: EventStream[E], zero: V, f: (V, E) => V)
  extends ProxyEventStream[E, V](source) {
  @volatile private var value = zero

  override protected[signals] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    value = f(value, event)
    dispatch(value, sourceContext)
  }
}

final private[signals] class FlatMapEventStream[E, V](source: EventStream[E], f: E => EventStream[V])
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
