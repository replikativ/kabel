(ns kabel.ratelimit
  "Per-connection rate limiting, in Synapse's shape.

  ## Why the connection and not the identity

  A token bucket keyed on identity is theatre when an identity costs one key
  generation: the abuser makes a new one. A **connection** costs a socket, a
  handshake, an address and a slot in somebody's connection ceiling, and none of
  those are free. So the connection is the thing worth metering, and the address
  book's group-diversity caps are what stop one operator holding many of them.

  This bounds *resource* abuse. It does not bound speech, and it should not be
  described as though it did.

  ## Synapse's shape: sleep, then queue, then reject

  `rc_federation` does not simply drop traffic over a threshold. It slows the
  offender first, then queues, and only rejects when the queue is full — so a
  peer that is merely busy degrades, while one that is hostile is cut off. A
  plain threshold cannot tell those apart, and answers both by punishing the
  busy one.

  Here that becomes a verdict per message:

      :accept   under the sustained rate
      :slow     over it, but within burst — the caller should add delay
      :queue    over burst — the caller should defer rather than serve now
      :reject   over the hard ceiling — drop it

  The caller decides what those mean, because only it knows whether it can
  afford to defer. This namespace is pure and holds no timers.

  ## Sliding window, not a bucket

  A fixed window lets twice the intended rate through at a boundary — the
  classic failure. This keeps counts in small sub-windows and sums the trailing
  ones, so the rate is bounded across any position of the window. Memory is
  `O(buckets)` per connection and independent of the message rate."
  (:refer-clojure :exclude [reset]))

(def default-opts
  {;; Sustained messages per window.
   :rate 100
   ;; Window length. Rate and window together are the sustained budget.
   :window-ms 10000
   ;; Sub-windows the window is split into. More is smoother and costs more.
   :buckets 10
   ;; Multiple of the sustained rate tolerated before queuing — headroom for a
   ;; peer that is legitimately bursty, such as one serving a subtree.
   :burst-factor 2.0
   ;; Multiple beyond which traffic is rejected outright.
   :reject-factor 5.0
   ;; Connections tracked. Attacker-supplied, so bounded; eviction is by
   ;; staleness so an idle entry never displaces an active one.
   :max-connections 1024})

(defn make-state
  ([] (make-state {}))
  ([opts]
   {:opts (merge default-opts opts)
    ;; connection -> {:buckets {index count} :last-seen t}
    :conns {}
    :stats {:accepted 0 :slowed 0 :queued 0 :rejected 0 :evicted 0}}))

(defn- bucket-index
  [opts now]
  (let [{:keys [window-ms buckets]} opts]
    (quot now (quot window-ms buckets))))

(defn- live-count
  "Messages in the trailing window, from the sub-window counts."
  [conn opts now]
  (let [{:keys [buckets]} opts
        idx (bucket-index opts now)
        floor (- idx (dec buckets))]
    (reduce-kv (fn [acc i n] (if (>= i floor) (+ acc n) acc)) 0 (:buckets conn))))

(defn- expire-buckets
  [conn opts now]
  (let [{:keys [buckets]} opts
        floor (- (bucket-index opts now) (dec buckets))]
    (update conn :buckets #(into {} (filter (fn [[i _]] (>= i floor)) %)))))

(defn- evict-if-full
  [state now]
  (let [{:keys [max-connections]} (:opts state)]
    (if (> (count (:conns state)) max-connections)
      ;; Oldest-seen first: an idle entry must never displace an active one,
      ;; or a flood of new connections would evict the peers doing real work.
      (let [victim (first (sort-by #(get-in (:conns state) [% :last-seen] 0)
                                   (keys (:conns state))))]
        (-> state
            (update :conns dissoc victim)
            (update-in [:stats :evicted] inc)))
      state)))

(defn check
  "Account for one message from `conn` and return `[state verdict]`.

  Verdicts are `:accept`, `:slow`, `:queue` or `:reject` — see the namespace
  docstring. The message is counted whatever the verdict, including when
  rejected: a peer that keeps sending after being refused should not get its
  budget back by being refused."
  [state conn now]
  (let [opts (:opts state)
        {:keys [rate burst-factor reject-factor]} opts
        idx (bucket-index opts now)
        state (update-in state [:conns conn]
                         (fn [c]
                           (-> (or c {:buckets {} :last-seen now})
                               (expire-buckets opts now)
                               (update-in [:buckets idx] (fnil inc 0))
                               (assoc :last-seen now))))
        state (evict-if-full state now)
        n (live-count (get-in state [:conns conn]) opts now)
        verdict (cond
                  (> n (* rate reject-factor)) :reject
                  (> n (* rate burst-factor)) :queue
                  (> n rate) :slow
                  :else :accept)
        stat (case verdict
               :accept :accepted :slow :slowed :queue :queued :reject :rejected)]
    [(update-in state [:stats stat] inc) verdict]))

(defn rate-for
  "Messages counted for `conn` in the trailing window — for diagnostics, and
  for a caller that wants to report why it is slowing somebody down."
  [state conn now]
  (if-let [c (get-in state [:conns conn])]
    (live-count c (:opts state) now)
    0))

(defn forget
  "Drop all accounting for `conn` — call when a connection closes, or its
  entry lingers until evicted."
  [state conn]
  (update state :conns dissoc conn))
