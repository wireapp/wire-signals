package com.wire.signals

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, ExecutionContext, Future}

package object utils {
  @tailrec
  def compareAndSet[A](ref: AtomicReference[A])(updater: A => A): A = {
    val current = ref.get
    val updated = updater(current)
    if (ref.compareAndSet(current, updated)) updated
    else compareAndSet(ref)(updater)
  }

  def withDelay[T](body: => T, delay: FiniteDuration = 300.millis)(implicit ec: ExecutionContext): CancellableFuture[T] = CancellableFuture.delayed(delay)(body)

  val DefaultTimeout: FiniteDuration = 5.seconds

  def result[A](future: Future[A])(implicit duration: FiniteDuration = DefaultTimeout): A =
    Await.result(future, duration)

  object returning {
    final def apply[A](a: A)(body: A => Unit): A = { body(a); a }
  }
  /*

  import scala.language.experimental.macros
  import scala.reflect.macros.blackbox.Context

  object returning {
    def apply[A](init: A)(effects: A => Unit): A = macro KestrelMacro.apply[A]
  }


  private object KestrelMacro {
    def apply[A](c: Context)(init: c.Tree)(effects: c.Tree) = {
      import c.universe._
      c.untypecheck(effects) match {
        case          Function(List(ValDef(_, t: TermName, _, EmptyTree)), b)  => q"val $t = $init; $b; $t"
        case Block(p, Function(List(ValDef(_, t: TermName, _, EmptyTree)), b)) => q"val $t = $init; $p; $b; $t"
        case _        /*  no inlining possible or necessary */                 => q"val x = $init; $effects(x); x"
      }
    }
  }
  */
}
