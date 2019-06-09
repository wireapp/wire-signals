package com.wire.signals.utils

import java.security.SecureRandom

object ZSecureRandom {
  lazy val random = new SecureRandom()

  def nextInt(): Int = random.nextInt
  def nextInt(bounds: Int): Int = random.nextInt(bounds)
  def nextInt(min: Int, max: Int): Int = random.nextInt((max - min) + 1) + min

  def nextLong(): Long = random.nextLong()
  def nextDouble(): Double = random.nextDouble()
  def nextFloat(): Double = random.nextFloat()
  def nextBytes(bytes: Array[Byte]): Unit = random.nextBytes(bytes)
}
