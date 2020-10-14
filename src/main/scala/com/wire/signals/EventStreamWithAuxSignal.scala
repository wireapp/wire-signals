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

import scala.concurrent.ExecutionContext

object EventStreamWithAuxSignal {
  def apply[A, B](source: EventStream[A], aux: Signal[B]): EventStreamWithAuxSignal[A, B] = new EventStreamWithAuxSignal(source, aux)
}

/** An event stream coupled with an auxiliary signal.
  * You can use it if you want to repeat some computations based on the current value of the signal every time when an event
  * is published in the source stream.
  * ```
  * val aux = Signal[Int]()
  * val source = EventStream[Unit]()
  *
  * val newStream = EventStreamWithAuxSignal(source, aux)
  * newStream.foreach { case (_, Option(n)) => /* ... */ }
  * ```
  * Here, `newStream` extends `EventStream[Unit, Option[Int]]`.
  * The subscriber function registered in `newStream`` will be called every time a new unit event is published in `source`
  * and it will receive a tuple of the event and the current value of `aux`: `Some[Int]` if something was already published
  * in the signal, or `None` if it is not initialized yet.
  *
  * @todo Consider moving this class to the extensions project.
  *
  * @param source The source event stream used to trigger events in this event stream. Every event of type `A` published in `source`
  *               will become the first part of the tuple published in this stream.
  * @param aux An auxiliary signal of values of the type `B`. Every time a new event is published in `source`, this stream will
  *            access the signal for its current value. The value (or lack of it) will become the second part of the tuple published
  *            in this stream.
  * @tparam A The type of events in the source stream.
  * @tparam B The type of values in the auxiliary signal.
  */
class EventStreamWithAuxSignal[A, B](source: EventStream[A], aux: Signal[B]) extends EventStream[(A, Option[B])] {
  protected[this] val subscriber: EventSubscriber[A] = new EventSubscriber[A] {
    override protected[signals] def onEvent(event: A, sourceContext: Option[ExecutionContext]): Unit = {
      dispatch((event, aux.currentValue), sourceContext)
    }
  }

  protected[this] val auxSubscriber: SignalSubscriber = SignalSubscriber()

  override protected def onWire(): Unit = {
    source.subscribe(subscriber)
    aux.subscribe(auxSubscriber)
  }

  override protected[signals] def onUnwire(): Unit = {
    source.unsubscribe(subscriber)
    aux.unsubscribe(auxSubscriber)
  }
}

