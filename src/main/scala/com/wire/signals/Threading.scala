package com.wire.signals

import com.wire.signals.DispatchQueue.Unlimited
import scala.concurrent.ExecutionContext

/** Use `Threading` to set up the default execution context which will be later used as the parent for other
  * dispatch queues and to run cancellable futures, event streams, and signals, if no other execution context
  * is provided.
  */
object Threading {
  /** An implicit reference to the default execution context. It is lazy, giving you a chance to replace the default
    * context with one of your own choosing before it is used for the first time.
    */
  implicit lazy val defaultContext: ExecutionContext = apply()

  /** A number of CPUs available for executing tasks, but at least two.
    * If there is one CPU available to the Java virtual machine, there's not much you can do about concurrency anyway, can you.
    * You can use it e.g. when creating your own limited dispatch queues where the number of available CPUs is the concurrency limit.
    */
  final val Cpus: Int = math.max(2, Runtime.getRuntime.availableProcessors)

  private var instance = Option.empty[DispatchQueue]
  private lazy val defaultQueue = DispatchQueue(Unlimited, ExecutionContext.global)

  /** The default dispatch queue for Wire Signals is lazily initialized, meaning that at the start of the app,
    * before it is used for the first time, you can provide a dispatch queue of your own to act as the default one.
    * If you won't do it, it will be created at the moment at the moment it's used for the first time.
    * In that case, it will be an unlimited dispatch queue wrapped over `ExecutionContext.global`.
    *
    * Note that it is technically possible to replace the default dispatch queue while the app is already running.
    * @todo Maybe we should disallow it.
    *
    * @see [[ExecutionContext.global]]
    *
    * @param queue - a custom dispatch queue that will serve as the default execution context in cases where no other execution
    *              context is provided and as the parent for all new dispatch queues when their parents are not provided.
    */
  def setAsDefault(queue: DispatchQueue): Unit = {
    instance = Some(queue)
  }

  /**
    * @return the default dispatch queue, either the one provided by the user or an unlimited dispatch queue over ExecutionContext.global,
    *         created at the moment of first use.
    */
  def apply(): DispatchQueue = instance.getOrElse(defaultQueue)
}
