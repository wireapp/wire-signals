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

/**
  *
  */
trait EventContext {
  def onContextStart(): Unit
  def onContextStop(): Unit
  def onContextDestroy(): Unit
  def register(observer: Subscription): Boolean
  def unregister(observer: Subscription): Unit
  def isContextStarted: Boolean
  def isContextDestroyed: Boolean
}

class EventContextImpl extends EventContext {

  private object lock

  private[this] var started = false
  private[this] var destroyed = false
  private[this] var observers = Set.empty[Subscription]

  override protected def finalize(): Unit = {
    lock.synchronized {
      if (!destroyed) onContextDestroy()
    }
    super.finalize()
  }

  override def onContextStart(): Unit = lock.synchronized {
    if (!started) {
      started = true
      observers.foreach(_.subscribe())
    }
  }

  override def onContextStop(): Unit = lock.synchronized {
    if (started) {
      started = false
      observers.foreach(_.unsubscribe())
    }
  }

  override def onContextDestroy(): Unit = lock.synchronized {
    destroyed = true
    val observersToDestroy = observers
    observers = Set.empty
    observersToDestroy.foreach(_.destroy())
  }

  override def register(observer: Subscription): Boolean = lock.synchronized {
    if (!destroyed && !observers.contains(observer)) {
      observers += observer
      if (started) observer.subscribe()
      true
    } else false
  }

  override def unregister(observer: Subscription): Unit = lock.synchronized(observers -= observer)

  override def isContextStarted: Boolean = lock.synchronized(started && !destroyed)

  override def isContextDestroyed: Boolean = lock.synchronized(destroyed)
}

object EventContext {
  def apply(): EventContext = new EventContextImpl

  object Implicits {
    implicit val global: EventContext = EventContext.Global
  }

  final object Global extends EventContext {
    override def register(observer: Subscription): Boolean = true
    override def unregister(observer: Subscription): Unit = {}
    override def onContextStart(): Unit = {}
    override def onContextStop(): Unit = {}
    override def onContextDestroy(): Unit = {}
    override def isContextStarted: Boolean = true
    override def isContextDestroyed: Boolean = false
  }
}
