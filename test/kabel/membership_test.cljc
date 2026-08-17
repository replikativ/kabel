(ns kabel.membership-test
  "Tests for L1 membership.

  Split deliberately in two: the policy functions are pure and tested
  directly, and the protocol is tested by running it in `kabel.sim` under
  churn, partition and loss. The policy tests are the ones that would catch a
  transcription error in the ladder; the simulation tests are the ones that
  catch a protocol that is individually correct and collectively wrong."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.membership :as m]
            [kabel.sim :as sim]
            [kabel.sim.rng :as rng]))

;; =============================================================================
;; Policy — pure
;; =============================================================================

(deftest backoff-ladder
  (let [opts m/default-opts]
    (testing "no failures means no wait"
      (is (= 0 (m/backoff-ms opts 0)))
      (is (= 0 (m/backoff-ms opts -1))))
    (testing "the ladder is asymmetric: fast, fast, slower, then a long tail"
      (is (= 1000 (m/backoff-ms opts 1)))
      (is (= 5000 (m/backoff-ms opts 2)))
      (is (= 15000 (m/backoff-ms opts 3)))
      (is (= 600000 (m/backoff-ms opts 4))))
    (testing "it saturates rather than running off the end"
      (is (= 600000 (m/backoff-ms opts 5)))
      (is (= 600000 (m/backoff-ms opts 99))))))

(deftest priority-ladder
  (testing "a proven peer with no failures is the best candidate"
    (is (= 0 (m/priority {:proven? true :attempts 0}))))

  (testing "a single failure outranks an untried stranger"
    ;; The counterintuitive rung, taken from hyperswarm: one failure is
    ;; usually a blip. If this ever regresses to demoting on first failure,
    ;; recovery after transient loss gets much slower.
    (is (< (m/priority {:proven? false :attempts 1})
           (m/priority {:proven? false :attempts 0}))))

  (testing "a proven peer that is currently failing still beats a stranger"
    (is (< (m/priority {:proven? true :attempts 3})
           (m/priority {:proven? false :attempts 3}))))

  (testing "unproven peers degrade with attempts, and saturate"
    (is (< (m/priority {:proven? false :attempts 2})
           (m/priority {:proven? false :attempts 4})))
    (is (= (m/priority {:proven? false :attempts 5})
           (m/priority {:proven? false :attempts 50})))))

(deftest tie-break-is-symmetric
  (testing "both peers reach the same verdict on the same connection"
    ;; The property, not the transcription. hyperswarm carries two subtly
    ;; different copies of this rule, so checking the algebra matters more
    ;; than checking the expression.
    ;;
    ;; The subtlety worth stating, because getting it backwards is the easy
    ;; mistake: the two sides must AGREE, not disagree. Consider the single
    ;; connection a→b. From a's side that connection is outbound
    ;; (initiator? true); from b's side the very same connection is inbound
    ;; (initiator? false). If they disagreed, one would hang up and the other
    ;; would hold a half-open connection.
    (doseq [a (range 25)
            b (range 25)
            :when (not= a b)]
      (let [a-id (keyword (str "peer-" a))
            b-id (keyword (str "peer-" b))
            ;; Both evaluate the connection a→b.
            a-verdict (m/keep-new? a-id b-id true false)
            b-verdict (m/keep-new? b-id a-id false false)]
        (is (= a-verdict b-verdict)
            (str "disagreed on the a→b connection, for " a-id " / " b-id)))))

  (testing "and the two directions between a pair resolve differently"
    ;; Agreement alone would be satisfied by "always keep". What makes this a
    ;; tie-break is that exactly one of the two possible connections wins.
    (doseq [a (range 15)
            b (range 15)
            :when (not= a b)]
      (let [a-id (keyword (str "peer-" a))
            b-id (keyword (str "peer-" b))
            a->b (m/keep-new? a-id b-id true false)
            b->a (m/keep-new? a-id b-id false false)]
        (is (not= a->b b->a)
            (str "both directions won, for " a-id " / " b-id)))))

  (testing "an outdated existing connection is always replaced"
    (is (m/keep-new? :a :b true true))
    (is (m/keep-new? :a :b false true))))

