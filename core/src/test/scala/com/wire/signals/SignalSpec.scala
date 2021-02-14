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
    implicit val executionContext: ExecutionContext = UnlimitedDispatchQueue()
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
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
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
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(2, 1000, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (global event and background execution contexts)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Many concurrent dispatches (global event and background execution contexts)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(100, 200, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Two concurrent dispatches (subscriber on UI eventcontext)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(2, 1000, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (subscriber on UI event context)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Many concurrent dispatches (subscriber on UI event context)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(100, 100, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (global event context, no source context)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, EventContext.Global, None, defaultContext)()
  }

  test("Several concurrent dispatches (subscriber on UI context, no source context)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, eventContext, None, defaultContext)()
  }

  test("Several concurrent mutations (subscriber on global event context)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    concurrentMutations(10, 200, EventContext.Global, defaultContext)()
  }

  test("Several concurrent mutations (subscriber on UI event context)") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
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

  test("An uninitialized signal should not contain any value") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    val s = Signal.empty[Int]
    assert(!result(s.contains(1)))
  }

  test("An initialized signal should contain the value it's initialized with") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    val s = Signal.const(2)
    assert(!result(s.contains(1)))
    assert(result(s.contains(2)))
  }

  test("The 'exists' check for an uninitialized signal should always return false") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext
    val s = Signal.empty[FiniteDuration]
    assert(!result(s.exists(d => d.toMillis == 5)))
  }

  test("The 'exists' check for an initialized signal should work accordingly") { //
    implicit val defaultContext: DispatchQueue = Threading.defaultContext// I know, stupid name
    val s = Signal.const(5.millis)
    assert(!result(s.exists(d => d.toMillis == 2)))
    assert(result(s.exists(d => d.toMillis == 5)))
  }

  test("After a signal is initialized, onUpdated should return the new value, and no old value") {
    val s = Signal[Int]()
    val p = Promise[(Option[Int], Int)]()

    s.onUpdated { case (oldValue, newValue) => p.success((oldValue, newValue)) }

    s ! 1

    assertEquals(result(p.future), (None, 1))
  }

  test("After the value is changed, onUpdated should return the new value, and the old value") {
    implicit val ec: ExecutionContext = SerialDispatchQueue()
    val s = Signal[Int](0)
    var p = Promise[(Option[Int], Int)]()

    s.onUpdated { case (oldValue, newValue) => p.success((oldValue, newValue)) }

    s ! 1

    assertEquals(result(p.future), (Some(0), 1))
    p = Promise()

    s ! 2

    assertEquals(result(p.future), (Some(1), 2))
  }

  test("After a signal is initialized, onChanged should return the new value") {
    val s = Signal[Int]()
    val p = Promise[Int]()

    s.onChanged { newValue => p.success(newValue) }

    s ! 1

    assertEquals(result(p.future), 1)
  }

  test("After the value is changed, onChanged should return only the new value") {
    implicit val ec: ExecutionContext = SerialDispatchQueue()
    val s = Signal[Int](0)
    var p = Promise[Int]()

    s.onChanged { newValue => p.success(newValue) }

    s ! 1

    assertEquals(result(p.future), 1)
    p = Promise()

    s ! 2

    assertEquals(result(p.future), 2)
  }

  test("After zipping two signals, the zipped signal updates from both sources") {
    val s1 = Signal(0)
    val s2 = Signal("")
    val zipped = Signal.zip(s1, s2)

    assert(waitForResult(zipped, (0, "")))

    s1 ! 1

    assert(waitForResult(zipped, (1, "")))

    s2 ! "a"

    assert(waitForResult(zipped, (1, "a")))
  }

  test("Zip one signal with another and assert that the zipped signal updates from both sources") {
    val s1 = Signal(0)
    val s2 = Signal("")
    val zipped = s1.zip(s2)

    assert(waitForResult(zipped, (0, "")))

    s1 ! 1

    assert(waitForResult(zipped, (1, "")))

    s2 ! "a"

    assert(waitForResult(zipped, (1, "a")))
  }

  test("Map one signal to another") {
    val s1 = Signal(0)
    val mapped = s1.map(n => s"number: $n")

    assert(waitForResult(mapped, "number: 0"))

    s1 ! 1

    assert(waitForResult(mapped, "number: 1"))
  }

  test("A signal mapped from an empty signal stays empty") {
    val s1 = Signal.empty[Int]
    val mapped = s1.map(n => s"number: $n")

    assert(!waitForResult(mapped, "number: 0", 1.second))
  }

  test("filter numbers to even and odd") {
    implicit val dq: DispatchQueue = SerialDispatchQueue()

    val numbers = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

    val source = Signal[Int]()
    val evenNumbers = source.filter(_ % 2 == 0)
    val oddNumbers = source.filter(_ % 2 != 0)

    var evenResults = List[Int]()
    var oddResults = List[Int]()
    val waitForMe = Promise[Unit]()

    def add(n: Int, toEven: Boolean) = {
      if (toEven) evenResults :+= n else oddResults :+= n
      if (evenResults.length + oddResults.length == numbers.length) waitForMe.success(())
    }

    evenNumbers.foreach(add(_, toEven = true))
    oddNumbers.foreach(add(_, toEven = false))

    numbers.foreach(source ! _)

    result(waitForMe.future)

    assertEquals(evenResults, List(2, 4, 6, 8))
    assertEquals(oddResults, List(1, 3, 5, 7, 9))
  }

  test("Call onTrue when a boolean signal becomes true for the first time") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = false
    s.onTrue.foreach { _ => res = true }

    assert(!res)

    s ! false

    assert(waitForResult(s, false))
    assert(!res)

    s ! true

    assert(waitForResult(s, true))
    assert(res)
  }

  test("Don't call onTrue again when a boolean signal becomes true") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = 0
    s.onTrue.foreach { _ => res += 1 }

    assertEquals(res, 0)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 0)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 1)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 1)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 1)
  }

  test("Call onFalse when a boolean signal becomes false for the first time") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = true
    s.onFalse.foreach { _ => res = false }

    assert(res)

    s ! true

    assert(waitForResult(s, true))
    assert(res)

    s ! false

    assert(waitForResult(s, false))
    assert(!res)
  }

  test("Don't call onFalse again when a boolean signal becomes false") {
    implicit val defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = 0
    s.onFalse.foreach { _ => res += 1 }

    assertEquals(res, 0)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 0)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 1)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 1)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 1)
  }


  test("Collect one signal to another") {
    implicit val timeout: FiniteDuration = 500.millis
    val s1 = Signal(0)
    val collected = s1.collect { case n if n % 2 == 0 => s"number: $n" }

    assert(waitForResult(collected, "number: 0"))

    s1 ! 1

    assert(!waitForResult(collected, "number: 1"))

    s1 ! 2

    assert(waitForResult(collected, "number: 2"))

    s1 ! 3

    assert(!waitForResult(collected, "number: 3"))

    s1 ! 4

    assert(waitForResult(collected, "number: 4"))
  }

  test("Combining two signals") {
    val signalA = Signal(1)
    val signalB = Signal(true)
    val chain = signalA.combine(signalB) { case (n, b) => s"$n:$b" }
    assert(waitForResult(chain, "1:true"))

    signalA ! 2

    assert(waitForResult(chain, "2:true"))

    signalB ! false

    assert(waitForResult(chain, "2:false"))

    signalB ! true

    assert(waitForResult(chain, "2:true"))

    signalA ! 42
    signalB ! false

    assert(waitForResult(chain, "42:false"))
  }

  test("Fallback to another signal if the original one is empty") {
    val s1 = Signal[Int]()
    val s2 = Signal[Int](2)

    val res = s1.orElse(s2)

    assert(waitForResult(res, 2))

    s1 ! 1

    assert(waitForResult(res, 1))
  }

  test("Fallback to another signal if the original one switch empty") {
    val s1 = Signal[Boolean](true)
    val s2 = Signal[Int](2)
    val s3 = s1.flatMap {
      case true  => Signal.const(1)
      case false => Signal.empty[Int]
    }

    val res = s3.orElse(s2)

    assert(waitForResult(res, 1))

    s1 ! false

    assert(waitForResult(res, 2))

    s2 ! 3

    assert(waitForResult(res, 3))

    s1 ! true

    assert(waitForResult(res, 1))

    s2 ! 4

    assert(waitForResult(res, 1))
  }

  test("Fallback to a signal of another type if the original one is empty") {
    val s1 = Signal[Int]()
    val s2 = Signal[String]("a")

    val res = s1.either(s2)

    assert(waitForResult(res, Left("a")))

    s1 ! 1

    assert(waitForResult(res, Right(1)))
  }

  test("Fallback to a signal of another type if the original one switch empty") {
    val s1 = Signal[Boolean](true)
    val s2 = Signal[String]("a")
    val s3 = s1.flatMap {
      case true  => Signal.const(1)
      case false => Signal.empty[Int]
    }

    val res = s3.either(s2)

    assert(waitForResult(res, Right(1)))

    s1 ! false

    assert(waitForResult(res, Left("a")))

    s2 ! "b"

    assert(waitForResult(res, Left("b")))

    s1 ! true

    assert(waitForResult(res, Right(1)))

    s2 ! "c"

    assert(waitForResult(res, Right(1)))
  }

  test("Pipe events from one signal to another") {
    val s1 = Signal(1)
    val s2 = Signal[Int]()

    s1.pipeTo(s2)

    assert(waitForResult(s2, 1))

    s2 ! 2

    assert(waitForResult(s2, 2))

    s1 ! 3

    assert(waitForResult(s2, 3))
  }

  test("Pipe events from one signal to another with the operator |") {
    val s1 = Signal(1)
    val s2 = Signal[Int]()

    s1 | s2

    assert(waitForResult(s2, 1))

    s2 ! 2

    assert(waitForResult(s2, 2))

    s1 ! 3

    assert(waitForResult(s2, 3))
  }
}
