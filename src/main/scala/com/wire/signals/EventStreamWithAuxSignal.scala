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

class EventStreamWithAuxSignal[A, B](source: EventStream[A], aux: Signal[B]) extends EventStream[(A, Option[B])] {
  protected val listener: EventListener[A] = new EventListener[A] {
    override protected[signals] def onEvent(event: A, sourceContext: Option[ExecutionContext]): Unit =
      dispatch((event, aux.currentValue), sourceContext)
  }

  protected val auxListener: SignalListener = SignalListener()

  override protected def onWire(): Unit = {
    source.subscribe(listener)
    aux.subscribe(auxListener)
  }

  override protected[signals] def onUnwire(): Unit = {
    source.unsubscribe(listener)
    aux.unsubscribe(auxListener)
  }
}
