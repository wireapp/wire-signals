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

import com.wire.signals.Signal.SignalSubscription
import com.wire.signals.Subscription.Subscriber
import utils._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.ref.WeakReference
import scala.util.Try

object Signal {
  final private class SignalSubscription[V](source:           Signal[V],
                                           subscriber:       Subscriber[V],
                                           executionContext: Option[ExecutionContext] = None
                                          )(implicit context: WeakReference[EventContext])
    extends BaseSubscription(context) with SignalSubscriber {

    override def changed(currentContext: Option[ExecutionContext]): Unit = synchronized {
      source.value.foreach { event =>
        if (subscribed)
          executionContext match {
            case Some(ec) if !currentContext.contains(ec) => Future(if (subscribed) Try(subscriber(event)))(ec)
            case _ => subscriber(event)
          }
      }
    }

    override protected[signals] def onSubscribe(): Unit = {
      source.subscribe(this)
      changed(None) // refresh the subscriber with current value
    }

    override protected[signals] def onUnsubscribe(): Unit = source.unsubscribe(this)
  }

  /** Creates a new [[SourceSignal]] of values of the type `V`. A usual entry point for the signals network.
    * Starts uninitialized (its value is set to `None`).
    *
    * @tparam V The type of the values which can be published to the signal.
    * @return A new signal of values of the type `V`.
    */
  def apply[V]() = new SourceSignal[V] with NoAutowiring

  /** Creates a new [[SourceSignal]] of values of the type `V`. A usual entry point for the signals network.
    * Starts initialized to the given value.
    *
    * @param v The initial value in the signal.
    * @tparam V The type of the values which can be published to the signal.
    * @return A new signal of values of the type `V`.
    */
  def apply[V](v: V) = new SourceSignal[V](Some(v)) with NoAutowiring

  private lazy val EMPTY = new ConstSignal[Any](None)

  /** Creates an empty, uninitialized [[ConstSignal]].
    * Empty signals can be used in flatMap chains to signalize (ha!) that for the given value of the parent signal all further
    * computations should be withheld until the value changes to something more useful.
    * ```
    * val parentSignal = Signal[Int]()
    * val thisSignal = parentSignal.flatMap {
    *   case n if n > 2 => Signal.const(n * 2)
    *   case _ => Signal.empty[Int]
    * }
    * thisSignal.foreach(println)
    * ```
    * Here, the function `println` will be called only for values > 2 published to `parentSignal`.
    * Basically, you may think of empty signals as a way to build alternatives to `Signal.filter` and `Signal.collect` when
    * you need more fine-grained control over conditions of propagating values.
    *
    * @tparam V The type of the value (used only in type-checking)
    * @return A new empty signal.
    */
  def empty[V]: Signal[V] = EMPTY.asInstanceOf[Signal[V]]

  /** Creates a [[ConstSignal]] initialized to the given value.
    * Use a const signal for providing a source of an immutable value in the chain of signals. Subscribing to a const signal
    * usually makes no sense, but they can be used in flatMaps in cases where the given value of the parent signal should
    * result always in the same value. Using [[ConstSignal]] in such case should have some performance advantage over using
    * a regular signal holding a (in theory mutable) value.
    *
    * @param v The immutable value held by the signal.
    * @tparam V The type of the value.
    * @return A new const signal initialized to the given value.
    */
  def const[V](v: V): Signal[V] = new ConstSignal[V](Some(v))

  /** Creates a new signal by joining together the original signals of two different types of values, `A` and `B`.
    * The resulting signal will hold a tuple of the original values and update every time one of them changes.
    *
    * Note this is *not* a method analogous to `EventStream.zip`. Here the parent signals can be of different type (`EventStream.zip`
    * requires all parent streams to be of the same type) but on the other hand we're not able to zip an arbitrary number of signals.
    * Also, the result value is a tuple, not just one event after another.
    *
    * Please also see `Signal.sequence` for a method which resembles `EventStream.zip` in a different way.
    *
    * @param s1 The first of the parent signals.
    * @param s2 The second of the parent signals.
    * @tparam A The type of the value of the first of parent signals.
    * @tparam B The type of the value of the second of parent signals.
    * @return A new signal its the value constructed as a tuple of values form the parent signals.
    */
  def zip[A, B](s1: Signal[A], s2: Signal[B]): Signal[(A, B)] = new Zip2Signal[A, B](s1, s2)