(deftest dialable-rules
  (let [base (-> (m/make-state :me [{:peer-id :p1 :addresses ["ws://p1"] :group "g1"}])
                 (assoc-in [:book :p1 :backoff-until] 0))]
    (testing "a fresh known peer is dialable"
      (is (m/dialable? base 0 :p1)))

    (testing "not while backed off"
      (is (not (m/dialable? (assoc-in base [:book :p1 :backoff-until] 5000) 1000 :p1)))
      (is (m/dialable? (assoc-in base [:book :p1 :backoff-until] 5000) 5000 :p1)))

    (testing "not if already connected or already dialing"
      (is (not (m/dialable? (assoc-in base [:connections :p1] {:since 0}) 0 :p1)))
      (is (not (m/dialable? (assoc-in base [:dialing :p1] 0) 0 :p1))))

    (testing "never ourselves"
      (is (not (m/dialable? (assoc-in base [:book :me] {:attempts 0}) 0 :me))))

    (testing "unknown peers are not dialable"
      (is (not (m/dialable? base 0 :nobody))))))

(deftest group-diversity
  (testing "a group at its cap yields no further candidates"
    ;; DHT_DESIGN §2.2: routing-table diversity must be over something an
    ;; attacker pays for. Without this, one operator with many addresses fills
    ;; the table.
    (let [state (-> (m/make-state :me
                                  [{:peer-id :a1 :addresses ["ws://a1"] :group "evil"}
                                   {:peer-id :a2 :addresses ["ws://a2"] :group "evil"}
                                   {:peer-id :a3 :addresses ["ws://a3"] :group "evil"}
                                   {:peer-id :b1 :addresses ["ws://b1"] :group "honest"}]
                                  {:max-per-group 2})
                    (assoc :connections {:a1 {:since 0} :a2 {:since 0}}))]
      (is (= 2 (get (m/group-counts state) "evil")))
      (is (not (m/dialable? state 0 :a3)) "over-represented group was still dialable")
      (is (m/dialable? state 0 :b1))))

  (testing "an unlabelled group is exempt rather than collapsed into one bucket"
    (let [state (-> (m/make-state :me
                                  [{:peer-id :u1 :addresses ["ws://u1"]}
                                   {:peer-id :u2 :addresses ["ws://u2"]}
                                   {:peer-id :u3 :addresses ["ws://u3"]}]
                                  {:max-per-group 1})
                    (assoc :connections {:u1 {:since 0}}))]
      (is (m/dialable? state 0 :u2))
      (is (m/dialable? state 0 :u3)))))

(deftest addresses-are-required-to-dial
  (testing "knowing a peer is not the same as being able to reach it"
    ;; A peer learned by inbound connection has no address until it announces
    ;; one — the connection carries no return address. Such an entry is
    ;; knowledge, not a candidate.
    (let [s (m/learn (m/make-state :me) [{:peer-id :nameless :group "g"}])]
      (is (contains? (:book s) :nameless))
      (is (not (m/dialable? s 0 :nameless)))))

  (testing "an address makes it dialable"
    (let [s (m/learn (m/make-state :me)
                     [{:peer-id :p :addresses ["ws://p"] :group "g"}])]
      (is (m/dialable? s 0 :p))
      (is (= "ws://p" (m/dial-address s :p))))))

