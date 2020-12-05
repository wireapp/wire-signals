/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.wire.signals

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, CyclicBarrier, TimeUnit}

import testutils._

import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._

class SignalSpec extends munit.FunSuite {
  private implicit val defaultContext: DispatchQueue = Threading.defaultContext
  private var received = Seq[Int]()
  private val capture = (value: Int) => received = received :+ value

  private val eventContext = EventContext()

  override def beforeEach(context: BeforeEach): Unit = {
    received = Seq[Int]()
    eventContext.start()
  }

  override def afterEach(context: AfterEach): Unit = {
    eventContext.stop()
  }

  test("Receive initial value") {
    val s = Signal(1)
    s.foreach(capture)
    andThen()
    assertEquals(received, Seq(1))
  }

  test("Basic subscriber lifecycle") {
    val s = Signal(1)
    assert(!s.hasSubscribers)
    val sub = s.foreach { _ => () }
    assert(s.hasSubscribers)
    sub.destroy()
    assert(!s.hasSubscribers)
  }

  test("Don't receive events after unregistering a single subscriber") {
    val s = Signal(1)
    val sub = s.foreach(capture)
    andThen()
    s ! 2
    andThen()
    assertEquals(received, Seq(1, 2))

    sub.destroy()
    s ! 3
    andThen()
    assertEquals(received, Seq(1, 2))
  }

  test("Don't receive events after unregistering all subscribers") {
    val s = Signal(1)
    s.foreach(capture)
    andThen()
    s ! 2
    andThen()
    assertEquals(received, Seq(1, 2))

    s.unsubscribeAll()
    s ! 3
    andThen()
    assertEquals(received, Seq(1, 2))
  }

  test("Signal mutation") {
    val s = Signal(42)
    s.foreach(capture)
    andThen()
    assertEquals(received, Seq(42))
    s.mutate(_ + 1)
    andThen()
    assertEquals(received, Seq(42, 43))
    s.mutate(_ - 1)
    andThen()
    assertEquals(received, Seq(42, 43, 42))
  }

  test("Don't send the same value twice") {
    val s = Signal(1)
    s.foreach(capture)
    Seq(1, 2, 2, 1).foreach { n =>
      s ! n
      andThen()
    }
    assertEquals(received, Seq(1, 2, 1))
  }

  test("Idempotent signal mutation") {
    val s = Signal(42)
    s.foreach(capture)
    andThen()
    assertEquals(received, Seq(42))
    s.mutate(_ + 1 - 1)
    andThen()
    assertEquals(received, Seq(42))
  }

  test("Simple for comprehension") {
    val s = Signal(0)
    val s1 = Signal.const(1)
    val s2 = Signal.const(2)
    val r = for {
      x <- s
      y <- Seq(s1, s2)(x)
    } yield y * 2
    r.foreach(capture)
    assertEquals(r.currentValue.get, 2)
    s ! 1
    andThen()
    assertEquals(r.currentValue.get, 4)
    assertEquals(received, Seq(2, 4))
  }

  test("Many concurrent subscriber changes") {
    val barrier = new CyclicBarrier(50)
    val num = new AtomicInteger(0)
    val s = Signal(0)

    def add(barrier: CyclicBarrier): Future[Subscription] = Future(blocking {
      barrier.await()
      s { _ => num.incrementAndGet() }
    })

    val subs = Await.result(Future.sequence(Seq.fill(50)(add(barrier))), 10.seconds)
    assert(s.hasSubscribers)
    assertEquals(num.getAndSet(0), 50)

    s ! 42
    assertEquals(num.getAndSet(0), 50)

    val chaosBarrier = new CyclicBarrier(75)
    val removals = Future.traverse(subs.take(25))(sub => Future(blocking {
      chaosBarrier.await()
      sub.destroy()
    }))
    val adding = Future.sequence(Seq.fill(25)(add(chaosBarrier)))
    val sending = Future.traverse((1 to 25).toList)(n => Future(blocking {
      chaosBarrier.await()
      s ! n
    }))

    val moreSubs = Await.result(adding, 10.seconds)
    Await.result(removals, 10.seconds)
    Await.result(sending, 10.seconds)

    assert(num.get <= 75 * 25)
    assert(num.get >= 25 * 25)
    assert(s.hasSubscribers)

    barrier.reset()
    Await.result(Future.traverse(moreSubs ++ subs.drop(25))(sub => Future(blocking {
      barrier.await()
      sub.destroy()
    })), 10.seconds)
    assert(!s.hasSubscribers)
  }

