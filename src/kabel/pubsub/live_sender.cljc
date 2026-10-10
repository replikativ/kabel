(ns kabel.pubsub.live-sender
  "Sender side of the acknowledged live window: one LiveSender per (connection,
  topic).

  A live publish is queued here instead of being put on the connection
  directly. A single send loop per sender writes the queue to the connection
  while the receiver's grant allows: at most `:frames` publications and
  `:bytes` estimated bytes unacknowledged. The receiver acknowledges
  cumulatively after it has APPLIED a publication, so the backlog of a burst
  stays here, bounded and under an overflow policy, instead of overflowing the
  receiver's lane.

  The transitions are pure functions `state -> [state result]`, testable
  without channels; `swap!` applies them. The send loop is the only writer of
  a sender's frames, including its control frames (`handshake-complete`,
  `resync`), which go before any queued publication and outside the window."
  (:require #?(:clj [clojure.core.async :as async :refer [go alts! close! chan]]
               :cljs [clojure.core.async :as async :refer [go alts! close! chan] :include-macros true])
            [kabel.pubsub.protocol :as proto]
            [replikativ.logging :as log]))

(def default-limits
  {:max-queued-publishes 4096
   :max-queued-bytes (* 16 1024 1024)})

(def ^:private empty-queue
  #?(:clj clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

;; =============================================================================
;; Pure transitions
;; =============================================================================

(defn initial-state
  "`mode` is `:windowed` (with `grant` `{:frames W :bytes B}`), `:legacy` (peer
  without windows: no seq, no window) or `:pending-grant` (queue, send nothing
  until the grant decision). `phase` is `:handshaking` for a server sender
  created at subscribe, `:live` otherwise."
  [{:keys [topic epoch mode grant phase limits]}]
  {:topic topic
   :epoch epoch
   :mode mode
   :grant grant
   :next-grant nil
   :phase (or phase :live)
   :overflowed? false
   :queue empty-queue
   :queued-bytes 0
   :control []
   :next-seq 0
   :acked -1
   :unacked (sorted-map)
   :unacked-bytes 0
   :last-ack-requested -1
   :limits (merge default-limits limits)})

(defn- drop-queue [s]
  (assoc s :queue empty-queue :queued-bytes 0))

(defn- fits-window?
  "Whether `entry` may be sent now. An entry larger than the byte grant is sent
  alone, when nothing is unacknowledged."
  [{:keys [grant unacked unacked-bytes]} entry]
  (let [n (count unacked)]
    (and (< n (:frames grant))
         (or (zero? n)
             (<= (+ unacked-bytes (:bytes entry)) (:bytes grant))))))