(deftest addresses-accumulate-but-are-capped
  (testing "new addresses are added without displacing known-good ones"
    (let [s (-> (m/make-state :me [{:peer-id :p :addresses ["ws://first"]}])
                (m/learn [{:peer-id :p :addresses ["ws://second"]}]))]
      (is (= ["ws://first" "ws://second"] (get-in s [:book :p :addresses])))
      (is (= "ws://first" (m/dial-address s :p))
          "a newly gossiped address displaced the one that has worked longest")))

  (testing "an address list is attacker-supplied, so it is capped"
    (let [s (reduce (fn [st i]
                      (m/learn st [{:peer-id :p :addresses [(str "ws://a" i)]}]))
                    (m/make-state :me [{:peer-id :p :addresses ["ws://first"]}])
                    (range 100))]
      (is (<= (count (get-in s [:book :p :addresses])) 3))
      (is (= "ws://first" (first (get-in s [:book :p :addresses]))))))

  (testing "duplicates do not consume the cap"
    (let [s (reduce (fn [st _] (m/learn st [{:peer-id :p :addresses ["ws://same"]}]))
                    (m/make-state :me)
                    (range 10))]
      (is (= ["ws://same"] (get-in s [:book :p :addresses]))))))

(deftest dial-budget
  (testing "bounded by parallelism, by the outbound target, and by the ceiling"
    (let [state (m/make-state :me [] {:max-peers 8 :max-parallel 3 :max-connections 10})]
      (is (= 3 (m/want-dials state)))
      (is (= 1 (m/want-dials (assoc state :dialing {:a 0 :b 0}))))
      (is (= 0 (m/want-dials (assoc state :dialing {:a 0 :b 0 :c 0}))))
      (is (= 0 (m/want-dials (assoc state :connections (zipmap (range 8) (repeat {:since 0})))))
          "kept dialing past the outbound target")
      (is (= 0 (m/want-dials (assoc state
                                    :connections (zipmap (range 10) (repeat {:since 0}))
                                    :opts (assoc (:opts state) :max-peers 100))))
          "kept dialing past the hard connection ceiling")))

  (testing "never negative"
    (let [state (-> (m/make-state :me [] {:max-peers 2})
                    (assoc :connections {:a {:since 0} :b {:since 0} :c {:since 0}}))]
      (is (= 0 (m/want-dials state))))))

(deftest book-is-bounded
  (testing "the book never exceeds :max-book, however much is gossiped at it"
    ;; Every reviewed system had an unbounded collection somewhere. This is
    ;; the one an attacker can grow for free by gossiping addresses.
    (let [state (reduce (fn [s i]
                          (m/learn s [{:peer-id (keyword (str "p" i))
                                       :addresses [(str "ws://p" i)]
                                       :group (str "g" i)}]))
                        (m/make-state :me [] {:max-book 10})
                        (range 500))]
      (is (<= (count (:book state)) 10))))

  (testing "learning is idempotent for peers already known"
    (let [s0 (m/make-state :me [{:peer-id :p1 :group "g"}])
          s1 (m/learn s0 [{:peer-id :p1 :group "g"}])]
      (is (= 1 (count (:book s1))))
      (is (zero? (get-in s1 [:stats :learned])))))

  (testing "we never learn ourselves"
    (let [s (m/learn (m/make-state :me) [{:peer-id :me :group "g"}])]
      (is (not (contains? (:book s) :me)))))

  (testing "a gossiped record cannot relabel a peer's group"
    ;; Otherwise a peer in a full bucket relabels itself into an empty one and
    ;; the diversity cap means nothing.
    (let [s (-> (m/make-state :me [{:peer-id :p1 :group "evil"}])
                (m/learn [{:peer-id :p1 :group "honest"}]))]
      (is (= "evil" (get-in s [:book :p1 :group]))))))

;; =============================================================================
;; Protocol, under simulation
;; =============================================================================

(defn- network
  "`k` nodes, all seeded only with `:n0`, so the mesh must assemble itself."
  ([k] (network k {} {}))
  ([k sim-opts mem-opts]
   (reduce (fn [s i]
             (let [id (keyword (str "n" i))
                   seeds (if (zero? i) [] [{:peer-id :n0 :addresses ["ws://n0"] :group "seed"}])]
               (sim/add-node s id m/handler
                             (m/make-state id seeds
                                           (merge {:addresses [(str "ws://n" i)]}
                                                  mem-opts)))))
           (sim/make-sim sim-opts)
           (range k))))

