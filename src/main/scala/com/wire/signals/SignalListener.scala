package com.wire.signals

import scala.concurrent.ExecutionContext

private[signals] trait SignalListener {
  // 'currentContext' is the context this method IS run in, NOT the context any subsequent methods SHOULD run in
  def changed(currentContext: Option[ExecutionContext]): Unit
}

private[signals] object SignalListener {
  def apply(): SignalListener = new DoNothingSignalListener()

  final class DoNothingSignalListener extends SignalListener {
    override def changed(currentContext: Option[ExecutionContext]): Unit = ()
  }
}