package com.wire

package object signals {
  @inline private[signals] def returning[A](a: A)(body: A => Unit): A = {
    body(a)
    a
  }
}
