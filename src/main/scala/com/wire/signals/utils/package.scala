package com.wire.signals

package object utils {
  @inline private[signals] def returning[A](a: A)(body: A => Unit): A = {
    body(a)
    a
  }
}
