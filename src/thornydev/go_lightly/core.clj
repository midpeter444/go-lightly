(ns thornydev.go-lightly.core
  (:refer-clojure :exclude [peek take])
  (:import (java.io Closeable)
           (java.util ArrayList)
           (java.util.concurrent LinkedTransferQueue TimeUnit
                                 LinkedBlockingQueue TimeoutException)))

;; ---[ go routines ]--- ;;

(def inventory (atom []))

(defmacro go
  "Launches a Clojure future as a 'go-routine' and returns the future.
   It is not necessary to keep a reference to this future, however.
   Instead, you can call the accompanying stop function to
   shutdown (cancel) all futures created by this function."
  [& body]
  `(let [fut# (future ~@body)]
     (swap! inventory conj fut#)
     fut#))

(defn stop
  "Stop (cancel) all futures started via the go macro.
   This should only be called when you are finished with
   all go routines running in your app, ideally at the end
   of the program.  It can be reused on a new set of go
   routines, as long as they were started after this stop
   fn returned, as it clears an cached of remembered go
   routines that could be subject to a race condition."
  []
  (doseq [f @inventory] (future-cancel f))
  (reset! inventory [])
  nil)

(defn shutdown
  "Stop (cancel) all futures started via the go macro and
   then call shutdown-agents to close down the entire Clojure
   agent/future thread pool."
  []
  (stop)
  (shutdown-agents))


(defmacro go&
  "Launch a 'go-routine' like deamon Thread to execute the body.
   This macro does not yield a future so it cannot be dereferenced.
   Instead it returns the Java Thread itself.

   It is intended to be used with channels for communication
   between threads.  This thread is not part of a managed Thread
   pool so cannot be directly shutdown.  It will stop either when
   all non-daemon threads cease or when you stop it some ad-hoc way."
  [& body]
  `(doto (Thread. (fn [] (do ~@body))) (.setDaemon true) (.start)))


;; ---[ channels and channel fn ]--- ;;

(defprotocol GoChannel
  (put [this val] "Put a value on a channel")
  (size [this] "Returns the number of values on the queue"))

(deftype Channel [^LinkedTransferQueue q open? prefer?]
  GoChannel
  (put [this val]
    (if @(.open? this)
      (.transfer q val)
      (throw (IllegalStateException. "Channel is closed. Cannot 'put'."))))
  (size [this] 0)

  Object
  (toString [this]
    (let [stat-str (when-not @(.open? this) ":closed ")]
      (if-let [sq (seq (.toArray q))]
        (str stat-str "<=[ ..." sq "] ")
        (str stat-str "<=[] "))))
  
  Closeable
  (close [this]
    (reset! (.open? this) false)
    nil))

(deftype BufferedChannel [^LinkedBlockingQueue q open? prefer?]
  GoChannel
  (put [this val]
    (if @(.open? this)
      (.put q val)
      (throw (IllegalStateException. "Channel is closed. Cannot 'put'."))))
  (size [this] (.size q))

  Object
  (toString [this]
    (let [stat-str (when-not @(.open? this) ":closed ")]
      (str stat-str "<=[" (apply str (interpose " " (seq (.toArray q)))) "] ")))
  
  Closeable
  (close [this]
    (reset! (.open? this) false)
    nil))

(deftype TimeoutChannel [^LinkedBlockingQueue q open? prefer?]
  GoChannel
  (put [this val] (throw (UnsupportedOperationException.
                          "Cannot put values onto a TimeoutChannel")))
  (size [this] (.size q))

  Object
  (toString [this]
    (if (closed? this)
      ":closed <=[:go-lightly/timeout] "
      "<=[] "))
  
  Closeable
  (close [this]
    (reset! (.open? this) false)
    nil))

;; TODO: doesn't work - why not?
;; (defmethod print-method BufferedChannel
;;   [ch w]
;;   (print-method '<- w) (print-method (seq (.q ch) w) (print-method '-< w)))

(defn take [channel] (.take (.q channel)))
(defn peek [channel] (.peek (.q channel)))
(defn close [channel] (.close channel))

(defn closed? [channel]
  (not @(.open? channel)))

(defn prefer [channel]
  (reset! (.prefer? channel) true))

(defn unprefer [channel]
  (reset! (.prefer? channel) false))

(defn preferred? [channel]
  @(.prefer? channel))

;; (defn channel
;;   "If no size is specifies, returns a TransferQueue as a channel.
;;    If a size is passed is in, returns a bounded BlockingQueue."
;;   ([] (LinkedTransferQueue.))
;;   ([capacity] (LinkedBlockingQueue. capacity)))

(defn channel
  ([] (->Channel (LinkedTransferQueue.) (atom true) (atom false)))
  ([capacity] (->BufferedChannel (LinkedBlockingQueue. capacity) (atom true) (atom false))))

(defn preferred-channel
  ([] (prefer (channel)))
  ([capacity] (prefer (channel capacity))))

