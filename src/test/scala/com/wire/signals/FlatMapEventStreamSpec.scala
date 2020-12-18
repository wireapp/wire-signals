package com.wire.signals

import com.wire.signals.testutils.{andThen, awaitAllTasks, withDelay}

class FlatMapEventStreamSpec extends munit.FunSuite {
  private var received = List.empty[Int]
  private val capture = (value: Int) => received :+= value

  override def beforeEach(context: BeforeEach): Unit = {
    received = List.empty[Int]
  }

  test("Normal flatmapping") {
    implicit val dispatchQueue: DispatchQueue = SerialDispatchQueue()

    val switch = EventStream[Boolean]()
    val source1 = EventStream[Int]()
    val source2 = EventStream[Int]()

    val result = switch.flatMap {
      case true  => source1
      case false => source2
    }
    result.foreach(capture)

    assertEquals(received, Nil)
    source1 ! 1
    awaitAllTasks
    assertEquals(received, Nil) // result not set yet
    source2 ! 2
    awaitAllTasks
    assertEquals(received, Nil) // result not set yet

    switch ! true

    source2 ! 2
    awaitAllTasks
    assertEquals(received, Nil) // result set to source1, so events from source2 are ignored
    source1 ! 1
    awaitAllTasks
    assertEquals(received, List(1))  // yay!
    source1 ! 1
    awaitAllTasks
    assertEquals(received, List(1, 1))  // yay!

    switch ! false

    source1 ! 1
    awaitAllTasks
    assertEquals(received, List(1, 1))  // no 3x1 because result now switched to source2
    source2 ! 2
    awaitAllTasks
    assertEquals(received, List(1, 1, 2))  // yay!

    switch ! true

    source2 ! 2
    awaitAllTasks
    assertEquals(received, List(1, 1, 2)) // result switched back to source1, so events from source2 are ignored again
    source1 ! 1
    awaitAllTasks
    assertEquals(received, List(1, 1, 2, 1))  // yay!
  }
}
