package com.wire.signals

import scala.concurrent.ExecutionContext

class SourceSignal[A](v: Option[A] = None) extends Signal(v) {
  def !(value: A): Unit = publish(value)

  override def publish(value: A, currentContext: ExecutionContext): Unit = super.publish(value, currentContext)

  def mutate(f: A => A): Boolean = update(_.map(f))

  def mutate(f: A => A, currentContext: ExecutionContext): Boolean = update(_.map(f), Some(currentContext))

  def mutateOrDefault(f: A => A, default: A): Boolean = update(_.map(f).orElse(Some(default)))
}
