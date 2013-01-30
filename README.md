# Go-lightly

## Overview

Go-lightly is a Clojure library that facilitates building concurrent programs in Clojure in the style built into the Go language.  Go concurrency is based on the [Communicating Sequential Processes](http://en.wikipedia.org/wiki/Communicating_sequential_processes) (CSP) model of programming.  

CSP addresses concurrency interaction patterns - how separate processes, threads or routines communicate and coordinate with each other via **message passing**. A CSP language or library is intended to provided constructs that reduce the complexity of inter-process/inter-thread communication using primitives that are easy to use and reason about. This means not having to be a deep expert in a system's memory model in order to do concurrent programming. Instead, it hides semaphores, mutexes, barriers and other low level concurrency constructs in higher-level abstractions.

The core constructs of the Go concurrency programming are:

1. Go routines
2. Synchronous (blocking) channels
3. Bounded, mostly asynchronous (non-blocking) channels
4. A `select` operator that reads the next available message from multiple channels
5. Timeout operations on channel read/writes/selects

The go-lightly library provides all of these (plus a few extras) by wrapping features already provided in the JVM and Clojure.

In this overview, I introduce these Go concepts using Go code examples and terminology and then show how to use the go-lightly library to program with the concepts in Clojure.


### Compatibility Notes, Minimal Requirements and Dependencies

Go-lightly only works with Java 7 (and later) in order to use the java.util.concurrent.LinkedTransferQueue, which was added in Java 7.  See the [synchronous channels section below](#syncchan) for details on why this concurrent queue was chosen to implement Go synchronous channels.

Go-lightly has been so far tested with Clojure 1.4.  I intend to ensure it is compatible with Clojure 1.3, 1.4 and 1.5, as long as Java 7 is provided.

The core go-lightly library has no dependencies beyond Clojure and Java 7.  However, some of the example code requires Zach Tellman's lamina library, since I played with ways to emulate some Go-concurrency programming features using lamina.


## Getting Started

You can get an overview of all features of the go-lightly library from the "[learn from the REPL](https://github.com/midpeter444/go-lightly/wiki/Tutorial:-Learn-go%E2%88%92lightly-at-the-REPL)" section of the wiki.

The rest of [the wiki](https://github.com/midpeter444/go-lightly/wiki) (still in progress) covers additional notes and details not covered in the above tutorial.


## Updates

0.3.1 published on 28-Jan-2013.  It adds:

* a `selectf` function, which is a select control structure modeled after the select from Go.
* a load-balancer example (in clj-examples) that implements Pike's load-balancer exmaple in Go and shows why the `selectf` function is a necessary concept
* a `gox` macro that acts like the go macro, but wraps everything in a try/catch that:
  * ignores InterruptedException, allowing you to call (stop) on infinite go routines without any error printing to the screen
  * catching any other Exception and printing to stdout, since exceptions thrown in a Clojure future get swallowed and make it hard to debug during development
  * it is expected that you will using `gox` while developing and then change it to `go` for general availability/production, but you can stick with `gox` for production code if that suits you


## TODO

There is a race condition in the current implementation of select and selectf that needs to be handled correctly.


# Documentation

## Go routines

Go routines in Go can be thought of as spawning a process in the background, as you do with the `&` symbol on a Unix/Linux command line.  But in Go you are not spawning a process or even a thread.  You are spawning a routine or piece of control flow that will get multiplexed onto threads in order to run.  Thus, go routines are lighter weight than threads.  You can have more Go routines running than the possible number of threads supported in a process by your OS or runtime.

To create a Go routine you simply invoke a function call with `go` in front of it and it will run that function in another thread.  In this aspect, Go's `go` is very similar to Clojure's `future` function.

```go
func SayHello(name) {
  time.Sleep(2 * time.Second)
  print "Hello: " + name + "\n";
}
go SayHello("Fred");  // returns immediately
// two seconds later prints "Hello Fred"
```

```clj
;; the Clojure version of the above (almost)
(defn say-hello [name]  
  (Thread/sleep 2000)
  (println "Hello:" name))
(future (say-hello "Fred"))
```

Like `go`, Clojure's `future` runs the function given to it in another thread.  However, `future` differs from Go routines in three important ways:

1. `future` pushes a function to be executed onto a thread from the Clojure agent thread pool where it remains until the future is finished.  Clojure futures are not a lightweight multiplexed routine.  This is a limitation of the JVM environment and as far as I know there is not a way to emulate this aspect of Go routines on the JVM.  (If any knows differently, please let me know!)

2. The thread used by `future` is not a daemon-thread, where as Go routines are daemons.  In Go, when the main thread (as defined by the thread running through the `main` function) ends, any Go routines still running are immediately shut down.  Thus, you can spawn a Go routine that never shuts down or is hung in a blocking operation and the program will still exit gracefully.  This not true of Clojure future threads.  The go-lightly library provides some utilities to assist with this.

3. `future` returns a Future whereas `go` returns nothing.  If you need to wait on a go routine to finish or deliver some value, you instead use a channel for communication (or, set some shared state for other thread to check, which is *not* idiomatic).  With a Future you can wait on it to finish and return a value to you directly.

The go-lightly library provides a `go` macro that internally invokes `future`, but ignores the Future that it returns.

When designing your concurrent programs in Clojure, think about whether you want to get the return value of the future and use that to coordinate threads.  If so, then you don't need go-lightly.  But if you want to spawn up "go routines" that will communicate via channels and treat those go routines like daemon threads, go-lightly facilitates that and makes it easy to do.  See [the wiki](https://github.com/midpeter444/go-lightly/wiki) for detailed examples.


## Synchronous Blocking Channels

In Go, when you create a channel with no arguments, you get a synchronous blocking channel:

```go
// Go version
// returns a synchronous channel that takes int values
ch := make(chan int)
```

```clj
;; Clojure version
;; Clojure channels are not typed - any value can be placed on it
(def ch (go/channel))
```

Puts and takes, or "sends" and "receives" in Go's parlance, are done with the left arrow operator: `<-`. Any send on the channel will block until a receive is done by another thread:

```go
// Go version
// blocks until value is received
ch <- 42
```

```clj
;; Clojure version
(go/put ch 42)
```

Likewise, any receive on the channel blocks until a send is done by another thread:

```go
// Go version
// blocks until value is sent to the channel
myval := <-ch
```

```clj
// Clojure version
(let [myval (go/take ch)]
  ;; do something with myval
  )
```
In Go parlance, synchronous blocking channels are simply called "channels", while (mostly) non-blocking asynchronous channels are called "buffered channels", so I will use those terms from here forward.

The [java.util.concurrent package](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/package-frame.html) includes a number of very nice concurrent queues, which are a superset of Go channels.  In particular, [SynchronousQueue](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/SynchronousQueue.html) and the newly introduced [LinkedTransferQueue](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/LinkedTransferQueue.html) can be used like Go channels.  It is worth emphasizing that they also have functionality beyond what Go channels provide.  The Go channels were intentionally designed to be minimally featured - they are constricted to be used in particular ways that facilitate the Go style of concurrent programming.  The go-lightly library similarly simplifies Java's TransferQueue to a minimal set of supported operations.  However, if you really need it, you can always [grab the embedded TransferQueue](#need-wiki-link) out of the go-lightly channel and work with it directly through Clojure's Java interop features.

*Side Note*: While Java's SynchronousQueue is nearly a drop in replacement for a Go channel, I chose to use LinkedTransferQueue instead because `peek` on SynchronousQueue always returns null, where on a TransferQueue it will return the value of a waiting transfer. In order to implement select I needed to be able to peek at the queue to see if anything is ready for reading.

### Using Channels

Because they block, channels are useful only in a multi-threaded environment.  Like a [CountDownLatch](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/CountDownLatch.html) or a [CyclicBarrier](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/CyclicBarrier.html) in Java, they are used to synchronize two threads with each other, one waiting until the other one is ready to send a signal.  Channels also have the advantage of being able to send messages.  They can say more than "I'm done" or "I'm ready"; they can deliver data to their recipient, such as the end result of a calculation or instructions on the next task to do.

You can also think of channels like Unix pipes, but channels can be used bidirectionally, though this is tricky and can lead to race conditions.  To illustrate: suppose we have a channel shared between two threads.  Thread A is the master and Thread B is the worker. Thread A sends instructions on what to do next and Thread B reports back when done with the result.  A simplification of their exchange could look like:

```go
// thread A
ch <- ":taskA"
taskA_result := <-ch
ch <- ":taskB"
taskB_result := <-ch

// thread B (in a different func)
instructionsA := <-ch
// .. do some work
ch <- resultA
instructionsB := <-ch
// .. do some work
ch <- resultA
```

This is not going to work. Due to a race to read from the channel, thread A is sometimes going to consume it's own instruction message and thread B is going to hang forever.  And if by chance thread A doesn't consume it's own messages, thread B is likely to consume its own result messages.

A better solution is to use two channels and treat each one as unidirectional.  [The wiki](https://github.com/midpeter444/go-lightly/wiki) has examples of doing this.

A key consideration when using channels is that you have to be careful to not code yourself into a deadlock.  This is particularly true in Clojure.  Go has nice built-in deadlock detection that issues a panic to tell you that all threads are deadlocked.  Java threads, and therefore Clojure threads, just go into la-la land never to be heard from again.  You'd have to get a thread dump to see what's happened.  Thread dumps are your friend when doing concurrent programming with blocking communications on the JVM.

Happily, there are ways to timeout and not block forever.  We will look at those when we get to the `select` feature.

### go-lightly Channel abstraction

Finally, the go-lightly library has built a formal abstraction: the GoChannel protocol. 

```clj
(defprotocol GoChannel
  (put [this val] "Put a value on a channel. May or may not block depending on type and circumstances.")
  (take [this] "Take the first value from a channel. May or may not block depending on type and circumstances.")
  (size [this] "Returns the number of values on the channel")
  (peek [this] "Retrieve, but don't remove, the first element on the channel. Never blocks.")
  (clear [this] "Remove all elements from the channel without returning them"))
```

There are other functions for channels not part of the GoChannel abstraction that will discussed below and in [the wiki](https://github.com/midpeter444/go-lightly/wiki).

The specific GoChannel implementation type for Go channels is called Channel.


## Buffered Channels 

Buffered channels are bounded, asynchronous and mostly non-blocking. Puts (sends) will block only if the buffered channel is full and takes (receives) will block only if the channel is empty.

Buffered channels are thread-safe - multiple threads can be reading and writing from them at the same time.  As with channels, reads on buffered channels consume values, so only one thread can get any given value.  Java has a number of concurrent queues that match the Go buffered channel functionality.  The go-lightly library buffered channel wraps the [LinkedBlockingQueue](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/LinkedBlockingQueue.).

To create a buffered channel, you simply pass a numeric argument specifying the max capacity of the channel:

```go
// Go version
ch := make(chan int, 128)
```

```clj
;; Clojure version
(def ch (go/channel 128))
```

You use buffered channels whenever one or more threads needs to be pushing a stream of messages for one or more consumers to read and process on the other end.


## select

Go comes with a ready-made control structure called select. It provides a shorthand way to specify how to deal with multiple channels, as well as allow for timeouts and non-blocking behavior (via a "default" clause). It looks like a switch/case statement in Go, but is different in that all paths involving a channel are evaluated, rather than just picking the first one that is ready.

The select statement is a central element in writing idiomatic Go concurrent applications.  In fact, Rob Pike, in his [Google IO 2012 presentation](http://www.youtube.com/watch?v=f6kdp27TYZs&feature=youtu.be)) on Go said:

> "The select statement is a key part of why concurrency is built into Go as features of the language, rather than just a library. It's hard to do control structures that depend on libraries."

Let's look at an example:

```go
select {
case v1 := <-c1:
    fmt.Printf("received %v from c1\n", v1)
case v2 := <-c2:
    fmt.Printf("received %v from c2\n", v2)
}
```

This `select` evaluates two channels and there are four possible scenarios:

1. c1 is ready to give a message, but c2 is not. The message from c1 is read into the variable v1 and the code clause for that first case is executed.
2. c2 is ready to give a message, but c1 is not. v2 then is assigned to the value read from c2 and its code clause is executed.
3. Both c1 and c2 are ready to give a message. One of them is randomly chosen for reading and execution of its code block. Note this means that you cannot depend on the order your clauses will be executed in.
4. Neither c1 nor c2 are ready to give a message. The select will block until the first one is ready, at which point it will be read from the channel and execute the corresponding code clause.

`select` statements in Go can also have timeouts or a default clause that executes immediately if none of the other cases are ready.  Examples are provided in [the wiki](https://github.com/midpeter444/go-lightly/wiki).

### select in go-lightly

The go-lightly library provides select-like functionality, but not exactly like Go does.  At present go-lightly's select is a function (or rather a suite of functions), not a control structure.  However, a control structure version could certainly be written if it is warranted.

The select function is go-lightly is modeled after the `sync` function in the [Racket language](http://racket-lang.org/) (discussed in [one of my go-lightly blog entries](http://thornydev.blogspot.com/2013/01/go-concurrency-constructs-in-clojure2.html) in more detail).  You pass in one or more channels and/or buffered channels and it performs the same logic outlined above to return the value from the next channel when it is available.

```clj
(def ch (go/channel))
(def buf-ch1 (go/channel 100))
(def buf-ch2 (go/channel Integer/MAX_VALUE))

(let [msg (go/select ch buf-ch1 buf-ch1)]
  ;; do something with first message result here
  )
```

If you don't want to block until a channel has a value, use `select-nowait`, which takes an optional return sentinel value if no channel is ready for reading:

```clj
user=> (select-nowait ch1 ch2 ch3 :bupkis)
:bupkis
```

## Timeout operations on channel read/writes/selects

In Go, timeouts are done with a timeout channel, which is returned by a call to `time.After`. You can use a timeout channel to timeout an individual select:

```go
select {
case m := <-ch:
    handle(m)
case <-time.After(1 * time.Minute):
    fmt.Println("timed out")
}
```

Or you can use one to timeout a series of selects in a loop:

```go
timeout := time.After(1 * time.Second)
for {
    select {
    case s := <-c:
        fmt.Println(s)
    case <-timeout:
        fmt.Println("timed out")
        return
    }
}
```

### timeouts in go-lightly

The second Go timeout example above uses an explicit return statement that is not compatible with Clojure or a functional style of program, so go-lightly provides other idioms.  If you want to block with a timeout, you have three options:

1. use `select-timeout`, which takes the timeout duration (in millis) as the first arg:

```clj
(go/select-timeout 1000 ch1 ch2 ch3)
```

2. use a TimeoutChannel, which is a channel that will have a sentinel timeout value after the prescribed number of milliseconds
   
```clj   
(go/select ch1 ch2 ch3 (go/timeout-channel 1000))
```

3. use the `with-timeout` macro to wrap the whole operation and quit if it hasn't finished before then

```clj
(go/with-timeout 1000
  (let [msg (go/select ch1 ch2 ch3)]
    ;; do something with first message result here
    ))
```

The usage scenarios around these three options are discussed [in the wiki](https://github.com/midpeter444/go-lightly/wiki).



## A note on namespaces

The GoChannel protocol defines two methods, `take` and `peek`, that conflict with function names in clojure.core.  I considered for quite a while what to call the "send" and "receive" and "look-without-taking" operations.  I decided against using `send` because that is the core operation on agents, which are a primary concurrency tool in Clojure, and I decided that would engender worse confusion.  I also could have put a star after all the function names like Zach Tellman does in the [lamina library](https://github.com/ztellman/lamina), but decided against it as well.

So to handle the name conflict, you have two options.  If you want to use or refer the entire go-lightly.core namespace into your namespace, you'll need to tell Clojure not to load clojure.core/take and clojure.core/peek:

```clj
(ns my.namespace
  (:refer-clojure :exclude [peek take])
  (:require [thornydev.go-lightly.core :refer :all]))
```      
      
Or you can simply use a namespace prefix to all the go-lightly.core functions:

```clj
(ns thornydev.go-lightly.examples.webcrawler.webcrawler
  (:require [thornydev.go-lightly.core :as go]))

(defn stop-frequency-reducer []
  (go/with-timeout 2000
    (let [back-channel (go/channel)]
      (go/put freq-reducer-status-channel {:msg :stop
                                           :channel back-channel})
      (go/take back-channel))))
```

The latter is the option I recommend and show in my examples.


## Usage

The go-lightly library is composed of one file: the thornydev.go-lightly.core namespace that defines helper macros and functions.  There is a test for it in the usual spot (using lein project structure).

In addition, I have provided a number of usage examples that I assembled or wrote while thinking about how to develop this library.

[The wiki](https://github.com/midpeter444/go-lightly/wiki) (**under construction**) will walk through detailed examples for go-lightly itself.

There are basically 4 categories of examples you'll see as you peruse the examples:

1. Examples in Go in the go-examples directory from Rob Pike and golang website.  See the README in the go-examples directory on how to set up to run them.
2. Examples in Clojure the clj-examples directory include:
  1. Examples using Java's SynchronousQueue, TransferQueue and LinkedBlockingQueue as Go channels
  2. Examples using the Clojure [lamina](https://github.com/ztellman/lamina) library as Go channels
    * Some of these are taken from gists done by Alexey Kachayev in thinking about how to go CSP Go-style programming in Clojure
  3. Examples using the go-lightly library

Each example can be loaded up and run in the REPL.

Because I want to make sure all of these will run and end gracefully (not hang), I also set up a massive case statement in the `thornydev.go-lightly.examples.run-examples/-main` method to run any of these via `lein run`.  Most can run be run with other targets, but some cannot since they take additional arguments.  See the run-examples.clj file for details.

Example:

    $ lein run :gen-amp :gen-lam1 :google-3-alpha

will run all three of those targets sequentially.


## Resources

The [go-lightly wiki](https://github.com/midpeter444/go-lightly/wiki) **(under construction)**

While developing the library, I did some "thinking out loud" in a set of blog posts:

* Part 1: [Go Concurrency Constructs in Clojure](http://thornydev.blogspot.com/2013/01/go-concurrency-constructs-in-clojure.html)
* Part 2: [Go Concurrency Constructs in Clojure: select](http://thornydev.blogspot.com/2013/01/go-concurrency-constructs-in-clojure2.html)
* Part 3: [Go Concurrency Constructs in Clojure: why go-lightly?](http://thornydev.blogspot.com/2013/01/go-concurrency-constructs-in-clojure3.html)
* Part 4: [Go Concurrency Constructs in Clojure: idioms and tradeoffs](http://thornydev.blogspot.com/2013/01/go-concurrency-constructs-in-clojure4.html)

#### Talks by Rob Pike:
* [Google I/O 2012 - Go Concurrency Patterns](http://www.youtube.com/watch?v=f6kdp27TYZs&feature=youtu.be)
* [Concurrency is not Parallelism](http://vimeo.com/49718712) ([slides here](https://rspace.googlecode.com/hg/slide/concur.html#landing-slide))
* [Google I/O 2010 - Load Balancer Example](https://www.youtube.com/watch?v=jgVhBThJdXc)


## License

Copyright © 2012 Michael Peterson

Some of the example code in the go-examples directory is copyright Rob Pike or and some in clj-examples is copyright [Alexey Kachayev](https://github.com/kachayev).

Distributed under the Eclipse Public License, the same as Clojure.
