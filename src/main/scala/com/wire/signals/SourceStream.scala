package com.wire.signals

import scala.concurrent.ExecutionContext

class SourceStream[E] extends EventStream[E] {
  def !(event: E): Unit = publish(event)
  override def publish(event: E): Unit = dispatch(event, None)
  def publish(event: E, ec: ExecutionContext): Unit = dispatch(event, Some(ec))
}