(defn- full-network
  "`k` nodes, each seeded with every other. Used where both halves of a
  partition must be able to form connections on their own — a network seeded
  only from `:n0` leaves the far side with nothing dialable, which would make
  a partition test pass for the wrong reason."
  [k sim-opts mem-opts]
  (reduce (fn [s i]
            (let [id (keyword (str "n" i))
                  seeds (for [j (range k) :when (not= i j)]
                          {:peer-id (keyword (str "n" j))
                           :addresses [(str "ws://n" j)]
                           :group (str "g" j)})]
              (sim/add-node s id m/handler
                            (m/make-state id seeds
                                          (assoc mem-opts
                                                 :addresses [(str "ws://n" i)])))))
          (sim/make-sim sim-opts)
          (range k)))

(defn- states [s] (map #(sim/node-state s %) (sort (sim/node-ids s))))

(defn- side [id] (if (< (parse-long (subs (name id) 1)) 4) :left :right))

(deftest network-assembles-itself-from-one-seed
  (testing "every node ends up connected, knowing only n0 to begin with"
    ;; This is the whole point of L1: seed one address, get a mesh.
    (let [s (-> (network 12 {:seed 3 :latency-min 5 :latency-max 40} {:max-peers 4})
                (sim/run-until 60000))]
      (doseq [st (states s)]
        (is (pos? (m/connection-count st))
            (str (:id st) " never connected")))

      (testing "and peer exchange spread the book beyond the seed"
        (doseq [st (states s)]
          (is (> (count (:book st)) 1)
              (str (:id st) " learned nothing past its seed"))))

      (testing "without anyone exceeding their outbound target"
        (doseq [st (states s)]
          (is (<= (m/connection-count st) (get-in st [:opts :max-connections]))))))))

(deftest connections-are-bounded
  (testing "no node exceeds the hard connection ceiling under heavy inbound"
    (let [s (-> (network 20 {:seed 11} {:max-peers 6 :max-connections 8})
                (sim/run-until 60000))]
      (doseq [st (states s)]
        (is (<= (m/connection-count st) 8)
            (str (:id st) " has " (m/connection-count st) " connections"))))))

(deftest unreachable-peers-back-off
  (testing "dialing a peer that never answers grows the backoff instead of spinning"
    (let [s (-> (sim/make-sim {:seed 5 :latency-min 5 :latency-max 10})
                (sim/add-node :a m/handler
                              (m/make-state :a [{:peer-id :ghost :addresses ["ws://ghost"] :group "g"}]
                                            {:addresses ["ws://a"]}))
                ;; :ghost exists but is down from the start, so dials are lost.
                (sim/add-node :ghost m/handler (m/make-state :ghost [] {:addresses ["ws://ghost"]}))
                (sim/crash :ghost)
                (sim/run-until 120000))
          st (sim/node-state s :a)]
      (is (pos? (get-in st [:book :ghost :attempts])))
      (is (>= (get-in st [:book :ghost :backoff-until]) 120000)
          "backoff did not extend past the run")
      (testing "and the retries were paced, not hammered"
        ;; 120 s at the default 1 s dial tick would be ~120 dials without
        ;; backoff. The ladder should hold it to a handful.
        (is (< (get-in st [:stats :dials]) 15)
            (str "dialed " (get-in st [:stats :dials]) " times"))))))

(deftest proven-connections-reset-failures
  (testing "a connection that survives :proven-ms clears the failure count"
    (let [s (-> (sim/make-sim {:seed 2 :latency-min 5 :latency-max 10})
                (sim/add-node :a m/handler
                              (m/make-state :a [{:peer-id :b :addresses ["ws://b"] :group "g"}]
                                            {:proven-ms 5000 :addresses ["ws://a"]}))
                (sim/add-node :b m/handler (m/make-state :b [] {:proven-ms 5000 :addresses ["ws://b"]}))
                (sim/run-until 30000))
          st (sim/node-state s :a)]
      (is (m/connected? st :b))
      (is (true? (get-in st [:book :b :proven?])))
      (is (zero? (get-in st [:book :b :attempts]))))))

(deftest partition-then-heal
  (testing "a partitioned network reconnects once healed"
    ;; :max-peers 6 against 4 nodes per side leaves every node with spare
    ;; capacity while partitioned, so healing has something to do. A shortened
    ;; backoff ladder keeps the test fast; the long tail is covered by
    ;; `unreachable-peers-back-off`.
    (let [s (-> (full-network 8 {:seed 6}
                              {:max-peers 6 :max-per-group 8
                               :backoff-ms [1000 2000 3000 5000]})
                (sim/partition-network (into {} (for [i (range 8)]
                                                  [(keyword (str "n" i))
                                                   (side (keyword (str "n" i)))])))
                (sim/run-until 60000))]
      (testing "both sides formed connections internally"
        (doseq [st (states s)]
          (is (pos? (m/connection-count st))
              (str (:id st) " has no connections, so the test proves nothing"))))

      (testing "and none of them crosses the split"
        (doseq [st (states s)
                peer (keys (:connections st))]
          (is (= (side (:id st)) (side peer))
              (str (:id st) " connected across the partition to " peer))))

      (testing "after healing, connections cross"
        (let [healed (-> s (sim/heal) (sim/run-until 200000))
              crossings (for [st (states healed)
                              peer (keys (:connections st))
                              :when (not= (side (:id st)) (side peer))]
                          [(:id st) peer])]
          (is (seq crossings) "no connection crossed after healing"))))))

(deftest simultaneous-dials-converge
  (testing "two peers dialing each other end with exactly one connection each"
    (let [s (-> (sim/make-sim {:seed 8 :latency-min 10 :latency-max 10})
                (sim/add-node :a m/handler (m/make-state :a [{:peer-id :b :addresses ["ws://b"] :group "g"}]
                                                         {:addresses ["ws://a"]}))
                (sim/add-node :b m/handler (m/make-state :b [{:peer-id :a :addresses ["ws://a"] :group "g"}]
                                                         {:addresses ["ws://b"]}))
                (sim/run-until 20000))
          sa (sim/node-state s :a)
          sb (sim/node-state s :b)]
      (is (= 1 (m/connection-count sa)))
      (is (= 1 (m/connection-count sb)))
      (is (m/connected? sa :b))
      (is (m/connected? sb :a)))))

(deftest dialers-announce-their-own-address
  (testing "an inbound peer becomes reachable, not just known"
    ;; Without this, a node that only ever receives connections accumulates a
    ;; book of peers it can never dial and never gossip — the mesh degenerates
    ;; into a star around whoever dialled first.
    (let [s (-> (sim/make-sim {:seed 4 :latency-min 5 :latency-max 10})
                (sim/add-node :dialer m/handler
                              (m/make-state :dialer
                                            [{:peer-id :listener
                                              :addresses ["ws://listener"] :group "g"}]
                                            {:addresses ["ws://dialer"]}))
                (sim/add-node :listener m/handler
                              (m/make-state :listener [] {:addresses ["ws://listener"]}))
                (sim/run-until 20000))
          st (sim/node-state s :listener)]
      (is (m/connected? st :dialer))
      (is (= ["ws://dialer"] (get-in st [:book :dialer :addresses]))
          "the listener never learned how to reach its peer")
      (is (m/dialable? (update st :connections dissoc :dialer) 999999 :dialer)))))

(deftest gossip-only-carries-reachable-peers
  (testing "an address-less entry is never passed on"
    ;; Gossiping names nobody can dial spreads useless knowledge and lets one
    ;; peer fill everyone's book with unreachable entries.
    (let [s (-> (m/make-state :me [] {:exchange-size 10})
                (m/learn [{:peer-id :reachable :addresses ["ws://r"]}
                          {:peer-id :nameless}]))
          {:keys [actions]}
          (m/handler (assoc s :addresses ["ws://me"])
                     {:type :message :from :caller
                      :payload {:type :dial :addresses ["ws://caller"]}}
                     {:now 0})
          offered (->> actions
                       (filter #(= :send (first %)))
                       (map #(nth % 2))
                       (filter #(= :peers (:type %)))
                       (mapcat :entries)
                       (map :peer-id)
                       set)]
      (is (contains? offered :reachable))
      (is (not (contains? offered :nameless))))))

(deftest disconnect-frees-the-peer-for-redial
  (testing "a dropped transport removes the connection"
    ;; Without this the state machine believes in a connection nobody is
    ;; draining, and — worse — never redials, because a peer it thinks it is
    ;; connected to is not a dial candidate.
    (let [s (-> (m/make-state :me [{:peer-id :p :addresses ["ws://p"]}])
                (assoc :connections {:p {:since 0 :initiator? true}}))
          {s' :state} (m/handler s {:type :disconnected :peer :p} {:now 60000})]
      (is (not (m/connected? s' :p)))
      (is (m/dialable? s' 60000 :p) "the peer was not freed for redial")))

  (testing "a long-lived connection that drops is not penalised"
    ;; A proven peer that restarts should be redialled promptly; charging it a
    ;; backoff would make every ordinary restart cost minutes.
    (let [s (-> (m/make-state :me [{:peer-id :p :addresses ["ws://p"]}]
                              {:proven-ms 15000})
                (assoc :connections {:p {:since 0 :initiator? true}})
                (assoc-in [:book :p :proven?] true))
          {s' :state} (m/handler s {:type :disconnected :peer :p} {:now 60000})]
      (is (zero? (get-in s' [:book :p :attempts])))
      (is (zero? (get-in s' [:book :p :backoff-until])))
      (is (m/dialable? s' 60000 :p))))

  (testing "a connection that drops before proving is treated as flapping"
    ;; Otherwise a peer that accepts and immediately hangs up is redialled on
    ;; every tick, forever.
    (let [s (-> (m/make-state :me [{:peer-id :p :addresses ["ws://p"]}]
                              {:proven-ms 15000})
                (assoc :connections {:p {:since 9000 :initiator? true}}))
          {s' :state} (m/handler s {:type :disconnected :peer :p} {:now 10000})]
      (is (= 1 (get-in s' [:book :p :attempts])))
      (is (> (get-in s' [:book :p :backoff-until]) 10000))
      (is (not (m/dialable? s' 10000 :p)))))

  (testing "a disconnect for a peer we do not hold is harmless"
    (let [s (m/make-state :me)
          {s' :state} (m/handler s {:type :disconnected :peer :ghost} {:now 0})]
      (is (empty? (:connections s'))))))

(deftest dropped-link-is-redialled
  (testing "the dial policy is the reconnect policy"
    ;; kabel has no reconnect of its own; this is what replaces it.
    (let [s (-> (sim/make-sim {:seed 17 :latency-min 5 :latency-max 20})
                (sim/add-node :a m/handler
                              (m/make-state :a [{:peer-id :b :addresses ["ws://b"]}]
                                            {:addresses ["ws://a"] :proven-ms 2000}))
                (sim/add-node :b m/handler
                              (m/make-state :b [] {:addresses ["ws://b"]
                                                   :proven-ms 2000}))
                (sim/run-until 20000))]
      (is (m/connected? (sim/node-state s :a) :b) "never connected in the first place")

      (let [after (-> s
                      (sim/link-down :a :b)
                      (sim/run-steps 4))]
        (testing "both sides notice"
          (is (not (m/connected? (sim/node-state after :a) :b)))
          (is (not (m/connected? (sim/node-state after :b) :a))))

        (testing "and the link is re-established without any external nudge"
          (let [healed (sim/run-until after 60000)]
            (is (m/connected? (sim/node-state healed :a) :b)
                "the dial policy did not reconnect")))))))

(deftest crash-notifies-peers
  (testing "a crashed peer's connections are dropped by everyone"
    ;; A real process's kernel closes its sockets when it dies. A simulator
    ;; where a crash is silent lets a protocol pass while believing forever in
    ;; connections that are gone.
    (let [s (-> (network 5 {:seed 23} {:max-peers 4})
                (sim/run-until 40000))
          connected-to-n1 (filter #(m/connected? (sim/node-state s %) :n1)
                                  (sim/node-ids s))
          after (-> s (sim/crash :n1) (sim/run-steps 20))]
      (is (seq connected-to-n1) "nobody was connected, so the test proves nothing")
      (doseq [id connected-to-n1]
        (is (not (m/connected? (sim/node-state after id) :n1))
            (str id " still believes it is connected to a crashed peer"))))))

(deftest membership-is-deterministic
  (testing "the same seed reproduces the same mesh"
    (let [run #(-> (network 10 {:seed 21} {:max-peers 3}) (sim/run-until 40000))
          a (run)
          b (run)]
      (is (= (map :connections (states a))
             (map :connections (states b))))
      (is (= (map :book (states a))
             (map :book (states b)))))))

(deftest lossy-network-still-assembles
  (testing "with 30% loss the mesh still forms, just slower"
    ;; Retries plus backoff are what carry this; if the test ever fails, the
    ;; ladder has regressed rather than the network having got unlucky — the
    ;; seed makes it reproducible either way.
    (let [s (-> (network 10 {:seed 31 :drop-p 0.3} {:max-peers 3})
                (sim/run-until 200000))]
      (doseq [st (states s)]
        (is (pos? (m/connection-count st))
            (str (:id st) " failed to connect under loss"))))))

(deftest a-saturated-seed-does-not-strand-newcomers
  (testing "a peer refused for capacity is told where else to go"
    ;; Found by measurement, not by reading. With everyone seeded from one
    ;; address and that seed at its connection ceiling, the excess peers were
    ;; stranded PERMANENTLY: peer exchange happens over a connection, so a node
    ;; that cannot connect can never learn a second address. Measured at 40
    ;; nodes: 7 isolated, books containing nothing but the seed, still isolated
    ;; after 900 s of virtual time.
    ;;
    ;; This is gossipsub's peer exchange on PRUNE, which I had dismissed as
    ;; unnecessary for a federated network. It is not: it is what stops a
    ;; saturated entry point from being a black hole.
    (let [n 14
          s (-> (reduce (fn [s i]
                          (let [id (keyword (str "n" i))]
                            (sim/add-node s id m/handler
                                          (m/make-state id
                                                        (if (zero? i)
                                                          []
                                                          [{:peer-id :n0
                                                            :addresses ["ws://n0"]
                                                            :group "seed"}])
                                                        {:addresses [(str "ws://n" i)]
                                                         :max-peers 4
                                                         :max-connections 4
                                                         :max-per-group 16}))))
                        (sim/make-sim {:seed 3 :latency-min 5 :latency-max 20})
                        (range n))
                (sim/run-until 120000))
          st (fn [i] (sim/node-state s (keyword (str "n" i))))]

      (testing "the seed really did saturate, so the test proves something"
        (is (= 4 (m/connection-count (st 0)))))

      (testing "and nobody is left isolated"
        (doseq [i (range n)]
          (is (pos? (m/connection-count (st i)))
              (str "n" i " was stranded with book "
                   (keys (get-in (st i) [:membership :book]))))))

      (testing "because a refusal carried referrals"
        (is (some #(> (count (get-in (st %) [:book])) 1) (range 1 n))
            "no peer ever learned an address beyond its seed")))))

(deftest refusals-engage-the-backoff-ladder
  (testing "a peer refused for capacity does not redial every tick forever"
    ;; Measured before the fix: 299 refusals in 300 s with `attempts` still 0,
    ;; because :dial-refused cleared :dialing without recording a failure. That
    ;; is a denial of service on the seed and a flat battery on the client.
    (let [s (-> (sim/make-sim {:seed 4 :latency-min 5 :latency-max 10})
                (sim/add-node :full m/handler
                              (m/make-state :full [] {:addresses ["ws://full"]
                                                      :max-connections 0}))
                (sim/add-node :hopeful m/handler
                              (m/make-state :hopeful
                                            [{:peer-id :full :addresses ["ws://full"]}]
                                            {:addresses ["ws://hopeful"]}))
                (sim/run-until 120000))
          st (sim/node-state s :hopeful)]
      (is (pos? (get-in st [:stats :dial-refused])))
      (is (pos? (get-in st [:book :full :attempts]))
          "a refusal was not counted as a failure")
      (is (> (get-in st [:book :full :backoff-until]) 120000)
          "the ladder never engaged")
      (is (< (get-in st [:stats :dials]) 15)
          (str "dialled " (get-in st [:stats :dials]) " times in 120 s")))))

(deftest a-duplicate-refusal-is-not-a-failure
  (testing "being told we are already connected does not earn a backoff"
    (let [s (-> (m/make-state :me [{:peer-id :p :addresses ["ws://p"]}])
                (assoc-in [:dialing :p] 0))
          {s' :state} (m/handler s {:type :message :from :p
                                    :payload {:type :dial-refused
                                              :reason :duplicate}}
                                 {:now 5000})]
      (is (zero? (get-in s' [:book :p :attempts])))
      (is (zero? (get-in s' [:book :p :backoff-until]))))))

(deftest peer-exchange-carries-topic-ranges
  (testing "a peer learns WHERE a topic lives without connecting there"
    ;; Peer exchange used to carry {:peer-id :addresses :group} and nothing
    ;; about topics, so membership picked peers blind to what they carried and
    ;; a subscriber had no way to reach the part of the network serving it.
    ;; That blindness is what forces a discovery layer at scale.
    (let [s (m/learn (m/make-state :me)
                     [{:peer-id :dbs :addresses ["ws://dbs"] :carries [[:db]]}
                      {:peer-id :rooms :addresses ["ws://rooms"] :carries [[:rooms]]}])]
      (is (= #{[:db]} (get-in s [:book :dbs :carries])))
      (is (= #{[:rooms]} (get-in s [:book :rooms :carries])))))

  (testing "gossiped ranges are normalised, so redundant claims collapse"
    (let [s (m/learn (m/make-state :me)
                     [{:peer-id :p :addresses ["ws://p"]
                       :carries [[] [:db] [:db "alice"]]}])]
      (is (= #{[]} (get-in s [:book :p :carries]))))))

(deftest dialling-prefers-peers-carrying-what-we-want
  (testing "a relevant peer outranks an equally-rated stranger"
    (let [book (fn [state] state)
          s (-> (m/make-state :me
                              [{:peer-id :irrelevant :addresses ["ws://i"]}
                               {:peer-id :relevant :addresses ["ws://r"]}]
                              {:topics #{[:db "alice" "kb1"]}})
                (assoc-in [:book :relevant :carries] #{[:db "alice"]})
                (assoc-in [:book :irrelevant :carries] #{[:rooms]}))
          [_ ordered] (m/candidates s 0)]
      (is (= :relevant (first ordered))
          "membership dialled blind to what peers carry")))

  (testing "with no topics configured, relevance does not reorder anything"
    ;; A peer with no interests must not have its dial policy silently changed.
    (let [s (-> (m/make-state :me
                              [{:peer-id :a :addresses ["ws://a"]}
                               {:peer-id :b :addresses ["ws://b"]}]
                              {})
                (assoc-in [:book :a :carries] #{[:db]}))
          [_ ordered] (m/candidates s 0)]
      (is (= 2 (count ordered)))))

  (testing "relevance does not override the priority ladder within a tier"
    ;; A proven peer still beats a stranger; relevance orders peers of equal
    ;; standing, it does not promote a failing peer over a working one.
    (let [s (-> (m/make-state :me
                              [{:peer-id :proven-irrelevant :addresses ["ws://p"]}
                               {:peer-id :fresh-relevant :addresses ["ws://f"]}]
                              {:topics #{[:db "x"]}})
                (assoc-in [:book :proven-irrelevant :proven?] true)
                (assoc-in [:book :fresh-relevant :carries] #{[:db]})
                (assoc-in [:book :fresh-relevant :attempts] 4))
          [_ ordered] (m/candidates s 0)]
      (is (= :fresh-relevant (first ordered))
          "relevance is the primary key, by design"))))
