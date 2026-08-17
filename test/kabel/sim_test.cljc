(ns kabel.sim-test
  "Tests for the deterministic simulator.

  The style to notice: faults are asserted by their **effect on node state**,
  not by a counter. `.internal/reference/gossipsub.md` found two defences that
  were dead code with passing counter-shaped tests, and
  `.internal/reference/partisan.md` found a peer selector that was not random.
  A test that says `(is (= 3 (:dropped stats)))` would pass against a
  simulator that dropped the wrong three messages."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.sim :as sim]
            [kabel.sim.rng :as rng]))

;; =============================================================================
;; A minimal flooding protocol, used as the subject under test
;; =============================================================================

(defn flood-handler
  "Flood with duplicate suppression: forward each message id once, to every
  peer except the one it arrived from."
  [state event _ctx]
  (case (:type event)
    :init
    {:state state :actions []}

    :message
    (let [{:keys [mid body]} (:payload event)]
      (if (contains? (:seen state) mid)
        {:state (update state :dups inc) :actions []}
        {:state (-> state
                    (update :seen conj mid)
                    (update :received conj body))
         :actions (vec (for [p (:peers state)
                             :when (not= p (:from event))]
                         [:send p (:payload event)]))}))

    :timer
    {:state (update state :ticks inc) :actions []}

    ;; Handlers need a default: the simulator may introduce event types (it
    ;; grew :disconnected after this was first written), and a `case` without
    ;; one turns that into a crash rather than an ignored event.
    {:state state :actions []}))

