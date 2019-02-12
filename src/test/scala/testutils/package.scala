import utils._
import signals.{EventContext, Signal, Subscription}

package object testutils {
  implicit class SignalToSink[A](val signal: Signal[A]) extends AnyVal {
    def sink: SignalSink[A] = returning(new SignalSink[A])(_.subscribe(signal)(EventContext.Global))
  }

  class SignalSink[A] {
    @volatile private var sub = Option.empty[Subscription]
    def subscribe(s: Signal[A])(implicit ctx: EventContext): Unit = sub = Some(s(v => value = Some(v)))
    def unsubscribe: Unit = sub.foreach { s =>
      s.destroy()
      sub = None
    }
    @volatile private[testutils] var value = Option.empty[A]
    def current: Option[A] = value
  }
}
