# Wire Signals
#### or Yet Another Reactive Library for Scala

[Go straight to Scaladocs](https://wireapp.github.io/wire-signals/api/com/wire/signals/index.html)

About two thirds of Wire Android code is written is Scala making it unique among Android apps - most of them being implemented in Java and/or Kotlin. Wire is a messenger and as such it must be very responsive: it has to quickly react to any events coming from the backend, as well as from the user, and from the Android OS itself. For the last three years the Android team developed its own implementation of event streams and "signals" - special event streams with caches holding the last propagated value. They proved to be a very flexible and concise way of handling events all over the Scala code in Wire.

This project is an attempt to migrate all that functionality into a separate library. There is still a lot of work to be done before we can call it Wire Signals version 1.0, but the code is already mature and used extensively in our application, from [fetching and decoding data from another device](https://github.com/wireapp/wire-android-sync-engine/blob/develop/zmessaging/src/main/scala/com/waz/service/push/PushService.scala) to [updating the list of messages displayed in a conversation](https://github.com/wireapp/wire-android/blob/develop/app/src/main/scala/com/waz/zclient/messages/MessagesController.scala).

#### How to use
To include `wire-signals` in your project, add this to your library dependencies in sbt:
```
libraryDependencies += "com.wire" %% "wire-signals" % "0.3.1"
```
Currently `wire-signals` work with Scala 2.11 (because Android), 2.12, and 2.13.

In short, you can create a `SourceSignal` somewhere in the code:
```
val intSignal = Signal(1) // SourceSignal[Int] with the initial value 1
val strSignal = Signal[String]() // initially empty SourceSignal[String]
```

and subscribe it in another place:
```
intSignal.foreach { number => println("number: $number") }
strSignal.foreach { str => println("str: $str") }
```

Now every time you publish something to the signals, the functions you provided above will be executed, just as in case of a regular event stream...
```
scala> intSignal ! 2
number: 2
```
... but if you happen to subscribe to a signal after an event was published, the subscriber will still have access to that event. On the moment of subscription the provided function will be executed with the last event in the signal if there is one. So at this point in the example subscribing to `intSignal` will result in the number being displayed:
```
> intSignal.foreach { number => println("number: $number") }
number: 2
```
but subscribing to `strSignal` will not display anything, because `strSignal` is still empty. Or, if you simply don't need that functionality, you can use a standard `EventStream` instead.

You can also of course `map` and `flatMap` signals, `zip` them, `throttle`, `fold`, or make any future or an event stream into one. With a bit of Scala magic you can even do for-comprehensions:
```
val fooSignal = for {
 number <- intSignal
 str    <- if (number % 3 == 0) Signal.const("Foo") else strSignal
} yield str
```

If you want to know more about how we use it:
* here's an [Overview](https://github.com/wireapp/wire-signals/wiki/Overview)
* [Scala Docs](https://wire.engineering/wire-signals/api/com/wire/signals/index.html)
* and a slightly outdated video about how we use it at Wire Android: [Scala on Wire](https://www.youtube.com/watch?v=dnsyd-h5piI)

(more info coming soon)

---
[editor on GitHub](https://github.com/wireapp/wire-signals/edit/gh-pages/index.md)
