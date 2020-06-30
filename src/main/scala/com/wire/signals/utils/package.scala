package com.wire.signals

package object utils {

  object returning {
    @inline
    final def apply[A](a: A)(body: A => Unit): A = {
      body(a); a
    }
  }
}
