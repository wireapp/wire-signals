package utils

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object returning {
  def apply[A](init: A)(effects: A => Unit): A = macro KestrelMacro.apply[A]
}

object returningF { // fallback for the very rare case where macro expansion results in a compiler crash
  def apply[A](a: A)(body: A => Unit): A = { body(a); a }
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
