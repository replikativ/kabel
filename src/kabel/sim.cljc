(ns kabel.sim
  "A deterministic discrete-event simulator for overlay protocols.

  Every run is a pure function of its seed. There is no wall clock, no
  threads, no `core.async`, and no unseeded randomness anywhere — so a failing
  run is reproduced by rerunning the seed, and a run reproduces identically on
  the JVM and in ClojureScript.

  ## Why this exists before the protocols do

  The interesting bugs in membership and dissemination protocols live under
  churn: nodes joining and leaving *during* a lookup, views being read while
  they split, repair racing expiry. An integration test against a handful of
  live peers does not produce them and will pass while the design is wrong.
  See `.internal/DHT_DESIGN.md` §7.

  ## The rule this simulator exists to enforce

  **Determinism comes from a seeded RNG, never from replacing the algorithm.**
  Each node carries its own rng in its state, seeded from the simulation seed
  and the node id, so the real peer-selection code runs — with reproducible
  draws — rather than a stub. Three of the four systems reviewed in
  `.internal/reference/` shipped a defence that was inert, or a selection
  function that was wrong, with tests that passed anyway. Assert that an
  attack has **no effect**; never that a counter moved.

  ## Node model

  A node is a pure state machine, following partisan's
  `partisan_broadcast_engine` shape (`.internal/reference/partisan.md` §7):

      (fn handler [state event ctx] -> {:state state' :actions [action ...]})

  where `event` is one of

      {:type :init}
      {:type :message      :from <id> :payload <any>}
      {:type :timer        :payload <any>}
      {:type :disconnected :peer <id>}

  `ctx` is `{:id <this node> :now <virtual ms>}`, and each action is

      [:send       <to-id> <payload>]
      [:timer      <delay-ms> <payload>]
      [:connect    <to-id> <address> <first-frame>]
      [:disconnect <to-id>]
      [:persist    <key> <value>]
      [:deliver    <topic> <payload>]

  `:connect` and `:disconnect` exist because opening a transport is not a
  message — in a deployment they are `kabel.peer/connect` against a URL
  followed by sending `<first-frame>`, and a socket close. Keeping them in the vocabulary means the protocol must hold an
  *address* to dial, which is the property the address book has to satisfy and
  which a message-only model quietly lets you skip.

  Handlers must not read a clock, generate randomness outside their state, or
  perform effects. Everything they need is in `state`, `event` and `ctx`.

  **Give the event dispatch a default clause.** The event vocabulary grows —
  `:disconnected` was added after the first handlers were written — and a
  `case` without a default turns a new event type into a crash rather than an
  ignored event."
  (:require [kabel.sim.rng :as rng]))

;; =============================================================================
;; Event queue
;; =============================================================================
;; Ordered by [time, insertion-sequence]. The sequence number is what makes
;; the order total: two events scheduled for the same virtual millisecond must
;; have one deterministic order, or the simulation is only reproducible by
;; luck.

(def ^:private event-order
  (fn [a b]
    (compare [(:at a) (:seq a)] [(:at b) (:seq b)])))

(defn- empty-queue [] (sorted-set-by event-order))

;; =============================================================================
;; Construction
;; =============================================================================

(def default-opts
  {:seed 42
   ;; Message latency is drawn uniformly from this inclusive range.
   :latency-min 10
   :latency-max 50
   ;; Probability that a message is silently lost in the network.
   :drop-p 0.0
   ;; Hard bound on events processed by `run-*`, so a protocol bug that
   ;; schedules work faster than it consumes it fails the test rather than
   ;; hanging it.
   :max-steps 1000000
   ;; Recording every delivery is useful and unbounded; a long run should turn
   ;; it off and assert on node state instead.
   :trace? true})

(defn make-sim
  "Create a simulation. See `default-opts` for the options."
  ([] (make-sim {}))
  ([opts]
   (let [opts (merge default-opts opts)]
     {:now 0
      :seq 0
      :steps 0
      :rng (rng/make-rng (:seed opts))
      :queue (empty-queue)
      :nodes {}
      ;; nil means "one network". Otherwise a map of node-id -> group; nodes
      ;; can reach each other exactly when their groups are equal.
      :partition nil
      :opts opts
      :trace []
      :stats {:sent 0 :delivered 0 :dropped-partition 0
              :dropped-loss 0 :dropped-down 0 :timers 0 :connects 0}})))

(defn- node-seed
  "Derive a node's rng seed from the simulation seed and its id.

  Deterministic, and independent of map iteration order — `hash` differs
  across platforms, so the id is stringified and folded byte by byte."
  [sim id]
  (let [s (str id)]
    (reduce (fn [acc ch]
              (rng/u32 (bit-xor (rng/u32 (bit-shift-left acc 5))
                                (int #?(:clj ch :cljs (.charCodeAt ch 0))))))
            (rng/u32 (get-in sim [:opts :seed]))
            (seq s))))

(defn- enqueue [sim event]
  (-> sim
      (update :queue conj (assoc event :seq (:seq sim)))
      (update :seq inc)))

(defn add-node
  "Add a node running `handler` with `init-state`.

  The node receives an `{:type :init}` event at the current virtual time, so
  it can schedule its first timers. Its rng is seeded from the simulation seed
  and its id, and lives at `:rng` in its state."
  [sim id handler init-state]
  (-> sim
      (assoc-in [:nodes id] {:handler handler
                             :up? true
                             :state (assoc init-state
                                           :rng (rng/make-rng (node-seed sim id))
                                           :id id)})
      (enqueue {:at (:now sim) :kind :init :to id})))

;; =============================================================================
;; Faults
;; =============================================================================

(defn- notify-disconnect
  "Tell every other node that `id`'s transport is gone.

  Broadcast rather than targeted because the simulator does not track
  connections — they are protocol state, not transport state. A node that was
  not connected to `id` simply drops a key it does not hold, so the extra
  events are inert.

  This matters for fidelity: when a real process dies its kernel closes every
  socket, so peers *do* find out. A simulator where a crash is silent lets a
  protocol pass tests while believing forever in connections that are gone."
  [sim id]
  (reduce (fn [s other]
            (if (= other id)
              s
              (enqueue s {:at (:now s) :kind :disconnected :to other :from id})))
          sim
          (keys (:nodes sim))))

(defn link-down
  "Drop the link between `a` and `b`: both learn the transport is gone.

  The targeted counterpart to `crash` — for testing a dropped connection
  between two peers that are both still running."
  [sim a b]
  (-> sim
      (enqueue {:at (:now sim) :kind :disconnected :to a :from b})
      (enqueue {:at (:now sim) :kind :disconnected :to b :from a})))

(defn crash
  "Take `id` down. It stops processing events; messages to it are dropped, and
  every other node is told its transport is gone.

  Its state is retained, so `restart` resumes from it — a crash is not
  amnesia. Use `forget` for that."
  [sim id]
  (-> sim
      (assoc-in [:nodes id :up?] false)
      (notify-disconnect id)))

(defn restart
  "Bring `id` back up and re-deliver `:init` so it can re-arm its timers."
  [sim id]
  (-> sim
      (assoc-in [:nodes id :up?] true)
      (enqueue {:at (:now sim) :kind :init :to id})))

(defn forget
  "Remove `id` entirely, state and all — a permanent departure.

  Peers are told the transport is gone, as they would be by a closing socket."
  [sim id]
  (-> sim
      (notify-disconnect id)
      (update :nodes dissoc id)))

(defn partition-network
  "Split the network into groups: a map of `node-id -> group`.

  Nodes reach each other exactly when their groups are equal. A node absent
  from the map is in group `nil` and so can reach every other absent node."
  [sim groups]
  (assoc sim :partition groups))

(defn heal
  "Remove any partition."
  [sim]
  (assoc sim :partition nil))

(defn reachable?
  "Can `from` currently send to `to`?"
  [sim from to]
  (let [p (:partition sim)]
    (or (nil? p) (= (get p from) (get p to)))))

(defn up? [sim id]
  (boolean (get-in sim [:nodes id :up?])))

;; =============================================================================
;; Scheduling
;; =============================================================================

(defn at
  "Schedule `f` — a `(fn [sim] -> sim)` — to run at virtual time `t`.

  This is how churn is injected: `(at sim 5000 #(crash % :n3))`."
  [sim t f]
  (enqueue sim {:at t :kind :call :f f}))

(defn send-message
  "Inject a message from outside, as if `from` had sent it now."
  [sim from to payload]
  (enqueue sim {:at (:now sim) :kind :message :from from :to to :payload payload}))

(defn- draw-latency [sim]
  (let [{:keys [latency-min latency-max]} (:opts sim)
        [rng' ms] (rng/rand-range (:rng sim) latency-min latency-max)]
    [(assoc sim :rng rng') ms]))

(defn- drop-message? [sim]
  (let [p (get-in sim [:opts :drop-p])]
    (if (<= p 0.0)
      [sim false]
      (let [[rng' dropped?] (rng/rand-bool (:rng sim) p)]
        [(assoc sim :rng rng') dropped?]))))

(defn- transmit
  "Put `payload` on the wire from `from` to `to`, subject to reachability,
  loss and latency."
  [sim from to payload]
  (let [sim (update-in sim [:stats :sent] inc)]
    (cond
      (not (contains? (:nodes sim) to))
      (update-in sim [:stats :dropped-down] inc)

      (not (reachable? sim from to))
      (update-in sim [:stats :dropped-partition] inc)

      :else
      (let [[sim dropped?] (drop-message? sim)]
        (if dropped?
          (update-in sim [:stats :dropped-loss] inc)
          (let [[sim ms] (draw-latency sim)]
            (enqueue sim {:at (+ (:now sim) ms)
                          :kind :message
                          :from from
                          :to to
                          :payload payload})))))))

(defn- apply-action
  [sim from [op & args]]
  (case op
    :send
    (let [[to payload] args]
      (transmit sim from to payload))

    ;; Opening and closing a transport are their own actions rather than
    ;; messages, because in a real deployment they are: `:connect` becomes
    ;; `kabel.peer/connect` against a URL, and `:disconnect` closes a socket.
    ;;
    ;; The simulator models both as ordinary frames, so the same protocol code
    ;; runs here and over a real wire. The address is carried but unused — the
    ;; simulator routes by node id, and the point of the action is that the
    ;; protocol had to *have* an address to dial, which is the property the
    ;; address book must satisfy.
    :connect
    (let [[to _address payload] args]
      (-> sim
          (update-in [:stats :connects] (fnil inc 0))
          (transmit from to (or payload {:type :dial}))))

    :deliver
    ;; Handing a payload to the application. The simulator has no application —
    ;; it counts the call so a test can assert delivery happened at the
    ;; protocol's boundary, which is the part the protocol is responsible for.
    (update-in sim [:stats :delivered-to-app] (fnil inc 0))

    :state-sync
    ;; "Your gap is older than anything I still hold." The simulator has no
    ;; application to answer it, so it counts the escalation — which is what a
    ;; test wants to assert anyway: that the transport gave up on repair
    ;; exactly once per unrepairable gap, rather than every tick.
    (update-in sim [:stats :state-syncs] (fnil inc 0))

    :persist
    ;; Handing a value to durable storage. The simulator has no store — it
    ;; counts the call so a test can assert persistence was attempted, which is
    ;; the part the protocol is responsible for. Whether the bytes land is the
    ;; runtime's business.
    (update-in sim [:stats :persisted] (fnil inc 0))

    :disconnect
    (let [[to] args]
      ;; The far side learns the transport is gone, exactly as a closing socket
      ;; tells it. The near side has already updated its own state — it is the
      ;; one that asked.
      (enqueue sim {:at (:now sim) :kind :disconnected :to to :from from}))

    :timer
    (let [[delay payload] args]
      (-> sim
          (update-in [:stats :timers] inc)
          (enqueue {:at (+ (:now sim) (max 0 delay))
                    :kind :timer
                    :to from
                    :payload payload})))

    (throw (ex-info "Unknown simulator action"
                    {:type :kabel.sim/unknown-action :action op :from from}))))

(defn- record [sim event]
  (if (get-in sim [:opts :trace?])
    (update sim :trace conj (select-keys event [:at :kind :from :to :payload]))
    sim))

(defn- deliver-to-node
  [sim {:keys [to from payload kind] :as event}]
  (let [{:keys [handler state up?]} (get-in sim [:nodes to])]
    (if-not up?
      ;; Messages and timers for a down node are simply lost; when it comes
      ;; back up it gets a fresh :init rather than a backlog.
      (update-in sim [:stats :dropped-down] inc)
      (let [ev (case kind
                 :init {:type :init}
                 :message {:type :message :from from :payload payload}
                 :timer {:type :timer :payload payload}
                 :disconnected {:type :disconnected :peer from})
            {new-state :state actions :actions}
            (handler state ev {:id to :now (:now sim)})
            sim (-> sim
                    (assoc-in [:nodes to :state] (or new-state state))
                    (record event))
            sim (if (= :message kind)
                  (update-in sim [:stats :delivered] inc)
                  sim)]
        (reduce (fn [s a] (apply-action s to a)) sim (or actions []))))))

;; =============================================================================
;; Running
;; =============================================================================

(defn step
  "Process the earliest pending event. Returns the sim unchanged if idle."
  [sim]
  (if-let [event (first (:queue sim))]
    (let [sim (-> sim
                  (update :queue disj event)
                  (assoc :now (max (:now sim) (:at event)))
                  (update :steps inc))]
      (if (= :call (:kind event))
        ((:f event) sim)
        (if (contains? (:nodes sim) (:to event))
          (deliver-to-node sim event)
          (update-in sim [:stats :dropped-down] inc))))
    sim))

(defn idle? [sim] (empty? (:queue sim)))

(defn- check-steps [sim]
  (when (> (:steps sim) (get-in sim [:opts :max-steps]))
    (throw (ex-info "Simulation exceeded :max-steps — a protocol is probably scheduling faster than it consumes"
                    {:type :kabel.sim/step-limit
                     :steps (:steps sim)
                     :now (:now sim)
                     :queued (count (:queue sim))})))
  sim)

(defn run-until
  "Run until virtual time reaches `t`, or the simulation goes idle."
  [sim t]
  (loop [sim sim]
    (let [next-at (:at (first (:queue sim)))]
      (if (or (nil? next-at) (> next-at t))
        (assoc sim :now (max (:now sim) t))
        (recur (check-steps (step sim)))))))

(defn run-until-idle
  "Run until nothing is pending.

  Note that a protocol with a periodic timer is *never* idle; use `run-until`
  for those, which is most of them."
  [sim]
  (loop [sim sim]
    (if (idle? sim)
      sim
      (recur (check-steps (step sim))))))

(defn run-steps
  "Process at most `n` events."
  [sim n]
  (loop [sim sim
         n n]
    (if (or (zero? n) (idle? sim))
      sim
      (recur (step sim) (dec n)))))

;; =============================================================================
;; Inspection
;; =============================================================================

(defn node-state
  "The current state of `id`."
  [sim id]
  (get-in sim [:nodes id :state]))

(defn node-ids [sim] (set (keys (:nodes sim))))

(defn messages-to
  "Traced messages delivered to `id`."
  [sim id]
  (filter #(and (= :message (:kind %)) (= id (:to %))) (:trace sim)))

(defn payloads-to
  "Payloads of traced messages delivered to `id`."
  [sim id]
  (map :payload (messages-to sim id)))