  /** A version of the `zip` method joining three signals of different value types. */
  def zip[A, B, C](s1: Signal[A], s2: Signal[B], s3: Signal[C]): Signal[(A, B, C)] = new Zip3Signal(s1, s2, s3)

  /** A version of the `zip` method joining four signals of different value types. */
  def zip[A, B, C, D](s1: Signal[A], s2: Signal[B], s3: Signal[C], s4: Signal[D]): Signal[(A, B, C, D)] = new Zip4Signal(s1, s2, s3, s4)

  /** A version of the `zip` method joining five signals of different value types. */
  def zip[A, B, C, D, E](s1: Signal[A], s2: Signal[B], s3: Signal[C], s4: Signal[D], s5: Signal[E]): Signal[(A, B, C, D, E)] =
    new Zip5Signal(s1, s2, s3, s4, s5)

  /** A version of the `zip` method joining six signals of different value types. */
  def zip[A, B, C, D, E, F](s1: Signal[A], s2: Signal[B], s3: Signal[C], s4: Signal[D], s5: Signal[E], s6: Signal[F]): Signal[(A, B, C, D, E, F)] =
    new Zip6Signal(s1, s2, s3, s4, s5, s6)

  /** A utility method for creating a [[ThrottledSignal]] with the value of the given type and updated no more often than once
    * every given time interval.
    *
    * @see [[ThrottledSignal]]
    *
    * @param s The parent signal providing the original value.
    * @param delay The time interval used for throttling.
    * @tparam V The type of value in both the parent signal and the new one.
    * @return A new throttled signal.
    */
  def throttled[V](s: Signal[V], delay: FiniteDuration): Signal[V] = new ThrottledSignal(s, delay)

  /** Creates a signal from an initial value, a list of parent signals, and a folding function. On initialization, and then on
    * every change of value of any of the parent signals, the folding function will be called for the whole list and use
    * the current values to produce a result, analogous to the `foldLeft` method in Scala collections.
    *
    * @see [[scala.collection.GenTraversableOnce.foldLeft]]
    *
    * @param sources A variable arguments list of parent signals, all with values of the same type `V`.
    * @param zero The initial value of the type `Z`.
    * @param f A folding function which takes the value of the type `Z`, another value of the type `V`, and produces a new value
    *          of the type `Z` again, so then it can use it in the next interation as its first argument, together with the current
    *          value of the next of the parent signals.
    * @tparam V The type of values in the parent streams.
    * @tparam Z The type of the initial and result value of the new signal.
    * @return A new signal of values of the type `Z`.
    */
  def foldLeft[V, Z](sources: Signal[V]*)(zero: Z)(f: (Z, V) => Z): Signal[Z] = new FoldLeftSignal[V, Z](sources: _*)(zero)(f)

  /** Creates a `Signal[Boolean]` of an arbitrary number of parent signals of `Boolean`.
    * The new signal's value will be `true` only if *all* parent signals values are `true`, and `false` if even one of them
    * changes its value to `false`.
    *
    * @param sources  A variable arguments list of parent signals of the type `Boolean`.
    * @return A new signal of `Boolean`.
    */
  def and(sources: Signal[Boolean]*): Signal[Boolean] = new FoldLeftSignal[Boolean, Boolean](sources: _*)(true)(_ && _)

