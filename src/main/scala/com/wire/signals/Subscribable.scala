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

trait Subscribable[Subscriber] {

  private object subscribersMonitor

  private var autowiring = true
  @volatile private[signals] var wired = false
  @volatile private var subscribers = Set.empty[Subscriber]

  protected def onWire(): Unit

  protected def onUnwire(): Unit

  def subscribe(subscriber: Subscriber): Unit = subscribersMonitor.synchronized {
    subscribers += subscriber
    if (!wired) {
      wired = true
      onWire()
    }
  }

  def unsubscribe(subscriber: Subscriber): Unit = subscribersMonitor.synchronized {
    subscribers -= subscriber
    if (wired && autowiring && subscribers.isEmpty) {
      wired = false
      onUnwire()
    }
  }

  def notifySubscribers(call: Subscriber => Unit): Unit = subscribers.foreach(call)

  def hasSubscribers = subscribers.nonEmpty

  def unsubscribeAll(): Unit = subscribersMonitor.synchronized {
    subscribers = Set.empty
    if (wired && autowiring) {
      wired = false
      onUnwire()
    }
  }

  def disableAutowiring(): this.type = subscribersMonitor.synchronized {
    autowiring = false
    if (!wired) {
      wired = true
      onWire()
    }
    this
  }
}
