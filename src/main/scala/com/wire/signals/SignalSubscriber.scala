package com.wire.signals

import scala.concurrent.ExecutionContext

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