  /** Creates a `Signal[Boolean]` of an arbitrary number of parent signals of `Boolean`.
    * The new signal's value will be `true` if *any* of the parent signals values is `true`, and `false` only if all one of them
    * change its value to `false`.
    *
    * @param sources  A variable arguments list of parent signals of the type `Boolean`.
    * @return A new signal of `Boolean`.
    */
  def or(sources: Signal[Boolean]*): Signal[Boolean] = new FoldLeftSignal[Boolean, Boolean](sources: _*)(false)(_ || _)

  /** Creates a signal of an arbitrary number of parent signals of the same value.
    * The value of the new signal is the sequence of values of all parent signals in the same order.
    * You can actually think of it as an analogous method to `EventStream.zip`.
    *
    * @param sources A variable arguments list of parent signals of the same type.
    * @tparam V The type of the values in the parent signals.
    * @return A new signal with its value being a sequence of current values of the parent signals.
    */
  def sequence[V](sources: Signal[V]*): Signal[Seq[V]] = new ProxySignal[Seq[V]](sources: _*) {
    override protected def computeValue(current: Option[Seq[V]]): Option[Seq[V]] = {
      val res = sources.map(_.value)
      if (res.exists(_.isEmpty)) None else Some(res.flatten)
    }
  }

  /** Creates a new signal from a future.
    * The signal will start uninitialized and initialize to its only, never again changing value if the future finishes with success.
    * If the future fails, the signal will stay empty. The subscriber functions registered in this signal will be called in
    * the given execution context if they don't explicitly specify the execution context they should be called in.
    *
    * Please note that in the typical case the subscriber functions probably will have it specified in what execution context
    * they should be called, as this allows for better control about what code is called in what e.c. The e.c. specified here
    * does *not* take precedent over the one specified in the subscription. Therefore, usually, it makes sense to use the overloaded
    * version of this method which uses the default execution context.
    *
    * @see [[Threading]]
    *
    * @param future The future producing the first and only value for the signal.
    * @param executionContext The execution context in which the subscriber functions will be called if not specified otherwise.
    * @tparam V The type of the value produced by the future.
    * @return A new signal which will hold the value produced by the future.
    */
  def from[V](future: Future[V], executionContext: ExecutionContext): Signal[V] = returning(new Signal[V]) { signal =>
    future.foreach {
      res => signal.set(Option(res), Some(executionContext))
    }(executionContext)
  }

  /** A version of `from` using the default execution context as its second argument. */
  def from[V](future: Future[V]): Signal[V] = from(future, Threading.defaultContext)

  /** A version of `from` creating a signal from a cancellable future. */
  def from[V](future: CancellableFuture[V], executionContext: ExecutionContext): Signal[V] = from(future.future, executionContext)

  /** A version of `from` creating a signal from a cancellable future, and using the default execution context. */
  def from[V](future: CancellableFuture[V]): Signal[V] = from(future.future, Threading.defaultContext)

  /** Creates a new signal from an event stream and an initial value.
    * The signal will be initialized to the initial value on its creation, and subscribe to the event stream.
    * Subsequently, it will update the value as new events are published in the parent event stream.
    *
    * @param initial The initial value of the signal.
    * @param source The parent event stream.
    * @tparam V The type of both the initial value and the events in the parent stream.
    * @return A new signal with the value of the type `V`.
    */
  def from[V](initial: V, source: EventStream[V]): Signal[V] = new Signal[V](Some(initial)) {
    private[this] lazy val subscription = source(publish)(EventContext.Global)

    override protected def onWire(): Unit = subscription.enable()
    override protected def onUnwire(): Unit = subscription.disable()
  }

  /** Creates a new signal from an event stream.
    * The signal will start uninitialized and subscribe to the parent event stream. Subsequently, it will update its value
    * as new events are published in the parent event stream.
    *
    * @param source The parent event stream.
    * @tparam V The type of the events in the parent stream.
    * @return A new signal with the value of the type `V`.
    */
  def from[V](source: EventStream[V]): Signal[V] = new Signal[V](None) {
    private[this] lazy val subscription = source(publish)(EventContext.Global)

    override protected def onWire(): Unit = subscription.enable()
    override protected def onUnwire(): Unit = subscription.disable()
  }
}