(defn enqueue
  "Queue `entry` `{:payload p :bytes b}`, or apply `policy` when a bound would
  be exceeded. `connection-room` is how many more bytes the connection-wide cap
  admits. Returns `[state decision]`, decision one of:
  - `:queued`;
  - `:dropped` (the sender accepts no more publications);
  - `:resync` (phase was live: the queue is dropped and `resync` is the last
    frame; the caller removes the subscriber);
  - `:resync-deferred` (phase was handshaking: the end of the handshake sends
    `resync` instead of `handshake-complete`);
  - `:fail` (the queue is dropped and the sender closed; the caller retires
    the connection)."
  [s {:keys [bytes] :as entry} policy connection-room]
  (let [{:keys [max-queued-publishes max-queued-bytes]} (:limits s)
        queued (fn [s] (-> s
                           (update :queue conj entry)
                           (update :queued-bytes + bytes)))]
    (cond
      (or (#{:closing :closed} (:phase s)) (:overflowed? s))
      [s :dropped]

      (and (< (count (:queue s)) max-queued-publishes)
           (<= (+ (:queued-bytes s) bytes) max-queued-bytes)
           (<= bytes connection-room))
      [(queued s) :queued]

      ;; The grant decision is still out. Do not fail a connection that works
      ;; without windows: send unpaced, as a peer without windows does. A grant
      ;; that arrives later still upgrades the sender.
      (= :pending-grant (:mode s))
      [(queued (assoc s :mode :legacy)) :queued]

      ;; A peer without windows cannot be told to resync.
      (and (= :resync policy) (not= :legacy (:mode s)))
      (if (= :handshaking (:phase s))
        [(-> s drop-queue (assoc :overflowed? true)) :resync-deferred]
        [(-> s drop-queue (assoc :phase :closing) (update :control conj :resync))
         :resync])

      :else
      [(-> s drop-queue (assoc :phase :closed :control [])) :fail])))

(defn take-next
  "The send loop's step. Returns `[state action]`, action one of
  `[:control kw]`, `[:publish {:payload p :seq n :epoch e :ack? b}]` (no seq in
  legacy mode), `[:wait]` or `[:done]`.

  A publication requests an ack when the queue is empty after it, when the
  next queued entry would not fit the window, or after half a window without a
  request. So whenever the loop waits with a non-empty queue, the newest frame
  it sent asked for an ack, and progress needs no timer."
  [{:keys [phase control queue mode grant next-seq last-ack-requested epoch]
    :as s}]
  (cond
    (= :closed phase)
    [s [:done]]

    (seq control)
    [(assoc s :control (subvec control 1)) [:control (first control)]]

    (empty? queue)
    (if (= :closing phase)
      [(assoc s :phase :closed) [:done]]
      [s [:wait]])

    (= :pending-grant mode)
    [s [:wait]]

    (= :legacy mode)
    (let [entry (peek queue)]
      [(-> s
           (update :queue pop)
           (update :queued-bytes - (:bytes entry)))
       [:publish {:payload (:payload entry)}]])

    (not (fits-window? s (peek queue)))
    [s [:wait]]

    :else
    (let [entry (peek queue)
          n next-seq
          s (-> s
                (update :queue pop)
                (update :queued-bytes - (:bytes entry))
                (assoc :next-seq (inc n))
                (update :unacked assoc n (:bytes entry))
                (update :unacked-bytes + (:bytes entry)))
          next-entry (peek (:queue s))
          ack? (or (nil? next-entry)
                   (not (fits-window? s next-entry))
                   (>= (- n last-ack-requested)
                       (quot (inc (:frames grant)) 2)))]
      [(cond-> s ack? (assoc :last-ack-requested n))
       [:publish {:payload (:payload entry) :seq n :epoch epoch :ack? ack?}]])))

(defn on-ack
  "Apply a cumulative ack up to and including `n`. Stale epochs and seqs out of
  range are ignored. Returns `[state acked-count]`, the number of frames this
  sender has had acknowledged in total."
  [s epoch n]
  (if (and (= epoch (:epoch s))
           (> n (:acked s))
           (< n (:next-seq s)))
    (let [released (subseq (:unacked s) <= n)
          remaining (into (sorted-map) (subseq (:unacked s) > n))
          s (assoc s
                   :acked n
                   :unacked remaining
                   :unacked-bytes (- (:unacked-bytes s)
                                     (reduce + 0 (map val released))))
          s (if (and (:next-grant s) (empty? remaining))
              (assoc s :grant (:next-grant s) :next-grant nil)
              s)]
      [s (inc n)])
    [s (inc (:acked s))]))

(defn on-grant
  "A grant from the receiver. Upgrades a pending or legacy sender to windowed;
  numbering starts at 0 under the sender's epoch. A grant that would shrink an
  active window waits until nothing is unacknowledged, so the last frame sent
  under the old grant still guarantees progress."
  [s grant]
  (case (:mode s)
    (:pending-grant :legacy)
    [(assoc s :mode :windowed :grant grant) true]

    :windowed
    (if (or (empty? (:unacked s))
            (and (>= (:frames grant) (get-in s [:grant :frames]))
                 (>= (:bytes grant) (get-in s [:grant :bytes]))))
      [(assoc s :grant grant :next-grant nil) true]
      [(assoc s :next-grant grant) true])))

(defn on-legacy
  "The receiver turned out to have no windows."
  [s]
  [(cond-> s (= :pending-grant (:mode s)) (assoc :mode :legacy)) true])

(defn finish
  "The end of a server sender's handshake. Returns `[state outcome]`:
  `:live` (`handshake-complete` queued as a control frame), `:resync` (an
  overflow happened during the handshake: `resync` queued instead, then the
  sender closes), or `:closed`. A sender already draining for an unsubscribe
  still sends `handshake-complete`, so the receiver can drain its captured
  publications and acknowledge the window."
  [s]
  (cond
    (= :closed (:phase s))
    [s :closed]

    (:overflowed? s)
    [(-> s
         (assoc :phase :closing :overflowed? false)
         (update :control conj :resync))
     :resync]

    (= :closing (:phase s))
    [(update s :control conj :handshake-complete) :closing]

    :else
    [(-> s
         (assoc :phase :live)
         (update :control conj :handshake-complete))
     :live]))

(defn drain
  "Accept no more publications; the loop sends what is queued, then closes.
  Used by unsubscribe, whose drain marker must follow every frame queued
  before it."
  [s]
  [(cond-> s
     (not= :closed (:phase s)) (assoc :phase :closing))
   true])

(defn close
  "Close now: drop the queue and the control frames."
  [s]
  [(-> s drop-queue (assoc :phase :closed :control [])) true])

;; =============================================================================
;; Runner
;; =============================================================================

(defn create
  "A sender handle. `connection-bytes` is an atom shared by all senders of one
  connection, holding their total queued bytes."
  [out connection-bytes opts]
  {:state (atom (initial-state opts))
   :out out
   :connection-bytes connection-bytes
   :wake (chan (async/sliding-buffer 1))
   :stop (chan)
   :done (async/promise-chan)})

(defn swap-sender-reserved!
  "As `swap-sender!`, for a transition whose `reserved` bytes the caller has
  already added to the connection total: the total moves by the queue's change
  less the reservation, so the reservation turns into the queued bytes without
  being counted twice."
  [sender reserved f & args]
  (let [result (volatile! nil)
        [old new] (swap-vals! (:state sender)
                              (fn [s]
                                (let [[s r] (apply f s args)]
                                  (vreset! result r)
                                  s)))]
    (when-let [c (:connection-bytes sender)]
      (let [delta (- (:queued-bytes new) (:queued-bytes old) reserved)]
        (when-not (zero? delta)
          (swap! c + delta))))
    (when-not (identical? old new)
      (async/offer! (:wake sender) true))
    (when (and (= :closed (:phase new)) (not= :closed (:phase old)))
      (close! (:stop sender)))
    @result))

(defn swap-sender!
  "Apply a pure transition `(f state & args) -> [state result]` to `sender` and
  return the result. Keeps the connection byte total, wakes the send loop on
  any change, and stops it when the sender closes."
  [sender f & args]
  (apply swap-sender-reserved! sender 0 f args))

(defn state [sender] @(:state sender))

(defn- frame
  [{:keys [topic epoch]} [kind x]]
  (case kind
    :control (case x
               :handshake-complete (proto/handshake-complete-msg topic)
               :resync (proto/resync-msg topic epoch))
    :publish (let [{:keys [payload seq epoch ack?]} x]
               (cond-> (proto/publish-msg topic payload)
                 seq (assoc :seq seq :epoch epoch)
                 ack? (assoc :ack? true)))))

(defn start!
  "Run the send loop. `on-exit` is called once when the loop ends, for any
  reason; `on-write-failed` with the reason when the connection refused a
  frame (closed, or core.async's pending-put limit)."
  [sender {:keys [on-exit on-write-failed]}]
  (let [{:keys [out stop wake done]} sender]
    (go
      (try
        (loop []
          (let [action (swap-sender! sender take-next)]
            (case (first action)
              :done nil

              :wait
              (let [[_ port] (alts! [stop wake] :priority true)]
                (when-not (= port stop)
                  (recur)))

              (let [msg (frame (state sender) action)
                    [ok? port] (try
                                 ;; stop first: a closed sender never writes,
                                 ;; and a put still pending when it closes
                                 ;; becomes inactive.
                                 (alts! [stop [out msg]] :priority true)
                                 (catch #?(:clj Throwable :cljs :default) e
                                   [e out]))]
                (cond
                  (= port stop) nil
                  (true? ok?) (recur)
                  :else (when on-write-failed
                          (on-write-failed (when-not (false? ok?) ok?))))))))
        (catch #?(:clj Throwable :cljs :default) e
          (log/error :pubsub/live-sender-failed {:error (str e)})))
      (swap-sender! sender close)
      (close! done)
      (when on-exit
        (on-exit)))
    sender))
