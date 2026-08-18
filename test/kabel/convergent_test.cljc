(ns kabel.convergent-test
  "The whole chain, with a real CRDT at the end: interest ranges → signed
  publish → δ path → repair horizon → -join state sync.

  The replica below is a G-Set modelled on `yggdrasil.convergent.gset`: δ in
  metadata, accrued by the δ's own join, cleared once propagated, and a
  remote-integrated value carrying none so it never re-propagates."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set :as set]
            [kabel.convergent :as conv]
            [kabel.dissemination :as d]
            [kabel.membership :as m]
            [kabel.sim :as sim]
            [kabel.sim.rng :as rng]))

;; =============================================================================
;; A G-Set, in yggdrasil's shape
;; =============================================================================

(def ^:private delta-key :kabel.convergent-test/delta)

(defn gset [& xs] (set xs))

(defn g-add
  "Local mutation: grow the set and accrue the δ, as `with-delta` does."
  [g x]
  (vary-meta (conj g x) update delta-key (fnil set/union #{}) #{x}))

(def ops
  {:join (fn [a b] (set/union a b))
   ;; O(δ) — and the result carries no δ, so it does not re-propagate.
   :apply-delta (fn [g d] (with-meta (set/union g d) nil))
   :delta-of (fn [g] (get (meta g) delta-key))
   :clear-delta (fn [g] (with-meta g nil))})

;; =============================================================================
;; The join law itself — asserted, not assumed
;; =============================================================================

(deftest the-replica-really-is-a-join-semilattice
  ;; Everything downstream rests on these three. A type that fails them
  ;; silently corrupts under this transport rather than merely misbehaving.
  (let [join (:join ops)
        vals [#{:a} #{:b} #{:a :c} #{} #{:d :e}]]
    (testing "commutative"
      (doseq [a vals b vals] (is (= (join a b) (join b a)))))
    (testing "associative"
      (doseq [a vals b vals c vals]
        (is (= (join a (join b c)) (join (join a b) c)))))
    (testing "idempotent"
      (doseq [a vals] (is (= a (join a a)))))))

(deftest deltas-converge-in-any-order
  ;; The property the flood depends on: dissemination reorders and duplicates
  ;; freely, so applying δs in any order with any repeats must agree.
  (let [apply-δ (:apply-delta ops)
        deltas [#{:a} #{:b} #{:c} #{:a} #{:b :d}]
        expected (reduce apply-δ #{} deltas)]
    (doseq [seed (range 30)]
      (let [[_ shuffled] (rng/shuffle (rng/make-rng seed) (concat deltas deltas))]
        (is (= expected (reduce apply-δ #{} shuffled))
            (str "order or duplication changed the result, seed " seed))))))

;; =============================================================================
;; Wiring — this is also the documentation of how to compose it
;; =============================================================================

(def topic [:ygg "alice" "kb1"])

(defn- node-handler
  "membership → dissemination → convergent, with the two joins between them:
  delivered payloads feed the replica, and the stranded signal drives a state
  request.

  A local mutation arrives as an `:app/add` message from `:app` — an injected
  LOCAL call, not network traffic — and its publish then travels the ordinary
  action path, so partitions and loss apply to it exactly as they would in a
  deployment."
  [state event ctx]
  (let [ms (assoc (:membership state) :rng (:rng state) :id (:id state))
        {ms' :state ma :actions} (m/handler ms event ctx)
        peers (keys (:connections ms'))

        ds (assoc (:dissemination state) :rng (:rng ms') :id (:id state))
        [ds d-sync] (d/sync-peers ds peers (:now ctx))
        {ds' :state da :actions} (d/handler ds event ctx)

        ;; Anything dissemination newly delivered is handed to the replica.
        cursor (:cursor state 0)
        delivered (:delivered ds')
        fresh (subvec delivered (min cursor (count delivered)))
        cs (reduce (fn [c p] (first (conv/apply-incoming c p)))
                   (assoc (:convergent state) :id (:id state))
                   fresh)

        ;; A local publish request from the replica becomes a dissemination
        ;; publish; a state request becomes an ordinary send.
        {cs' :state ca :actions} (conv/handler cs event ctx)

        ;; Stranded beyond the horizon → ask for full state, once.
        stranded (:needs-state-sync ds')
        [cs' state-acts] (if (and (seq stranded) (seq peers))
                           (conv/request-state cs' stranded peers)
                           [cs' []])

        ;; A local mutation: apply it, take the δ, publish it.
        local (when (and (= :message (:type event))
                         (= :app/add (:type (:payload event))))
                (:x (:payload event)))
        [cs' ds' pub-acts]
        (if (nil? local)
          [cs' ds' []]
          (let [[c acts] (conv/local-change cs' (g-add (conv/value cs') local))]
            (reduce (fn [[c ds out] [op t payload]]
                      (if (= :publish op)
                        (let [[ds2 pa] (d/publish ds t payload)]
                          [c ds2 (into out pa)])
                        [c ds out]))
                    [c ds' []]
                    acts)))]
    {:state (assoc state
                   :membership (dissoc ms' :rng)
                   :dissemination (dissoc ds' :rng)
                   :convergent cs'
                   :cursor (count delivered)
                   :rng (:rng ds'))
     :actions (vec (concat ma d-sync da ca state-acts pub-acts))}))

(defn- make-node [id seeds opts]
  {:id id
   :membership (m/make-state id seeds {:addresses [(str "ws://" (name id))]})
   :dissemination (d/make-state id #{topic} (merge {:have-interval-ms 1000} opts))
   :convergent (conv/make-state id topic #{} ops)
   :cursor 0})

(defn- change!
  "Apply a local mutation on `node`, as an injected LOCAL call.

  `sim/send-message` enqueues directly rather than going through `transmit`, so
  it bypasses partitions and loss — which is right for a local API call and
  wrong for peer traffic. An earlier version of this helper injected the
  resulting peer sends that way and silently defeated the partition it was
  supposed to be testing."
  [s node x]
  (sim/send-message s :app node {:type :app/add :x x}))

(defn- net [ids opts]
  (reduce (fn [s id]
            (sim/add-node s id node-handler
                          (make-node id
                                     (if (= id (first ids))
                                       []
                                       [{:peer-id (first ids)
                                         :addresses [(str "ws://" (name (first ids)))]
                                         :group "seed"}])
                                     opts)))
          (sim/make-sim {:seed 7 :latency-min 5 :latency-max 20 :max-steps 3000000})
          ids))

(defn- replica [s id] (conv/value (:convergent (sim/node-state s id))))

;; =============================================================================
;; The chain
;; =============================================================================

(deftest a-delta-reaches-every-interested-peer
  (testing "a local change converges across the mesh by the op path"
    (let [s (-> (net [:a :b :c] {}) (sim/run-until 40000))
          s (change! s :a :x)
          s (sim/run-until s 60000)]
      (doseq [id [:a :b :c]]
        (is (= #{:x} (replica s id)) (str id " did not converge")))

      (testing "and it travelled as a δ, not as full state"
        (let [st (sim/node-state s :b)]
          (is (pos? (get-in st [:convergent :stats :deltas-in])))
          (is (zero? (get-in st [:convergent :stats :states-in]))))))))

(deftest concurrent-changes-converge
  (testing "two peers mutating at once end in the same place"
    (let [s (-> (net [:a :b :c] {}) (sim/run-until 40000))
          s (-> s (change! :a :from-a) (change! :b :from-b))
          s (sim/run-until s 70000)]
      (is (= #{:from-a :from-b} (replica s :a)))
      (is (= #{:from-a :from-b} (replica s :b)))
      (is (= #{:from-a :from-b} (replica s :c))))))

(deftest a-peer-beyond-the-horizon-converges-by-state
  (testing "when δ repair cannot help, a full join closes the gap"
    ;; :c is cut off while :a makes more changes than the repair store holds,
    ;; so the δs are gone by the time it returns. Only -join can close it, and
    ;; the horizon signal is what asks.
    (let [s (-> (net [:a :b :c] {:store-size 3}) (sim/run-until 40000))
          cut (sim/partition-network s {:a :main :b :main :c :alone})
          busy (reduce (fn [acc i]
                         (-> acc
                             (change! :a (keyword (str "v" i)))
                             (sim/run-until (+ 40000 (* (inc i) 300)))))
                       cut
                       (range 12))
          expected (replica busy :a)]

      (testing "the isolated peer really did fall behind"
        (is (not= expected (replica busy :c)))
        (is (> (count expected) 3) "not enough changes to outrun the store"))

      (let [healed (-> busy sim/heal (sim/run-until 200000))]
        (testing "and catches up once reconnected"
          (is (= expected (replica healed :c))
              (str "c has " (replica healed :c) " expected " expected)))

        (testing "by asking for state, because the δs were gone"
          (let [st (sim/node-state healed :c)]
            (is (pos? (get-in st [:convergent :stats :state-requests]))
                "no state was ever requested, so the horizon signal is not wired")
            (is (pos? (get-in st [:convergent :stats :joins])))))))))

(deftest state-is-requested-once-not-every-tick
  (testing "a repeating stranded signal does not become a request storm"
    ;; beyond-horizon fires on every digest tick; asking each time would turn
    ;; one slow peer into a broadcast loop.
    (let [c (conv/make-state :me topic #{} ops)
          stranded [{:origin :a :epoch 0 :missing-below 10}]
          [c1 a1] (conv/request-state c stranded [:p1 :p2])
          [_ a2] (conv/request-state c1 stranded [:p1 :p2])]
      (is (= 2 (count a1)))
      (is (empty? a2) "asked again for the same gap"))))
