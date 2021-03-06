(ns thornydev.go-lightly.search.google
  (:require [thornydev.go-lightly :as go]))

;; Implementation of Pike's Google IO 2012 example
;; of a fake Google search
;; http://www.youtube.com/watch?v=f6kdp27TYZs&feature=youtu.be

;; Uses the go-lightly library to do this in
;; a more-or-less idiomatic Clojure style

(defn fake-search [kind]
  (fn [query]
    (Thread/sleep (rand-int 100))
    (format "%s result for '%s'\n" kind query)))

;; cached versions of fake-search fns
(def web   (fake-search "web"))
(def image (fake-search "image"))
(def video (fake-search "video"))

(def web1   (fake-search "web1"))
(def web2   (fake-search "web2"))
(def image1 (fake-search "image1"))
(def image2 (fake-search "image2"))
(def video1 (fake-search "video1"))
(def video2 (fake-search "video2"))


;; ---[ Google 1.0 Search ]--- ;;

(defn google-1 [query]
  (reduce #(conj % (%2 query)) [] [web image video]))

;; ---[ Google 2.0 Search ]--- ;;

;; each search transfers onto the same channel
(defn google-20 [query]
  (let [ch (go/channel)]
    (doseq [search [web image video]]
      (go/go (go/put ch (search query))))
    (vec
     (for [_ (range 3)] (go/take ch)))))

;; use a timeout-channel to limit the round of searches
;; to a maximum of 80 ms
(defn google-21 [query]
  (let [ch (go/channel)]
    (doseq [search [web image video]]
      (go/go (go/put ch (search query))))

    (let [tch (go/timeout-channel 80)]
      (loop [msg (go/select ch tch) responses []]
        (cond
         (= 3 (count responses))     responses
         (= msg :go-lightly/timeout) (do (println "Timed out.") responses)
         :else (recur (go/select ch tch) (conj responses msg)))))))


;; use the with-timeout macro to limit search time to 80 ms
;; since the macro spawns this body into a separate future
;; and then cancels it, we have to push accumulated info
;; into an output-ch (or an atom would work too) to be able
;; to return any queries we did complete
(defn google-21b [query]
  (let [search-ch (go/channel)
        searches  [web image video]
        output-ch (go/channel (count searches))]
    (doseq [search searches]
      (go/go (go/put search-ch (search query))))

    (go/with-timeout 80
      (dotimes [_ (count searches)]
        (go/put output-ch (go/take search-ch))))

    (let [out-vec (go/channel->vec output-ch)]
      (when (not= (count out-vec) (count searches))
        (println "Timed out."))
      out-vec)))


(defn- first-to-finish
  "Takes multple fake-search fn (replicas), launches each
   in a separate thread to process the same query.
   Returns the result from the first one to finish."
  [query & replicas]
  (let [ch (go/channel)]
    (doseq [rep replicas]
      (go/go (go/put ch (rep query))))
    (go/take ch)))


(defn google-3 [query]
  (let [ch (go/channel)
        out-ch (go/channel 3)]

    (go/go (go/put ch (first-to-finish query web1 web2)))
    (go/go (go/put ch (first-to-finish query image1 image2)))
    (go/go (go/put ch (first-to-finish query video1 video2)))

    (go/with-timeout 80
      (dotimes [_ 3]
        (go/put out-ch (go/take ch))))

    (let [out-vec (go/channel->vec out-ch)]
      (when-not (= 3 (count out-vec))
        (println "Timed out."))
      out-vec)))

;; for benchmarking
(defn google-3-no-timeout [query]
  (let [ch (go/channel)
        out-ch (go/channel 3)]
    
    (go/go (go/put ch (first-to-finish query web1 web2)))
    (go/go (go/put ch (first-to-finish query image1 image2)))
    (go/go (go/put ch (first-to-finish query video1 video2)))

    (dotimes [_ 3]
      (go/put out-ch (go/take ch)))

    (go/channel->vec out-ch)))

(defn now []
  (System/currentTimeMillis))

(defn since [start]
  (- (now) start))

(defn get-google-fn [version]
  (case version
    :goog1.0 google-1
    :goog2.0 google-20
    :goog2.1 google-21
    :goog2.1b google-21b
    :goog3.0 google-3
    :goog3-nt google-3-no-timeout))

(defn google-main [version]
  (let [start (now)
        results ((get-google-fn version) "clojure")
        elapsed (since start)]
    (println results)
    (println elapsed "ms"))
  (go/stop))
