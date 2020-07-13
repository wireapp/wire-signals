package com.wire.signals

import scala.concurrent.ExecutionContext

private[signals] trait EventListener[E] {
  // 'currentContext' is the context this method IS run in, NOT the context any subsequent methods SHOULD run in
  protected[signals] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit
}