(defn timeout-channel
  [duration-ms]
  (let [ch (->TimeoutChannel (LinkedBlockingQueue. 1) (atom true) (atom true))]
    (go& (do (Thread/sleep duration-ms)
             (.put (.q ch) :go-lightly/timeout)
             (close ch)))
    ch))

;; TODO: remove later
;; (defn timeout-channel
;;   "Create a channel that after the specified duration (in
;;    millis) will have the :go-lightly/timeout sentinel value"
;;   [duration]
;;   (let [ch (channel)]
;;     (go& (do (Thread/sleep duration)
;;              (.put ch :go-lightly/timeout)))
;;     ch))

;; ---[ select and helper fns ]--- ;;

(defn- now [] (System/currentTimeMillis))

(defn- timed-out? [start duration]
  (when duration
    (> (now) (+ start duration))))

(defn- choose [ready-chans]
  (.take (nth ready-chans (rand-int (count ready-chans)))))

(defn- peek-channels [channels]
  (let [ready (doall (keep #(when-not (nil? (.peek %)) %) channels))]
    (if (seq ready)
      (nth ready (rand-int (count ready)))  ;; pick at random if >1 ready
      (Thread/sleep 0 500))))

;; TODO: if any of these channels are timeout channels, they
;; need to be read preferentially, so we would need to add
;; some polymorphism or flags to detect which are timeout
;; channels => can do this by creating our own protocols for
;; the channel and have a defrecord type of TimerChannel
;; vs. regular GoChannel
(defn- probe-til-ready [channels timeout]
  (let [start (now)]
    (loop [chans channels ready-chan nil]
      (cond
       ready-chan (.take ready-chan)
       (timed-out? start timeout) :go-lightly/timeout
       :else (recur channels (peek-channels channels))))))

(defn- doselect [channels timeout nowait]
  (let [ready (doall (filterv #(not (nil? (.peek %))) channels))]
    (if (seq ready)
      (choose ready)
      (when-not nowait
        (probe-til-ready channels timeout)))))

(defn- parse-nowait-args [channels]
  (if (keyword? (last channels))
    (split-at (dec (count channels)) channels)
    [channels nil]))

;; public select fns

(defn select
  "Select one message from the channels passed in."
  [& channels]
  (doselect channels nil nil))

(defn select-timeout
  "Like select, selects one message from the channels passed in
   with the same behavior except that a timeout is in place that
   if no message becomes available before the timeout expires, a
   :go-lightly/timeout sentinel message will be returned."
  [timeout & channels]
  (doselect channels timeout nil))

(defn select-nowait
  "Like select, selects one message from the channels passed in
   with the same behavior except that if no channel has a message
   ready, it immediately returns nil or the sentinel keyword value
   passed in as the last argument."
  [& channels]
  (let [[chans sentinel] (parse-nowait-args channels)
        result (doselect chans nil :nowait)]
    (if (and (nil? result) (seq? sentinel))
      (first sentinel)
      result)))


;; ---[ channels to collection/sequence conversions ]--- ;;

(defn channel->seq
  "Takes a snapshot of all values on a channel *without* removing
   the values from the channel. Returns a (non-lazy) seq of the values.
   Generally recommended for use with a buffered channel, but will return
   return a single value if a producer is waiting to put one on."
  [ch]
  (seq (.toArray ch)))

(defn channel->vec
  "Takes a snapshot of all values on a channel *without* removing
   the values from the channel. Returns a vector of the values.
   Generally recommended for use with a buffered channel, but will return
   return a single value if a producer is waiting to put one on."
  [ch]
  (vec (.toArray ch)))

(defn drain
  "Removes all the values on a channel and returns them as a non-lazy seq.
   Generally recommended for use with a buffered channel, but will return
   a pending transfer value if a producer is waiting to put one on."
  [ch]
  (let [al (ArrayList.)]
    (.drainTo ch al)
    (seq al)))

(defn lazy-drain
  "Lazily removes values from a channel. Returns a Cons lazy-seq until
   it reaches the end of the channel.
   Generally recommended for use with a buffered channel, but will return
   on or more values one or more producer(s) is waiting to put a one or
   more values on.  There is a race condition with producers when using."
  [ch]
  (if-let [v (.poll ch)]
    (cons v (lazy-seq (lazy-drain ch)))
    nil))

;; ---[ helper macros ]--- ;;

;; Credit to mikera
;; from: http://stackoverflow.com/a/6697469/871012
;; TODO: is there a way to do this where the future can
;;       return something or do something before being
;;       cancelled?  Would require an abstraction around
;;       future ...
(defmacro with-timeout [millis & body]
  `(let [fut# (future ~@body)]
     (try
       (.get fut# ~millis TimeUnit/MILLISECONDS)
       (catch TimeoutException x#
         (do
           (future-cancel fut#)
           nil)))))
