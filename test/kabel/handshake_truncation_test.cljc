(ns kabel.handshake-truncation-test
  "`send-handshake!` must end the handshake when the producer is FINISHED, and
   only then.

   Today it ends on either of two conditions and cannot tell them apart: the
   item channel CLOSED (correct — the producer said it is done) or the item
   channel went QUIET for `:item-timeout-ms` (wrong — the producer is merely
   slow). Both fall out of the same `alts!` into the same `items` vector, and an
   empty `items` sends `:pubsub/handshake-complete`.

   A producer is legitimately quiet whenever it computes rather than streams.
   konserve-sync's `-handshake-items` is exactly that shape: it evaluates the
   whole key set — a store walk plus a metadata read per key — before it emits
   anything, and it CLOSES the channel when genuinely done. So the exact signal
   needed is already there and is being ignored in favour of a 100 ms guess.

   The consequence is silent data loss, not a slow sync: the subscriber is told
   the handshake completed, so it believes the prefix it received is the whole
   store. datahike's walker emits index nodes first and mutable branch pointers
   LAST, so truncation drops precisely the branch head — the one key whose
   absence makes the replica unusable."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.pubsub :as pubsub]
            [superv.async :refer [S <?? go-try] :as sasync]
            #?(:clj [clojure.core.async :as async :refer [chan >!! <!! go timeout close! put! alts!!]]
               :cljs [cljs.core.async :as async :refer [chan close! put!]])))

(def ^:private send-handshake! #'pubsub/send-handshake!)

#?(:clj
   (defn- drain-out
     "Collect everything written to `out` for `ms`, then return it."
     [out ms]
     (let [deadline (+ (System/currentTimeMillis) ms)]
       (loop [acc []]
         (let [remaining (- deadline (System/currentTimeMillis))]
           (if (neg? remaining)
             acc
             (let [[v _] (alts!! [out (timeout remaining)])]
               (if (nil? v) acc (recur (conj acc v))))))))))

#?(:clj
   (defn- run-handshake
     "Feed `producer-fn` items into a handshake channel and run `send-handshake!`
      against a fake `out`. `producer-fn` receives the channel and is responsible
      for closing it. Auto-acks every batch so the ack path never interferes.
      Returns {:msgs [...] :complete? bool :data-count n}."
     [producer-fn opts]
     (let [out          (chan 1024)
           handshake-ch (chan 100)
           pending-acks (atom {})]
       ;; auto-ack: whenever a batch-complete is pending, deliver its ack
       (async/go-loop []
         (async/<! (timeout 5))
         (doseq [[topic batches] @pending-acks
                 [idx ack-ch] batches]
           (put! ack-ch {:ok true :topic topic :batch idx}))
         (recur))
       (producer-fn handshake-ch)
       (let [result-ch (send-handshake! S out :t handshake-ch opts pending-acks)
             _         (alts!! [result-ch (timeout 8000)])
             msgs      (drain-out out 300)]
         {:msgs msgs
          :complete? (boolean (some #(= :pubsub/handshake-complete (:type %)) msgs))
          :data-count (count (filter #(= :pubsub/handshake-data (:type %)) msgs))}))))

#?(:clj
   (deftest quiet-producer-is-not-a-finished-producer
     ;; The canonical konserve-sync shape: think for a while, THEN stream, THEN
     ;; close. Nothing is lost or late here — the producer simply had to compute
     ;; before it could speak.
     (testing "a producer that pauses before its first item must not be cut off"
       (let [res (run-handshake
                  (fn [ch]
                    (async/go
                      (async/<! (timeout 400))        ; compute phase (> item-timeout)
                      (doseq [i (range 30)] (async/>! ch {:key i :value i}))
                      (close! ch)))
                  (assoc pubsub/default-opts :item-timeout-ms 100))]
         (is (:complete? res) "handshake should still finish")
         (is (= 30 (:data-count res))
             (str "expected all 30 items, got " (:data-count res)
                  " — the handshake was truncated by the quiet period"))))))

#?(:clj
   (deftest mid-stream-pause-does-not-truncate
     ;; The shape that bites a big store: items flow, then one read is slow
     ;; (a cold page, GC, a large node), then the rest arrive — including the
     ;; branch head, which the walker deliberately emits LAST.
     (testing "a pause in the middle must not drop the tail"
       (let [res (run-handshake
                  (fn [ch]
                    (async/go
                      (doseq [i (range 25)] (async/>! ch {:key i :value i}))
                      (async/<! (timeout 400))        ; slow read mid-stream
                      (doseq [i (range 25 50)] (async/>! ch {:key i :value i}))
                      (async/>! ch {:key :db :value :HEAD})   ; head last
                      (close! ch)))
                  (assoc pubsub/default-opts :item-timeout-ms 100))]
         (is (:complete? res))
         (is (= 51 (:data-count res))
             (str "expected 51 items, got " (:data-count res)))
         (is (some #(= :db (get-in % [:data :key])) (:msgs res))
             "the branch head must survive — its loss is what makes the replica unusable")))))

#?(:clj
   (deftest closed-channel-still-completes-promptly
     ;; The guard against over-correcting: a producer that closes immediately
     ;; must still finish at once, not wait out any timeout.
     (testing "an empty, closed producer completes"
       (let [res (run-handshake (fn [ch] (close! ch))
                                (assoc pubsub/default-opts :item-timeout-ms 100))]
         (is (:complete? res))
         (is (zero? (:data-count res)))))))