  test("Concurrent updates with incremental values") {
    incrementalUpdates((s, r) => s {
      r.add
    })
  }

  test("Concurrent updates with incremental values with serial dispatch queue") {
    val dispatcher = SerialDispatchQueue()
    incrementalUpdates((s, r) => s.on(dispatcher) {
      r.add
    })
  }

  test("Concurrent updates with incremental values and onChanged subscriber") {
    incrementalUpdates((s, r) => s.onChanged {
      r.add
    })
  }

  test("Concurrent updates with incremental values and onChanged subscriber with serial dispatch queue") {
    val dispatcher = SerialDispatchQueue()
    incrementalUpdates((s, r) => s.onChanged.on(dispatcher) {
      r.add
    })
  }

  private def incrementalUpdates(onUpdate: (Signal[Int], ConcurrentLinkedQueue[Int]) => Unit): Unit = {
    100 times {
      val signal = Signal(0)
      val received = new ConcurrentLinkedQueue[Int]()

      onUpdate(signal, received)

      val send = new AtomicInteger(0)
      val done = new CountDownLatch(10)
      (1 to 10).foreach(_ => Future {
        for (_ <- 1 to 100) {
          val v = send.incrementAndGet()
          signal.mutate(_ max v)
        }
        done.countDown()
      })

      done.await(DefaultTimeout.toMillis, TimeUnit.MILLISECONDS)

      assertEquals(signal.currentValue.get, send.get())

      val arr = received.asScala.toVector
      assertEquals(arr, arr.sorted)
    }
  }

  test("Two concurrent dispatches (global event and background execution contexts)") {
    concurrentDispatches(2, 1000, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (global event and background execution contexts)") {
    concurrentDispatches(10, 200, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Many concurrent dispatches (global event and background execution contexts)") {
    concurrentDispatches(100, 200, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Two concurrent dispatches (subscriber on UI eventcontext)") {
    concurrentDispatches(2, 1000, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (subscriber on UI event context)") {
    concurrentDispatches(10, 200, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Many concurrent dispatches (subscriber on UI event context)") {
    concurrentDispatches(100, 100, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (global event context, no source context)") {
    concurrentDispatches(10, 200, EventContext.Global, None, defaultContext)()
  }

  test("Several concurrent dispatches (subscriber on UI context, no source context)") {
    concurrentDispatches(10, 200, eventContext, None, defaultContext)()
  }

  test("Several concurrent mutations (subscriber on global event context)") {
    concurrentMutations(10, 200, EventContext.Global, defaultContext)()
  }

  test("Several concurrent mutations (subscriber on UI event context)") {
    concurrentMutations(10, 200, eventContext, defaultContext)()
  }

  private def concurrentDispatches(dispatches: Int,
                                   several: Int,
                                   eventContext: EventContext,
                                   dispatchExecutionContext: Option[ExecutionContext],
                                   actualExecutionContext: ExecutionContext
                                  )(subscribe: Signal[Int] => (Int => Unit) => Subscription = s => g => s(g)(eventContext)): Unit =
    concurrentUpdates(dispatches, several, (s, n) => s.set(Some(n), dispatchExecutionContext), actualExecutionContext, subscribe)

  private def concurrentMutations(dispatches: Int,
                                  several: Int,
                                  eventContext: EventContext,
                                  actualExecutionContext: ExecutionContext
                                 )(subscribe: Signal[Int] => (Int => Unit) => Subscription = s => g => s(g)(eventContext)): Unit =
    concurrentUpdates(dispatches, several, (s, n) => s.mutate(_ + n), actualExecutionContext, subscribe, _.currentValue.get == 55)

  private def concurrentUpdates(dispatches: Int,
                                several: Int,
                                f: (SourceSignal[Int], Int) => Unit,
                                actualExecutionContext: ExecutionContext,
                                subscribe: Signal[Int] => (Int => Unit) => Subscription,
                                additionalAssert: Signal[Int] => Boolean = _ => true): Unit =
    several times {
      val signal = Signal(0)

      @volatile var lastSent = 0
      val received = new AtomicInteger(0)
      val p = Promise[Unit]()

      val subscriber = subscribe(signal) { i =>
        lastSent = i
        if (received.incrementAndGet() == dispatches + 1) p.trySuccess({})
      }

      (1 to dispatches).foreach(n => Future(f(signal, n))(actualExecutionContext))

      result(p.future)

      assert(additionalAssert(signal))
      assertEquals(signal.currentValue.get, lastSent)
    }
}