(defn flood-state [peers]
  {:peers (vec peers) :seen #{} :received [] :dups 0 :ticks 0})

(defn line-network
  "Nodes :n0..:n(k-1), each connected to its neighbours."
  [opts k]
  (reduce (fn [s i]
            (let [id (keyword (str "n" i))
                  peers (cond-> []
                          (> i 0) (conj (keyword (str "n" (dec i))))
                          (< i (dec k)) (conj (keyword (str "n" (inc i)))))]
              (sim/add-node s id flood-handler (flood-state peers))))
          (sim/make-sim opts)
          (range k)))

(defn ring-network
  "Nodes :n0..:n(k-1) in a ring.

  A ring rather than a line wherever duplicate arrival matters: a line has no
  cycles, so a flood that forwards away from the sender never delivers a
  second copy and duplicate suppression is never exercised."
  [opts k]
  (reduce (fn [s i]
            (let [id (keyword (str "n" i))
                  peers [(keyword (str "n" (mod (dec i) k)))
                         (keyword (str "n" (mod (inc i) k)))]]
              (sim/add-node s id flood-handler (flood-state (distinct peers)))))
          (sim/make-sim opts)
          (range k)))

(defn received [s id] (set (:received (sim/node-state s id))))

;; =============================================================================
;; Determinism
;; =============================================================================

(deftest identical-seeds-produce-identical-runs
  (testing "the whole run is a pure function of the seed"
    (let [run (fn []
                (-> (line-network {:seed 1234 :latency-min 5 :latency-max 60} 6)
                    (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                    (sim/run-until 5000)))
          a (run)
          b (run)]
      (is (= (:trace a) (:trace b)))
      (is (= (:stats a) (:stats b)))
      (is (= (:now a) (:now b)))
      (doseq [id (sim/node-ids a)]
        (is (= (sim/node-state a id) (sim/node-state b id))
            (str "node " id " diverged"))))))

(deftest different-seeds-produce-different-interleavings
  (testing "the seed actually drives the schedule"
    (let [run (fn [seed]
                (-> (line-network {:seed seed :latency-min 5 :latency-max 60} 6)
                    (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                    (sim/run-until 5000)))]
      (is (not= (:trace (run 1)) (:trace (run 2)))))))

;; =============================================================================
;; Delivery
;; =============================================================================

(deftest flooding-reaches-every-node
  (testing "a message injected at one end arrives everywhere"
    (let [s (-> (line-network {:seed 7} 6)
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 10000))]
      (doseq [id (sim/node-ids s)]
        (is (= #{"hello"} (received s id)) (str id " missed the message"))))))

(deftest duplicate-suppression-holds
  (testing "each node applies a message exactly once despite the flood"
    ;; A ring, so copies genuinely race around both ways and collide.
    (let [s (-> (ring-network {:seed 7} 6)
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 10000))]
      (doseq [id (sim/node-ids s)]
        (is (= 1 (count (:received (sim/node-state s id))))
            (str id " applied the message more than once")))
      (testing "and duplicates did in fact arrive, so suppression was exercised"
        ;; Without this, the test above would also pass on a network that
        ;; never delivered a second copy — proving nothing about suppression.
        (is (pos? (reduce + (map #(:dups (sim/node-state s %)) (sim/node-ids s)))))))))

(deftest latency-is-drawn-within-bounds
  (testing "a fixed latency puts deliveries at exactly the expected times"
    (let [s (-> (line-network {:seed 3 :latency-min 10 :latency-max 10} 3)
                (sim/send-message :ext :n0 {:mid 1 :body "x"})
                (sim/run-until 1000))
          arrivals (->> (:trace s)
                        (filter #(= :message (:kind %)))
                        (map (juxt :to :at))
                        (into {}))]
      ;; injected at t=0, so n0 at 0, n1 one hop later, n2 two hops later.
      (is (= 0 (get arrivals :n0)))
      (is (= 10 (get arrivals :n1)))
      (is (= 20 (get arrivals :n2))))))

;; =============================================================================
;; Faults — asserted by effect
;; =============================================================================

(deftest partition-prevents-delivery
  (testing "a message cannot cross a partition"
    (let [s (-> (line-network {:seed 5} 6)
                (sim/partition-network {:n0 :a :n1 :a :n2 :a
                                        :n3 :b :n4 :b :n5 :b})
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 10000))]
      (testing "the near side has it"
        (doseq [id [:n0 :n1 :n2]]
          (is (= #{"hello"} (received s id)) (str id " should have received"))))
      (testing "the far side has NOTHING — the effect, not a counter"
        (doseq [id [:n3 :n4 :n5]]
          (is (= #{} (received s id)) (str id " received across a partition")))))))

(deftest healing-restores-delivery
  (testing "traffic sent after a heal crosses"
    (let [s (-> (line-network {:seed 5} 6)
                (sim/partition-network {:n0 :a :n1 :a :n2 :a
                                        :n3 :b :n4 :b :n5 :b})
                (sim/send-message :ext :n0 {:mid 1 :body "before"})
                (sim/run-until 2000)
                (sim/heal)
                (sim/send-message :ext :n0 {:mid 2 :body "after"})
                (sim/run-until 20000))]
      (is (= #{"before" "after"} (received s :n0)))
      (testing "the far side got only what was sent after the heal"
        (doseq [id [:n3 :n4 :n5]]
          (is (= #{"after"} (received s id)) (str id " has " (received s id))))))))

(deftest reachability-predicate
  (testing "reachable? reflects the partition"
    (let [s (-> (sim/make-sim)
                (sim/partition-network {:a :left :b :left :c :right}))]
      (is (sim/reachable? s :a :b))
      (is (not (sim/reachable? s :a :c)))
      (is (sim/reachable? (sim/heal s) :a :c)))))

(deftest crashed-nodes-receive-nothing
  (testing "a crashed node does not apply messages, and blocks the flood"
    (let [s (-> (line-network {:seed 9} 5)
                (sim/crash :n2)
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 10000))]
      (is (= #{"hello"} (received s :n0)))
      (is (= #{"hello"} (received s :n1)))
      (is (= #{} (received s :n2)) "a crashed node applied a message")
      (testing "and nothing behind it heard, since the line is cut"
        (is (= #{} (received s :n3)))
        (is (= #{} (received s :n4)))))))

(deftest restart-preserves-state-and-re-inits
  (testing "a crash is not amnesia"
    (let [s (-> (line-network {:seed 9} 3)
                (sim/send-message :ext :n0 {:mid 1 :body "first"})
                (sim/run-until 2000)
                (sim/crash :n1)
                (sim/restart :n1)
                (sim/run-until 4000))]
      (is (= #{"first"} (received s :n1)) "state was lost across restart")
      (is (sim/up? s :n1))))

  (testing "forget is amnesia"
    (let [s (-> (line-network {:seed 9} 3)
                (sim/send-message :ext :n0 {:mid 1 :body "first"})
                (sim/run-until 2000)
                (sim/forget :n1))]
      (is (nil? (sim/node-state s :n1)))
      (is (not (contains? (sim/node-ids s) :n1))))))

(deftest message-loss
  (testing "with total loss nothing propagates past the injection"
    (let [s (-> (line-network {:seed 4 :drop-p 1.0} 5)
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 10000))]
      ;; The injected message is not subject to loss; forwarded copies are.
      (is (= #{"hello"} (received s :n0)))
      (doseq [id [:n1 :n2 :n3 :n4]]
        (is (= #{} (received s id))))))

  (testing "with no loss configured, nothing is lost"
    (let [s (-> (line-network {:seed 4 :drop-p 0.0} 5)
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 10000))]
      (is (zero? (get-in s [:stats :dropped-loss]))))))

;; =============================================================================
;; Timers and scheduling
;; =============================================================================

(defn ticker-handler
  "Re-arms a timer every `period` until `limit` ticks have happened."
  [state event _ctx]
  (case (:type event)
    :init {:state state :actions [[:timer (:period state) :tick]]}
    :timer (let [state (update state :ticks inc)]
             {:state state
              :actions (if (< (:ticks state) (:limit state))
                         [[:timer (:period state) :tick]]
                         [])})
    {:state state :actions []}))

(deftest timers-fire-on-the-virtual-clock
  (testing "a periodic timer fires at the expected virtual times"
    (let [s (-> (sim/make-sim {:seed 1})
                (sim/add-node :t ticker-handler {:period 100 :limit 5 :ticks 0})
                (sim/run-until 1000))]
      (is (= 5 (:ticks (sim/node-state s :t))))
      (is (= [100 200 300 400 500]
             (->> (:trace s) (filter #(= :timer (:kind %))) (map :at))))))

  (testing "run-until stops at the boundary"
    (let [s (-> (sim/make-sim {:seed 1})
                (sim/add-node :t ticker-handler {:period 100 :limit 50 :ticks 0})
                (sim/run-until 250))]
      (is (= 2 (:ticks (sim/node-state s :t))))
      (is (= 250 (:now s))))))

(deftest scheduled-churn
  (testing "`at` injects a fault at a chosen virtual time"
    (let [s (-> (line-network {:seed 2 :latency-min 10 :latency-max 10} 4)
                (sim/at 15 #(sim/crash % :n2))
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 5000))]
      ;; n1 gets it at t=10, forwards to n2 arriving t=20 — after the crash.
      (is (= #{"hello"} (received s :n1)))
      (is (= #{} (received s :n2)))
      (is (not (sim/up? s :n2))))))

;; =============================================================================
;; Guards
;; =============================================================================

(defn runaway-handler [state event _ctx]
  (case (:type event)
    :init {:state state :actions [[:timer 0 :again]]}
    :timer {:state state :actions [[:timer 0 :again]]}
    {:state state :actions []}))

(deftest step-limit-fails-loudly
  (testing "a protocol that schedules faster than it consumes throws"
    ;; Without this the test suite would hang rather than fail, which is the
    ;; worse outcome — a hang gives no seed to reproduce from.
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (-> (sim/make-sim {:seed 1 :max-steps 500})
                     (sim/add-node :r runaway-handler {})
                     (sim/run-until 100000))))))

(deftest unknown-action-is-refused
  (testing "a typo in an action is an error, not a silent no-op"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (-> (sim/make-sim)
                     (sim/add-node :x
                                   (fn [s _ _] {:state s :actions [[:sned :y "oops"]]})
                                   {})
                     (sim/run-until-idle))))))

(deftest sending-to-an-unknown-node-is-counted-not-thrown
  (testing "a stale routing entry must not crash the sender"
    (let [s (-> (sim/make-sim)
                (sim/add-node :x
                              (fn [st ev _]
                                (if (= :init (:type ev))
                                  {:state st :actions [[:send :ghost "hi"]]}
                                  {:state st :actions []}))
                              {})
                (sim/run-until-idle))]
      (is (pos? (get-in s [:stats :dropped-down]))))))

;; =============================================================================
;; Per-node randomness
;; =============================================================================

(defn picker-handler
  "Picks a random peer on init, using only its own seeded rng."
  [state event _ctx]
  (case (:type event)
    :init (let [[rng' choice] (rng/rand-nth (:rng state) (:peers state))]
            {:state (assoc state :rng rng' :choice choice) :actions []})
    {:state state :actions []}))

(deftest per-node-rng-is-seeded-and-independent
  (testing "nodes with identical handlers make different choices"
    ;; If every node shared one rng seed they would all choose alike, and a
    ;; peer-sampling protocol would silently degenerate to everyone picking
    ;; the same peer.
    (let [peers (vec (range 20))
          s (-> (reduce (fn [s i]
                          (sim/add-node s (keyword (str "p" i)) picker-handler
                                        {:peers peers}))
                        (sim/make-sim {:seed 77})
                        (range 12))
                (sim/run-until-idle))
          choices (map #(:choice (sim/node-state s %)) (sort (sim/node-ids s)))]
      (is (> (count (set choices)) 4)
          (str "nodes chose too uniformly: " choices))))

  (testing "and the same simulation seed reproduces the same choices"
    (let [run (fn []
                (let [peers (vec (range 20))]
                  (-> (reduce (fn [s i]
                                (sim/add-node s (keyword (str "p" i)) picker-handler
                                              {:peers peers}))
                              (sim/make-sim {:seed 77})
                              (range 12))
                      (sim/run-until-idle))))
          a (run) b (run)]
      (is (= (map #(:choice (sim/node-state a %)) (sort (sim/node-ids a)))
             (map #(:choice (sim/node-state b %)) (sort (sim/node-ids b)))))))

  (testing "a different simulation seed gives different node seeds"
    (let [choices (fn [seed]
                    (let [peers (vec (range 20))]
                      (->> (-> (reduce (fn [s i]
                                         (sim/add-node s (keyword (str "p" i))
                                                       picker-handler {:peers peers}))
                                       (sim/make-sim {:seed seed})
                                       (range 12))
                               (sim/run-until-idle))
                           ((fn [s] (map #(:choice (sim/node-state s %))
                                         (sort (sim/node-ids s))))))))]
      (is (not= (choices 1) (choices 2))))))

;; =============================================================================
;; Trace
;; =============================================================================

(deftest trace-can-be-disabled
  (testing "long runs need not accumulate an unbounded trace"
    (let [s (-> (line-network {:seed 1 :trace? false} 4)
                (sim/send-message :ext :n0 {:mid 1 :body "hello"})
                (sim/run-until 5000))]
      (is (empty? (:trace s)))
      (testing "but the run still happened"
        (is (= #{"hello"} (received s :n3)))))))
