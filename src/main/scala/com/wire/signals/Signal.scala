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

import com.wire.signals.Signal.{SignalSubscriber, SignalSubscription}
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

  private[signals] trait SignalSubscriber {
    // 'currentContext' is the context this method IS run in, NOT the context any subsequent methods SHOULD run in
    def changed(currentContext: Option[ExecutionContext]): Unit
  }

  private[signals] object SignalSubscriber {
    def apply(): SignalSubscriber = new DoNothingSignalSubscriber()

    final class DoNothingSignalSubscriber extends SignalSubscriber {
      override def changed(currentContext: Option[ExecutionContext]): Unit = ()
    }
  }

  final private val Empty = new ConstSignal[Any](None)

  /** Creates a new [[SourceSignal]] of values of the type `V`. A usual entry point for the signals network.
    * Starts uninitialized (its value is set to `None`).
    *
    * @tparam V The type of the values which can be published to the signal.
    * @return A new signal of values of the type `V`.
    */
  def apply[V](): SourceSignal[V] = new SourceSignal[V] with NoAutowiring

  /** Creates a new [[SourceSignal]] of values of the type `V`. A usual entry point for the signals network.
    * Starts initialized to the given value.
    *
    * @param v The initial value in the signal.
    * @tparam V The type of the values which can be published to the signal.
    * @return A new signal of values of the type `V`.
    */
  def apply[V](v: V): SourceSignal[V] = new SourceSignal[V](Some(v)) with NoAutowiring

  /** Returns an empty, uninitialized, immutable signal of the given type.
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
    * @see [[ConstSignal]]
    *
    * @tparam V The type of the value (used only in type-checking)
    * @return A new empty signal.
    */
  @inline def empty[V]: Signal[V] = Empty.asInstanceOf[Signal[V]]

  /** Creates a [[ConstSignal]] initialized to the given value.
    * Use a const signal for providing a source of an immutable value in the chain of signals. Subscribing to a const signal
    * usually makes no sense, but they can be used in flatMaps in cases where the given value of the parent signal should
    * result always in the same value. Using [[ConstSignal]] in such case should have some performance advantage over using
    * a regular signal holding a (in theory mutable) value.
    *
    * @see [[ConstSignal]]
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
    * during the given time interval. If changes to the value of the parent signal happen more often, some of them will be ignored.
    *
    * @see [[ThrottledSignal]]
    * @param source The parent signal providing the original value.
    * @param delay The time interval used for throttling.
    * @tparam V The type of value in both the parent signal and the new one.
    * @return A new throttled signal of the same value type as the parent.
    */
  def throttled[V](source: Signal[V], delay: FiniteDuration): Signal[V] = new ThrottledSignal(source, delay)

  /** Creates a signal from an initial value, a list of parent signals, and a folding function. On initialization, and then on
    * every change of value of any of the parent signals, the folding function will be called for the whole list and use
    * the current values to produce a result, analogous to the `foldLeft` method in Scala collections.
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
  def from[V](initial: V, source: EventStream[V]): Signal[V] = new EventStreamSignal[V](source, Option(initial))

  /** Creates a new signal from an event stream.
    * The signal will start uninitialized and subscribe to the parent event stream. Subsequently, it will update its value
    * as new events are published in the parent event stream.
    *
    * @param source The parent event stream.
    * @tparam V The type of the events in the parent stream.
    * @return A new signal with the value of the type `V`.
    */
  def from[V](source: EventStream[V]): Signal[V] = new EventStreamSignal[V](source)
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
class Signal[V] protected (@volatile protected[signals] var value: Option[V] = None)
  extends EventSource[V] with Subscribable[SignalSubscriber] { self =>
  private object updateMonitor

  /** Updates the current value of the signal by applying a given function to it.
    * The function should return an option of the value type. If the result is `None` the signal will become empty until the next update.
    * The subscribers will be notified of the update only if the new value is different from the current one. If yes, we will try to
    * call them on the given execution context, but only if the subscriptions do not specify otherwise.
    *
    * @param f The function used to update the value of the signal.
    * @param currentContext The execution context on which the subscriber functions will be called if subscriptions don't specify otherwise (optional).
    * @return true if the update actually happened and subscribers will be notified, false if the new value is the same as the old one.
    */
  protected[signals] def update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext] = None): Boolean =
    updateMonitor.synchronized {
      set(f(value), currentContext)
    }

  /** Sets the value of the signal to the new one.
    * The new value is an option of the value type. If the result is `None` the signal will become empty until the next update.
    * The subscribers will be notified of the update only if the new value is different from the current one. If yes, we will try to
    * call them on the given execution context, but only if the subscriptions do not specify otherwise.
    *
    * @todo Check why we synchronize in `update` but not here. It clearly works: tests fail if we synchronize this method. I just want to know why.
    *
    * @param v The new value of the signal.
    * @param currentContext The execution context on which the subscriber functions will be called if subscriptions don't specify otherwise (optional).
    * @return true if the new value is different from the old one and so a change actually happens and the subscribers will be notified,
    *         false if the new value is the same as the old one.
    */
  protected[signals] def set(v: Option[V], currentContext: Option[ExecutionContext] = None): Boolean =
    if (value != v) {
      value = v
      notifySubscribers(currentContext)
      true
    } else false

  /** Notifies the subscribers that the value of the signal has changed.
    *
    * @param currentContext The execution context on which the subscriber functions will be called if subscriptions don't specify otherwise (optional).
    */
  protected def notifySubscribers(currentContext: Option[ExecutionContext] = None): Unit =
    super.notifySubscribers(_.changed(currentContext))

  /** The current value of the signal.
    * If the signal requires some initial work before accessing its value for the first time, it will be done exactly one time.
    * Subsequently, this method will simply return the current value.
    *
    * Please note that this will return an option of the value type. You may get a `None` if the signal is not initialized yet
    * or if it was temporarily cleared and awaits another update. Usually, it's safer to use `head` or `future` and work with
    * a future of the value type instead.
    *
    * @return The current value of the signal.
    */
  @inline final def currentValue: Option[V] = {
    if (!wired) disableAutowiring()
    value
  }

  /** A future with the current value of the signal.
    * The future will finish immediately with the current value of the signal if the value is already set. If the signal is empty,
    * the future will finish when the next update sets the value.
    *

    * @return The current value of the signal or the value it will be set to in the next update.
    */
  final def future: Future[V] = currentValue match {
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

  /** An alias to the `future` method. */
  @inline @deprecated("Use .future instead", "0.4") final def head: Future[V] = future

  /** An event stream where each event is a tuple of the old and the new value of the signal.
    * Every time the value of the signal changes - actually changes to another value - the new value will be published in this stream,
    * together with the old value which you can use to check what exactly changed. The old value is wrapped in an `Option`: if the signal 
    * was previously empty, the old value will be `None` otherwise it will be `Some[V]`.
    * The values are guaranteed to differ, i.e. if you get a tuple `(Some(oldValue), newValue)` then `oldValue != newValue`.
    */
  final lazy val onUpdated: EventStream[(Option[V], V)] = new EventStream[(Option[V], V)] with SignalSubscriber { stream =>
    private var prev = self.value

    override def changed(ec: Option[ExecutionContext]): Unit = stream.synchronized {
      self.value.foreach { current =>
        if (!prev.contains(current)) {
          dispatch((prev, current), ec)
          prev = Some(current)
        }
      }
    }

    override protected def onWire(): Unit = self.subscribe(this)

    override protected[signals] def onUnwire(): Unit = self.unsubscribe(this)
  }

  /** An event stream where each event is a new value of the signal.
    * Every time the value of the signal changes - actually changes to another value - the new value will be published in this stream.
    * The events in the stream are guaranteed to differ. It's not possible to get two equal events one after another.
    */
  @inline final lazy val onChanged: EventStream[V] = onUpdated.map(_._2)

  /** Zips this signal with the given one.
    *
    * @param other The other signal with values of the same or a different type.
    * @tparam Z The type of the values of the other signal.
    * @return A new signal with values being tuples of the value of this signal and the other one.
    *         The value of the other signal will be updated every time this or the other signal's value is updated.
    */
  final def zip[Z](other: Signal[Z]): Signal[(V, Z)] = new Zip2Signal[V, Z](this, other)

  /** Creates a new `Signal[Z]` by mapping the value of the type `V` of this signal.
    *
    * @param f The function mapping the value of the original signal into the value of the new signal.
    * @tparam Z The value type of the new signal.
    * @return A new signal
    */
  final def map[Z](f: V => Z): Signal[Z] = new MapSignal[V, Z](this, f)

  /** Creates a new `Signal[V]` which updates its value only if the new value of the original signal satisfies the filter,
    * and changes to empty otherwise. Also, if the initial value of the original signal does not satisfy the filter,
    * the new signal will start empty.
    *
    * @param f A filtering function which for any value of the original signal returns true or false.
    * @return A new signal of the same value type.
    */
  final def filter(f: V => Boolean): Signal[V] = new FilterSignal(this, f)

  /** An alias for `filter` used in the for/yield notation.
    *
    * This can be useful for more readable chains of asynchronous computations where at some point we want to wait until
    * some condition is fulfilled:
    * ```
    * val resultSignal = for {
    *  a    <- signalA
    *  b    <- signalB
    *  true <- checkCondition(a, b)
    *  c    <- signalC
    * } yield c
    * ```
    * Here, `resultSignal` will be updated to the value of `signalC` only if the current values of `signalA` and `signalB` fulfill
    * the condition. If the check fails, `resultSignal` will become empty until `signalA` or `signalB` changes its value and the new
    * pair fulfills the condition.
    */
  @inline final def withFilter(f: V => Boolean): Signal[V] = filter(f)

  /** Assuming that the value of the signal can be interpreted as a boolean, this method returns a future
    * of type `Unit` which will finish with success when the value of the original signal is true.
    *
    * ```
    * val signal = Signal[Int](3)
    * signal.map(_ % 2 == 0).onTrue.foreach { _ => println("This is the first time the value of the signal is even") }
    * ```
    *
    * @return A new future which finishes either immediately or as soon as the value of the original signal is true.
    */
  final def onTrue(implicit ev: V =:= Boolean): Future[Unit] = collect { case true => () }.future

  /** Assuming that the value of the signal can be interpreted as a boolean, this method returns a future
    * of type `Unit` which will finish with success when the value of the original signal is false.
    *
    * ```
    * val signal = Signal[Int](2)
    * signal.map(_ % 2 == 0).onFalse.foreach { _ => println("This is the first time the value of the signal is odd") }
    * ```
    *
    * @return A new future which finishes either immediately or as soon as the value of the original signal is false.
    */
  final def onFalse(implicit ev: V =:= Boolean): Future[Unit] = collect { case false => () }.future

  /** Creates a new signal of values of the type `Z` by applying a partial function which maps the original value of the type `V`
    * to a value of the type `Z`. If the partial function doesn't work for the current value, the new signal will become empty
    * until the next update. Basically, it's filter + map.
    *
    * @param pf A partial function which for the original value of the type `V` may produce a value of the type `Z`.
    * @tparam Z The value type of the new signal.
    * @return A new signal with values of the type `Z`, holding the value produced from the original signal's value by
    *         the partial function, or empty if that's not possible.
    */
  final def collect[Z](pf: PartialFunction[V, Z]): Signal[Z] = new ProxySignal[Z](this) {
    override protected def computeValue(current: Option[Z]): Option[Z] = self.value.flatMap { v =>
      pf.andThen(Option(_)).applyOrElse(v, { _: V => Option.empty[Z] })
    }
  }

  /** Creates a new `Signal[Z]` by mapping each event of the original `Signal[V]` to a new signal and switching to it.
    * The usual use case is to create a new complex signal not as one big entity with the value being the result of
    * computations based on a lot of data at once, but to break it into simpler signals connected by flatMaps. At each
    * step the used signal produces an intermediate value and recomputing that value is not necessary again until
    * the values used to compute that one are changed too.
    *
    * @param f The function mapping each event of type `v` to a signal of the type `Z`.
    * @tparam Z The value type of the new signal.
    * @return A new or already existing signal to which we switch as the result of a change in the value of the original signal.
    */
  final def flatMap[Z](f: V => Signal[Z]): Signal[Z] = new FlatMapSignal[V, Z](this, f)

  /** Flattens a signal whose value type is also a signal.
    *
    * @tparam Z The type of the value of the nested signal.
    * @return A new signal of the value type the same as the value type of the nested signal.
    */
  final def flatten[Z](implicit evidence: V <:< Signal[Z]): Signal[Z] = flatMap(x => x)

  /** Creates a new signal with the value type `Z` where the change in the value is the result of applying a function
    * which combines the previous value of type `Z` with the changed value of the type `V` of the parent signal.
    *
    * @todo Test if it really works like that, the code is a bit complicated.
    *
    * @param zero The initial value of the new signal.
    * @param f The function which combines the current value of the new signal with the new, changed value of the parent (this) signal
    *          to produce a new value for the new signal (might be the same as the old one and then subscribers won't be notified).
    * @tparam Z The value type of the new signal.
    * @return A new signal with the value of the type `Z`.
    */
  final def scan[Z](zero: Z)(f: (Z, V) => Z): Signal[Z] = new ScanSignal[V, Z](this, zero, f)

  /** Combines the current values of this and another signal of the same or different types `V` and `Z` to produce a signal with the value
    * of yet another type `Y`. Basically, zip + map.
    *
    * @param other The other signal with values of the same or a different type.
    * @param f The function which combines the current values of both parent signals to produce the value of the new signal.
    * @tparam Z The value type of the other signal.
    * @tparam Y The value type of the new signal.
    * @return A new signal with the value of the type `Y`.
    */
  final def combine[Z, Y](other: Signal[Z])(f: (V, Z) => Y): Signal[Y] = new ProxySignal[Y](this, other) {
    override protected def computeValue(current: Option[Y]): Option[Y] = for (v <- self.value; z <- other.value) yield f(v, z)
  }

  /** Creates a throttled version of this signal which updates no more often than once during the given time interval.
    * If changes to the value of the parent signal happen more often, some of them will be ignored.
    *
    * @see [[ThrottledSignal]]
    *
    * @param delay The time interval used for throttling.
    * @return A new throttled signal of the same value type as the parent.
    */
  final def throttle(delay: FiniteDuration): Signal[V] = new ThrottledSignal(this, delay)

  /** Creates a version of this signal which, if the parent signal becomes empty, temporarily uses the value of the given
    * `fallback` signal. The moment the parent signal is set to a new value again, the new signal switches back to it.
    * Only when both signals are empty, the new signal will become empty too.
    *
    * @param fallback Another signal of the same value type.
    * @return A new signal of the same value type.
    */
  final def orElse(fallback: Signal[V]): Signal[V] = new ProxySignal[V](self, fallback) {
    override protected def computeValue(current: Option[V]): Option[V] = self.value.orElse(fallback.value)
  }

  /** A generalization of the `orElse` method where the fallback signal can have another value type.
    * If the value of this signal is `V` and the value of the fallback signal is `Z`, the new signal will return
    * an `Either[Z, V]`. When the parent signal is set, the value of the new signal will be `Right(v)`. When the parent
    * signal becomes empty, the value of the new signal will temporarily switch to `Left(z)` where `z` is the current value
    * of the fallback signal. The moment the parent signal is set to a new value again, the new signal will switch back to
    * `Right(v)`.
    * Only when both signals are empty, the new signal will become empty too.
    *
    * @param fallback Another signal of the same or different value type.
    * @tparam Z The value type of the fallback signal.
    * @return A new signal with the value being either the value of the parent or the value of the fallback signal if
    *         the parent is empty.
    */
  final def either[Z](fallback: Signal[Z]): Signal[Either[Z, V]] = map(Right(_): Either[Z, V]).orElse(fallback.map(Left.apply))

  /** A shorthand for registering a subscriber function in this signal which only purpose is to publish changes to the value
    * of this signal in another [[SourceSignal]]. The subscriber function will be called in the execution context of the
    * original publisher.
    *
    * @see [[SourceSignal]]
    *
    * @param sourceSignal he signal in which changes to the value of this signal will be published.
    * @param ec An [[EventContext]] which can be used to manage the subscription (optional).
    * @return A new [[Subscription]] to this signal.
    */
  final def pipeTo(sourceSignal: SourceSignal[V])(implicit ec: EventContext = EventContext.Global): Subscription = apply(sourceSignal ! _)

  /** An alias for `pipeTo`. */
  @inline final def |(sourceSignal: SourceSignal[V])(implicit ec: EventContext = EventContext.Global): Subscription = pipeTo(sourceSignal)

  /** Creates a new signal of the same value type which changes its value to the changed value of the parent signal only if
    * the given `select` function returns different results for the old and the new value. If the results of the `select`
    * functions are equal, then even if the new value of the original signal is actually different from the old one, the value
    * of the new signal stays the same.
    *
    * Consider the following example:
    * ```
    * val parent = Signal[Int](3)
    * val oddEvenSwitch = parent.onPartialUpdate { _ % 2 == 0 }
    * oddEvenSwitch.foreach { n => println(s"The value switched between odd and even and is now equal to $n") }
    * ```
    * Here, the value of `oddEvenSwitch` will update only if the new value is even if the old one was odd and vice versa.
    * So, if we publish new odd values to `parent` (1, 5, 9, 7, ...) the value of `oddEvenSwitch` will stay at 3. Only
    * when we publish an even number to `parent` (say, 2), the value `oddEventSwitch` will change. And from now on it will
    * stay like that until we publish an odd number to the parent.
    *
    * @param select A function mapping from the current value of the original signal to another value which will be used
    *               for checking if the new signal should update.
    * @tparam Z The type of the value returned by the `select` function.
    * @return A new signal of the same value type as this one, which updates only if the `select` function gives different
    *         results for the old and the new value of the parent signal.
    */
  final def onPartialUpdate[Z](select: V => Z): Signal[V] = new PartialUpdateSignal[V, Z](this)(select)

  /** @todo This is an old comment to this method. Consider writing the same in a simpler way.
    *
    * If this signal is computed from sources that change their value via a side effect (such as signals) and is not
    * informed of those changes while unwired (e.g. because this signal removes itself from the sources' children
    * lists in #onUnwire), it is mandatory to update/recompute this signal's value from the sources in #onWire, since
    * a dispatch always happens after #onWire. This is true even if the source values themselves did not change, for the
    * recomputation in itself may rely on side effects.
    *
    * This also implies that a signal should never #dispatch in #onWire because that will happen anyway immediately
    * afterwards in #subscribe.
    */
  protected def onWire(): Unit = {}

  protected def onUnwire(): Unit = {}

  /** Registers a subscriber in a specified execution context and returns the subscription. An optional event context can also
    * be provided by the user for managing the subscription instead of doing it manually. When the value of the signal changes,
    * the subscriber function will be called in the given execution context instead of the one of the publisher.
    *
    * @see [[EventSource]]
    *
    * @param ec An `ExecutionContext` in which the [[Subscription.Subscriber]] function will be executed.
    * @param subscriber [[Subscription.Subscriber]] - a function which is called initially, when registered in the signal,
    *                   and then every time the value of the signal changes.
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the signal and the [[Subscription.Subscriber]]
    */
  override def on(ec: ExecutionContext)(subscriber: Subscriber[V])(implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(new SignalSubscription[V](this, subscriber, Some(ec))(WeakReference(eventContext)))(_.enable())

  /** Registers a subscriber which will always be called in the same execution context in which the value of the signal was changed.
    * An optional event context can be provided by the user for managing the subscription instead of doing it manually.
    *
    * @see [[EventSource]]
    * @param subscriber [[Subscription.Subscriber]] - a function which is called initially, when registered in the signal,
    *                   and then every time the value of the signal changes.
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the signal and the [[Subscription.Subscriber]]
    */
  override def apply(subscriber: Subscriber[V])(implicit eventContext: EventContext = EventContext.Global): Subscription =
    returning(new SignalSubscription[V](this, subscriber, None)(WeakReference(eventContext)))(_.enable())

  /** Sets the value of the signal to the given value. Notifies the subscribers if the value actually changes.
    *
    * @param value The new value of the signal.
    */
  protected def publish(value: V): Unit = set(Some(value))

  /** Sets the value of the signal to the given value. Notifies the subscribers if the value actually changes.
    * if the subscription specify the execution context, that execution context will be used to execute the subscriber
    * function, and only if not, the context given in the `publish` method will be used.
    *
    * @param value The new value of the signal.
    * @param currentContext The execution context that will be used to call the subscriber function if the subscription
    *                       does not say otherwise.
    */
  protected def publish(value: V, currentContext: ExecutionContext): Unit = set(Some(value), Some(currentContext))
}

/** By default, a new signal is initialized lazily, i.e. only when the first subscriber function is registered in it.
  * You can decorate it with `NoAutowiring` to enforce initialization.
  *
  * @see [[Subscribable]]
  */
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
  // @todo shouldn't this be in an overridden `onWire`?
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

final private[signals] class EventStreamSignal[V](source: EventStream[V], v: Option[V] = None) extends Signal[V](v) {
  private[this] lazy val subscription = source(publish)(EventContext.Global)

  override protected def onWire(): Unit = subscription.enable()
  override protected def onUnwire(): Unit = subscription.disable()
}