/** A signal is an event stream with a cache.
  *
  * Whereas an event stream holds no internal state and just passes on events it receives, a signal keeps the last value it received.
  * A new subscriber function registered in an event stream will be called only when a new event is published.
  * A new subscriber function registered in a signal will be called immediately (or as soon as possible on the given execution context)
  * with the current value of the signal (unless it's not initialized yet) and then again when the value changes.
  * A signal is also able to compare a new value published in it with the old one - the new value will be passed on only if
  * it is different. Thus, a signal can help us with optimizing performance on both ends: as a cache for values which otherwise
  * would require expensive computations to produce them every time we need them, and as a way to ensure that subscriber functions
  * are called only when the value actually changes, but not when the result of the intermediate computation is the same as before.
  *
  * Note that for clarity we talk about *events* in the event streams, but about *values* in signals.
  *
  * An signal of the type `V` dispatches values to all functions of the type `(V) => Unit` which were registered in
  * the signal as its subscribers. It provides a handful of methods which enable the user to create new signals by means of composing
  * the old ones, filtering them, etc., in a way similar to how the user can operate on standard collections, as well as to interact with
  * Scala futures, cancellable futures, and event streams. Please note that by default a signal is not able to receive events from the outside -
  * that functionality belongs to [[SourceSignal]].
  *
  * @see [[EventStream]]
  *
  * @param value The option of the last value published in the signal or `None` if the signal was not initialized yet.
  * @tparam V The type of the value held in the signal.
  */
class Signal[V](@volatile protected[signals] var value: Option[V] = None)
  extends EventSource[V] with Subscribable[SignalSubscriber] { self =>
  private object updateMonitor

  protected[signals] def update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext] = None): Boolean = {
    val changed = updateMonitor.synchronized {
      val next = f(value)
      if (value != next) {
        value = next; true
      }
      else false
    }
    if (changed) notifySubscribers(currentContext)
    changed
  }

  protected[signals] def set(v: Option[V], currentContext: Option[ExecutionContext] = None): Unit =
    if (value != v) {
      value = v
      notifySubscribers(currentContext)
    }

  protected[signals] def notifySubscribers(currentContext: Option[ExecutionContext]): Unit =
    super.notifySubscribers(_.changed(currentContext))

  final def currentValue: Option[V] = {
    if (!wired) disableAutowiring()
    value
  }

  final lazy val onChanged: EventStream[V] = new EventStream[V] with SignalSubscriber { stream =>
    private var prev = self.value

    override def changed(ec: Option[ExecutionContext]): Unit = stream.synchronized {
      self.value.foreach { current =>
        if (!prev.contains(current)) {
          dispatch(current, ec)
          prev = Some(current)
        }
      }
    }

    override protected def onWire(): Unit = self.subscribe(this)

    override protected[signals] def onUnwire(): Unit = self.unsubscribe(this)
  }

  final def head: Future[V] = currentValue match {
    case Some(v) => Future.successful(v)
    case None =>
      val p = Promise[V]()
      val subscriber = new SignalSubscriber {
        override def changed(ec: Option[ExecutionContext]): Unit = value.foreach(p.trySuccess)
      }
      subscribe(subscriber)
      p.future.onComplete(_ => unsubscribe(subscriber))(Threading.defaultContext)
      value.foreach(p.trySuccess)
      p.future
  }

  final def future: Future[V] = head

  final def zip[Z](s: Signal[Z]): Signal[(V, Z)] = new Zip2Signal[V, Z](this, s)

  final def map[Z](f: V => Z): Signal[Z] = new MapSignal[V, Z](this, f)

  final def filter(f: V => Boolean): Signal[V] = new FilterSignal(this, f)

  final def withFilter(f: V => Boolean): Signal[V] = filter(f)

  final def ifTrue(implicit ev: V =:= Boolean): Signal[Unit] = collect { case true => () }

  final def ifFalse(implicit ev: V =:= Boolean): Signal[Unit] = collect { case false => () }

  final def collect[Z](pf: PartialFunction[V, Z]): Signal[Z] = new ProxySignal[Z](this) {
    override protected def computeValue(current: Option[Z]): Option[Z] = self.value.flatMap { v =>
      pf.andThen(Option(_)).applyOrElse(v, { _: V => Option.empty[Z] })
    }
  }

  final def flatMap[Z](f: V => Signal[Z]): Signal[Z] = new FlatMapSignal[V, Z](this, f)

  final def flatten[Z](implicit evidence: V <:< Signal[Z]): Signal[Z] = flatMap(x => x)

  final def scan[Z](zero: Z)(f: (Z, V) => Z): Signal[Z] = new ScanSignal[V, Z](this, zero, f)

  final def combine[Z, Y](s: Signal[Z])(f: (V, Z) => Y): Signal[Y] = new ProxySignal[Y](this, s) {
    override protected def computeValue(current: Option[Y]): Option[Y] = for (v <- self.value; z <- s.value) yield f(v, z)
  }

  final def throttle(delay: FiniteDuration): Signal[V] = new ThrottledSignal(this, delay)

  final def orElse(fallback: Signal[V]): Signal[V] = new ProxySignal[V](self, fallback) {
    override protected def computeValue(current: Option[V]): Option[V] = self.value.orElse(fallback.value)
  }

  final def either[Z](right: Signal[Z]): Signal[Either[V, Z]] = map(Left(_): Either[V, Z]).orElse(right.map(Right.apply))

  final def pipeTo(sourceSignal: SourceSignal[V])(implicit ec: EventContext = EventContext.Global): Unit = apply(sourceSignal ! _)
  final def |(sourceSignal: SourceSignal[V])(implicit ec: EventContext = EventContext.Global): Unit = pipeTo(sourceSignal)

  final def onPartialUpdate[Z](select: V => Z): Signal[V] = new PartialUpdateSignal[V, Z](this)(select)

  /** If this signal is computed from sources that change their value via a side effect (such as signals) and is not
    * informed of those changes while unwired (e.g. because this signal removes itself from the sources' children
    * lists in #onUnwire), it is mandatory to update/recompute this signal's value from the sources in #onWire, since
    * a dispatch always happens after #onWire. This is true even if the source values themselves did not change, for the
    * recomputation in itself may rely on side effects (e.g. ZMessaging => SomeValueFromTheDatabase).
    *
    * This also implies that a signal should never #dispatch in #onWire because that will happen anyway immediately
    * afterwards in #subscribe.
    */
  protected def onWire(): Unit = {}

  protected def onUnwire(): Unit = {}

  override def on(ec: ExecutionContext)(subscriber: Subscriber[V])(implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(new SignalSubscription[V](this, subscriber, Some(ec))(WeakReference(eventContext)))(_.enable())

  override def apply(subscriber: Subscriber[V])(implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(new SignalSubscription[V](this, subscriber, None)(WeakReference(eventContext)))(_.enable())

  protected def publish(value: V): Unit = set(Some(value))

  protected def publish(value: V, currentContext: ExecutionContext): Unit = set(Some(value), Some(currentContext))
}

trait NoAutowiring { self: Signal[_] =>
  disableAutowiring()
}

abstract class ProxySignal[V](sources: Signal[_]*) extends Signal[V] with SignalSubscriber {
  override def onWire(): Unit = {
    sources.foreach(_.subscribe(this))
    value = computeValue(value)
  }

  override def onUnwire(): Unit = sources.foreach(_.unsubscribe(this))

  override def changed(ec: Option[ExecutionContext]): Unit = update(computeValue, ec)

  protected def computeValue(current: Option[V]): Option[V]
}

final private[signals] class ScanSignal[V, Z](source: Signal[V], zero: Z, f: (Z, V) => Z) extends ProxySignal[Z](source) {
  value = Some(zero)

  override protected def computeValue(current: Option[Z]): Option[Z] =
    source.value.map { v => f(current.getOrElse(zero), v) }.orElse(current)
}

final private[signals] class FilterSignal[V](source: Signal[V], f: V => Boolean) extends ProxySignal[V](source) {
  override protected def computeValue(current: Option[V]): Option[V] = source.value.filter(f)
}

final private[signals] class MapSignal[V, Z](source: Signal[V], f: V => Z) extends ProxySignal[Z](source) {
  override protected def computeValue(current: Option[Z]): Option[Z] = source.value.map(f)
}

final private[signals] class Zip2Signal[A, B](s1: Signal[A], s2: Signal[B]) extends ProxySignal[(A, B)](s1, s2) {
  override protected def computeValue(current: Option[(A, B)]): Option[(A, B)] =
    for (a <- s1.value; b <- s2.value) yield (a, b)
}

final private[signals] class Zip3Signal[A, B, C](s1: Signal[A], s2: Signal[B], s3: Signal[C])
  extends ProxySignal[(A, B, C)](s1, s2, s3) {
  override protected def computeValue(current: Option[(A, B, C)]): Option[(A, B, C)] =
    for {
      a <- s1.value
      b <- s2.value
      c <- s3.value
    } yield (a, b, c)
}

final private[signals] class Zip4Signal[A, B, C, D](s1: Signal[A], s2: Signal[B], s3: Signal[C], s4: Signal[D])
  extends ProxySignal[(A, B, C, D)](s1, s2, s3, s4) {
  override protected def computeValue(current: Option[(A, B, C, D)]): Option[(A, B, C, D)] =
    for {
      a <- s1.value
      b <- s2.value
      c <- s3.value
      d <- s4.value
    } yield (a, b, c, d)
}

final private[signals] class Zip5Signal[A, B, C, D, E](s1: Signal[A], s2: Signal[B], s3: Signal[C], s4: Signal[D], s5: Signal[E])
  extends ProxySignal[(A, B, C, D, E)](s1, s2, s3, s4, s5) {
  override protected def computeValue(current: Option[(A, B, C, D, E)]): Option[(A, B, C, D, E)] =
    for {
      a <- s1.value
      b <- s2.value
      c <- s3.value
      d <- s4.value
      e <- s5.value
    } yield (a, b, c, d, e)
}

final private[signals] class Zip6Signal[A, B, C, D, E, F](s1: Signal[A], s2: Signal[B], s3: Signal[C], s4: Signal[D], s5: Signal[E], s6: Signal[F])
  extends ProxySignal[(A, B, C, D, E, F)](s1, s2, s3, s4, s5, s6) {
  override protected def computeValue(current: Option[(A, B, C, D, E, F)]): Option[(A, B, C, D, E, F)] = for {
    a <- s1.value
    b <- s2.value
    c <- s3.value
    d <- s4.value
    e <- s5.value
    f <- s6.value
  } yield (a, b, c, d, e, f)
}

final private[signals] class FoldLeftSignal[V, Z](sources: Signal[V]*)(v: Z)(f: (Z, V) => Z) extends ProxySignal[Z](sources: _*) {
  override protected def computeValue(current: Option[Z]): Option[Z] =
    sources.foldLeft(Option(v))((mv, signal) => for (a <- mv; b <- signal.value) yield f(a, b))
}

final private[signals] class PartialUpdateSignal[V, Z](source: Signal[V])(select: V => Z) extends ProxySignal[V](source) {
  private object updateMonitor

  override protected[signals] def update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext]): Boolean = {
    val changed = updateMonitor.synchronized {
      val next = f(value)
      if (value.map(select) != next.map(select)) {
        value = next
        true
      }
      else false
    }
    if (changed) notifySubscribers(currentContext)
    changed
  }

  override protected def computeValue(current: Option[V]): Option[V] = source.value
}
