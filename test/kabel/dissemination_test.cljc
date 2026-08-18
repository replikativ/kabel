(ns kabel.dissemination-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.dissemination :as d]
            [kabel.interval-set :as is*]
            [kabel.overlay :as o]
            [kabel.sim :as sim]
            [kabel.topics :as tp]))

;; =============================================================================
;; Pure
;; =============================================================================

(deftest seen-tracking
  (testing "marking and testing"
    (let [s (-> (d/make-state :me)
                (d/mark-seen :a 0 1)
                (d/mark-seen :a 0 2))]
      (is (d/seen? s :a 0 1))
      (is (d/seen? s :a 0 2))
      (is (not (d/seen? s :a 0 3)))
      (testing "epochs are separate namespaces"
        ;; A restarted node bumps its epoch; without separation its fresh
        ;; sequence numbers would be suppressed as duplicates of the old run.
        (is (not (d/seen? s :a 1 1))))
      (testing "origins are separate namespaces"
        (is (not (d/seen? s :b 0 1))))))

  (testing "a fully received stream costs one range, whatever the volume"
    (let [s (reduce #(d/mark-seen %1 :a 0 %2) (d/make-state :me) (range 5000))]
      (is (= 1 (is*/count-ranges (get-in s [:seen :a 0]))))))

  (testing "summary is capped"
    (let [s (reduce #(d/mark-seen %1 %2 0 1)
                    (d/make-state :me #{} {:max-summary-origins 5})
                    (map #(keyword (str "o" %)) (range 50)))]
      (is (= 5 (count (d/summary s)))))))

(deftest gap-computation
  (testing "we ask only for what a peer claims to hold, and only for gaps"
    (let [mine (-> (d/make-state :me)
                   (d/mark-seen :a 0 1)
                   (d/mark-seen :a 0 2)
                   (d/mark-seen :a 0 5))
          theirs {:a {0 (is*/add-all is*/empty [1 2 3 4 5])}}
          wants (d/gaps-against mine theirs)]
      (is (= [{:origin :a :epoch 0 :lo 3 :hi 4}] wants))))

  (testing "nothing to ask when we are ahead or level"
    (let [mine (reduce #(d/mark-seen %1 :a 0 %2) (d/make-state :me) (range 10))
          theirs {:a {0 (is*/add-all is*/empty (range 5))}}]
      (is (empty? (d/gaps-against mine theirs)))))

  (testing "everything is a gap when we have nothing"
    (let [wants (d/gaps-against (d/make-state :me)
                                {:a {0 (is*/add-all is*/empty [3 4 5])}})]
      (is (= [{:origin :a :epoch 0 :lo 3 :hi 5}] wants)))))

(deftest store-is-bounded
  (testing "the repair store evicts oldest first and never exceeds its cap"
    (let [s (reduce (fn [st i]
                      (first (d/publish st :t {:n i})))
                    (d/make-state :me #{:t} {:store-size 10})
                    (range 100))]
      (is (= 10 (count (:store s))))
      (is (= 10 (count (:store-order s))))
      (testing "and it retains the most recent"
        (is (contains? (:store s) [:me 0 99]))
        (is (not (contains? (:store s) [:me 0 0])))))))

(deftest authorization-is-checked-at-every-hop
  (testing "a refused message is neither delivered nor forwarded"
    (let [s (-> (d/make-state :me #{:secret} {:authorize-fn (fn [topic _] (not= :secret topic))})
                (assoc :peers {:p1 {:interests nil :relay? true}}))
          {s' :state actions :actions}
          (d/handler s {:type :message :from :p0
                        :payload {:type :gossip :topic :secret :origin :o
                                  :epoch 0 :seq 1 :hops 0 :payload "x"}}
                     {:now 0})]
      (is (empty? actions) "an unauthorized message was forwarded")
      (is (empty? (:delivered s')))
      (is (= 1 (get-in s' [:stats :unauthorized])))

      (testing "and is not recorded as seen"
        ;; If it were, a later authorized copy of the same id would be
        ;; suppressed as a duplicate — a refusal that silently poisons the
        ;; message id.
        (is (not (d/seen? s' :o 0 1))))))

  (testing "an authorized message on another topic passes"
    (let [s (-> (d/make-state :me #{:open} {:authorize-fn (fn [topic _] (not= :secret topic))})
                (assoc :peers {:p1 {:interests nil :relay? true}}))
          {s' :state actions :actions}
          (d/handler s {:type :message :from :p0
                        :payload {:type :gossip :topic :open :origin :o
                                  :epoch 0 :seq 1 :hops 0 :payload "x"}}
                     {:now 0})]
      (is (= ["x"] (:delivered s')))
      (is (= 1 (count actions))))))

(deftest no-echo-and-hop-ttl
  (let [base (-> (d/make-state :me #{:t})
                 (assoc :peers {:p1 {:interests nil :relay? true}
                                :p2 {:interests nil :relay? true}}))]
    (testing "a message is never sent back where it came from"
      (let [{actions :actions}
            (d/handler base {:type :message :from :p1
                             :payload {:type :gossip :topic :t :origin :o
                                       :epoch 0 :seq 1 :hops 0 :payload "x"}}
                       {:now 0})]
        (is (= [:p2] (map second actions)))))

    (testing "a message at the hop limit is delivered but not relayed"
      ;; gossipsub has no hop limit at all; a forwarding loop there is bounded
      ;; only by the seen-cache, which is bounded only by time.
      (let [s (assoc-in base [:opts :max-hops] 3)
            {s' :state actions :actions}
            (d/handler s {:type :message :from :p1
                          :payload {:type :gossip :topic :t :origin :o
                                    :epoch 0 :seq 1 :hops 3 :payload "x"}}
                       {:now 0})]
        (is (= ["x"] (:delivered s')) "hop-expired message was not delivered")
        (is (empty? actions) "hop-expired message was relayed anyway")
        (is (= 1 (get-in s' [:stats :hop-expired])))))))

(deftest interest-filtering
  (testing "a topic reaches subscribers and relays, and nobody else"
    (let [s (-> (d/make-state :me #{:t})
                (assoc :peers {:wants   {:interests #{:t} :carries #{}}
                               :other   {:interests #{:u} :carries #{}}
                               :relay   {:interests #{:u} :carries #{tp/everything}}
                               :unknown {:interests nil :carries #{}}}))
          [_ actions] (d/publish s :t "x")
          targets (set (map second actions))]
      (is (contains? targets :wants))
      (is (contains? targets :relay) "a relay must carry topics it does not want")
      (is (contains? targets :unknown) "unannounced interests should be optimistic")
      (is (not (contains? targets :other))))))

(deftest topic-ranges-bound-what-a-relay-carries
  ;; The measured problem a range solves: relaying everything costs 4× relaying
  ;; nothing, and neither extreme is what a deployment wants.
  (testing "a relay carrying a prefix forwards that slice and no more"
    (let [s (-> (d/make-state :me #{})
                (assoc :peers {:alice-relay {:interests #{} :carries #{[:db "alice"]}}
                               :all-db      {:interests #{} :carries #{[:db]}}
                               :everything  {:interests #{} :carries #{tp/everything}}
                               :nothing     {:interests #{} :carries #{}}}))
          targets (fn [topic] (set (map second (second (d/publish s topic "x")))))]

      (testing "inside the range"
        (let [t (targets [:db "alice" "kb1"])]
          (is (contains? t :alice-relay))
          (is (contains? t :all-db))
          (is (contains? t :everything))
          (is (not (contains? t :nothing)))))

      (testing "outside it"
        (let [t (targets [:db "bob" "kb1"])]
          (is (not (contains? t :alice-relay)) "a range leaked past its prefix")
          (is (contains? t :all-db))
          (is (contains? t :everything))))

      (testing "a different branch entirely"
        (let [t (targets [:rooms "x"])]
          (is (not (contains? t :alice-relay)))
          (is (not (contains? t :all-db)))
          (is (contains? t :everything))))))

  (testing "carrying a range is not subscribing to it"
    ;; Ranges say what you RELAY; subscriptions say what you DELIVER. Conflating
    ;; them would silently hand a relay every message under a prefix it merely
    ;; agreed to forward.
    (let [s (-> (d/make-state :me #{} {})
                (assoc :carries #{[:db]})
                (assoc :peers {:p {:interests nil :carries #{tp/everything}}}))
          {s' :state} (d/handler s {:type :message :from :p
                                    :payload {:type :gossip :topic [:db "alice" "kb1"]
                                              :origin :o :epoch 0 :seq 1 :hops 0
                                              :payload "x"}}
                                 {:now 0})]
      (is (empty? (:delivered s')) "a relayed topic was delivered as if subscribed")
      (is (d/seen? s' :o 0 1) "but it was still relayed, so it must be marked seen"))))

;; =============================================================================
;; Under simulation, composed with membership
;; =============================================================================

(defn- overlay-network
  "`k` nodes seeded only from `:n0`, all subscribed to `:t`."
  ([k] (overlay-network k {} {}))
  ([k sim-opts node-opts]
   (reduce (fn [s i]
             (let [id (keyword (str "n" i))]
               (sim/add-node s id o/handler
                             (o/make-state id
                                           (merge {:addresses [(str "ws://n" i)]
                                                   :seeds (if (zero? i)
                                                            []
                                                            [{:peer-id :n0
                                                              :addresses ["ws://n0"]
                                                              :group "seed"}])
                                                   :topics #{:t}
                                                   :membership {:max-peers 4}}
                                                  node-opts)))))
           (sim/make-sim sim-opts)
           (range k))))

(defn- states [s] (map #(sim/node-state s %) (sort (sim/node-ids s))))

(deftest publish-reaches-the-whole-network
  (testing "one seed address, then a publish that arrives everywhere"
    ;; The headline property, and the thing replikativ had to wire by hand.
    (let [s (-> (overlay-network 12 {:seed 3 :latency-min 5 :latency-max 30} {})
                (sim/run-until 60000)
                (sim/send-message :app :n5 {:type :publish :topic :t :payload "hello"})
                (sim/run-until 90000))]
      (doseq [st (states s)]
        (is (= ["hello"] (o/delivered st))
            (str (:id st) " delivered " (o/delivered st)))))))

(deftest each-node-delivers-exactly-once
  (testing "duplicate suppression across a mesh with many cycles"
    (let [s (-> (overlay-network 10 {:seed 4} {:membership {:max-peers 6}})
                (sim/run-until 60000)
                (sim/send-message :app :n0 {:type :publish :topic :t :payload "once"})
                (sim/run-until 90000))]
      (doseq [st (states s)]
        (is (= 1 (count (o/delivered st)))
            (str (:id st) " delivered " (count (o/delivered st)) " copies")))

      (testing "and duplicates really did arrive, so suppression was exercised"
        ;; Without this the test would also pass on a network that happened to
        ;; be a tree.
        (is (pos? (reduce + (map #(get-in % [:dissemination :stats :duplicates])
                                 (states s)))))))))

(deftest repair-fills-a-gap-after-partition
  (testing "a node that missed a publish while partitioned recovers by anti-entropy"
    ;; This is Plumtree's lazy repair path expressed as an interval-set gap
    ;; query, and it is the mechanism that lets the eager path be a plain
    ;; flood: whatever the flood loses, the digest exchange finds.
    (let [assembled (-> (overlay-network 6 {:seed 12}
                                         {:membership {:max-peers 5}
                                          :dissemination {:have-interval-ms 2000}})
                        (sim/run-until 60000))
          ;; Cut :n5 off entirely, publish, then heal.
          isolated (-> assembled
                       (sim/partition-network {:n0 :main :n1 :main :n2 :main
                                               :n3 :main :n4 :main :n5 :alone})
                       (sim/send-message :app :n0 {:type :publish :topic :t :payload "missed"})
                       (sim/run-until 90000))]
      (testing "it genuinely missed the message"
        (is (= [] (o/delivered (sim/node-state isolated :n5))))
        (is (= ["missed"] (o/delivered (sim/node-state isolated :n1)))))

      (testing "and recovers it after healing, without a republish"
        (let [healed (-> isolated (sim/heal) (sim/run-until 400000))]
          (is (= ["missed"] (o/delivered (sim/node-state healed :n5)))
              "anti-entropy did not repair the gap")
          (is (pos? (reduce + (map #(get-in % [:dissemination :stats :want-served])
                                   (states healed))))))))))

(deftest non-subscribers-relay-without-delivering
  (testing "a relay carries a topic it is not subscribed to"
    (let [s (-> (sim/make-sim {:seed 2 :latency-min 5 :latency-max 10})
                (sim/add-node :a o/handler
                              (o/make-state :a {:topics #{:t}
                                                :addresses ["ws://a"]
                                                :seeds [{:peer-id :b
                                                         :addresses ["ws://b"]
                                                         :group "g"}]}))
                ;; :b subscribes to nothing but relays.
                (sim/add-node :b o/handler
                              (o/make-state :b {:topics #{}
                                                :addresses ["ws://b"]
                                                :seeds [{:peer-id :c
                                                         :addresses ["ws://c"]
                                                         :group "g"}]}))
                (sim/add-node :c o/handler (o/make-state :c {:topics #{:t} :addresses ["ws://c"]}))
                (sim/run-until 40000)
                (sim/send-message :app :a {:type :publish :topic :t :payload "through"})
                (sim/run-until 60000))]
      (is (= ["through"] (o/delivered (sim/node-state s :a))))
      (is (= [] (o/delivered (sim/node-state s :b))) "a non-subscriber delivered")
      (is (= ["through"] (o/delivered (sim/node-state s :c)))
          "the relay failed to carry the topic"))))

(deftest overlay-is-deterministic
  (testing "the same seed reproduces the same deliveries and the same mesh"
    (let [run #(-> (overlay-network 8 {:seed 55} {})
                   (sim/run-until 40000)
                   (sim/send-message :app :n2 {:type :publish :topic :t :payload "d"})
                   (sim/run-until 70000))
          a (run) b (run)]
      (is (= (map o/delivered (states a)) (map o/delivered (states b))))
      (is (= (map o/connections (states a)) (map o/connections (states b)))))))

(deftest state-stays-bounded
  (testing "under sustained publishing, stores and delivery logs respect their caps"
    (let [s (reduce (fn [s i]
                      (-> s
                          (sim/send-message :app (keyword (str "n" (mod i 6)))
                                            {:type :publish :topic :t :payload i})
                          (sim/run-until (+ 60000 (* i 200)))))
                    (-> (overlay-network 6 {:seed 9 :trace? false}
                                         {:dissemination {:store-size 20 :max-delivered 30}})
                        (sim/run-until 60000))
                    (range 60))]
      (doseq [st (states s)]
        (is (<= (count (get-in st [:dissemination :store])) 20)
            (str (:id st) " store grew to "
                 (count (get-in st [:dissemination :store]))))
        (is (<= (count (o/delivered st)) 30))
        (testing "and the seen set stays compact rather than growing per message"
          (let [ranges (reduce + 0 (for [[_ epochs] (get-in st [:dissemination :seen])
                                         [_ iset] epochs]
                                     (is*/count-ranges iset)))]
            (is (< ranges 20)
                (str (:id st) " seen set fragmented into " ranges " ranges"))))))))

(deftest authorization-sees-the-payload
  (testing "a policy can decide on the message body, not only the topic"
    ;; "may this key set the root of THAT database" is not answerable from the
    ;; topic alone, and the old positional gate had no room for the payload.
    (let [seen (atom [])
          s (-> (d/make-state :me #{:db/roots}
                              {:authorize (fn [ctx] (swap! seen conj ctx)
                                            (= "mine" (:db (:payload ctx))))})
                (assoc :peers {:p1 {:interests nil :relay? true}}))
          msg (fn [db] {:type :gossip :topic :db/roots :origin :alice
                        :epoch 0 :seq 1 :hops 0 :payload {:db db :root "r"}})
          {allowed :state} (d/handler s {:type :message :from :p0
                                         :payload (msg "mine")} {:now 0})
          {denied :state} (d/handler s {:type :message :from :p0
                                        :payload (assoc (msg "yours") :seq 2)}
                                     {:now 0})]
      (is (= [{:db "mine" :root "r"}] (:delivered allowed)))
      (is (empty? (:delivered denied)))
      (is (= 1 (get-in denied [:stats :unauthorized])))
      (testing "and the principal is the verified origin"
        (is (= :alice (:principal (first @seen))))
        (is (= :publish (:op (first @seen))))))))

(deftest legacy-authorize-fn-still-works
  (testing "the historical (fn [topic origin]) shape is unchanged"
    (let [s (-> (d/make-state :me #{:t}
                              {:authorize-fn (fn [topic _origin] (not= :secret topic))})
                (assoc :peers {:p1 {:interests nil :relay? true}}))
          {denied :state} (d/handler s {:type :message :from :p0
                                        :payload {:type :gossip :topic :secret
                                                  :origin :o :epoch 0 :seq 1
                                                  :hops 0 :payload "x"}}
                                     {:now 0})]
      (is (= 1 (get-in denied [:stats :unauthorized]))))))

(deftest a-new-subscriber-catches-up-without-waiting-for-a-timer
  (testing "the backlog arrives on connect, not on the next digest tick"
    ;; Joining used to cost up to :have-interval-ms before a peer discovered
    ;; anything it had missed: the forward path worked at once, but the backlog
    ;; waited for a timer. For a subscriber, "connected" and "current" are not
    ;; the same thing, and the gap between them was seconds for no reason.
    (let [;; :n0 publishes while :late is absent.
          established (-> (overlay-network 3 {:seed 77 :latency-min 5 :latency-max 20}
                                           {:dissemination {:have-interval-ms 60000}})
                          (sim/run-until 40000)
                          (sim/send-message :app :n0
                                            {:type :publish :topic :t :payload "missed"})
                          (sim/run-until 60000))]
      (is (= ["missed"] (o/delivered (sim/node-state established :n1)))
          "the network never carried the publish, so the test proves nothing")

      ;; A brand-new peer joins, seeded only with :n0.
      (let [joined (-> established
                       (sim/add-node :late o/handler
                                     (o/make-state :late
                                                   {:addresses ["ws://late"]
                                                    :seeds [{:peer-id :n0
                                                             :addresses ["ws://n0"]
                                                             :group "seed"}]
                                                    :topics #{:t}
                                                    :dissemination {:have-interval-ms 60000}}))
                       ;; Well under the 60 s digest interval: if catch-up
                       ;; depended on the timer, nothing could have arrived.
                       (sim/run-until 75000))]
        (is (seq (o/connections (sim/node-state joined :late)))
            "the newcomer never connected")
        (is (= ["missed"] (o/delivered (sim/node-state joined :late)))
            "a new subscriber did not catch up until the digest timer fired")))))

(deftest a-dormant-relay-stops-volunteering
  ;; Mastodon degrades registrations after seven days without an admin login.
  ;; Ours is narrower and more directly useful: a relay carries other people's
  ;; traffic on its operator's behalf, and when that operator stops showing up
  ;; it should stop volunteering. Unattended relaying is how an instance ends
  ;; up hosting content nobody is watching.
  (let [opts {:dormant-after-ms 10000 :have-interval-ms 1000}
        base (-> (d/make-state :relay #{:mine} opts)
                 (assoc :carries #{[:public]}))]

    (testing "a relay with no heartbeat yet is NOT dormant"
      ;; It must not narrow itself before anyone has had a chance to say hello.
      (is (not (d/dormant? base 999999)))
      (is (= #{[:public]} (d/effective-carries base 999999))))

    (let [alive (d/heartbeat! base 0)]
      (testing "and stays live while the operator keeps showing up"
        (is (not (d/dormant? alive 5000)))
        (is (= #{[:public]} (d/effective-carries alive 5000))))

      (testing "but goes dormant once absent past the threshold"
        (is (d/dormant? alive 20000))
        (is (= #{} (d/effective-carries alive 20000))
            "a dormant relay was still volunteering"))

      (testing "while continuing to work for itself"
        ;; It stops serving strangers; it does not stop being a peer.
        (is (= #{:mine} (:topics alive))))

      (testing "and a heartbeat brings it straight back"
        (let [revived (d/heartbeat! alive 20000)]
          (is (not (d/dormant? revived 20001)))
          (is (= #{[:public]} (d/effective-carries revived 20001))))))))

(deftest going-dormant-is-announced
  (testing "peers are told coverage changed, once, on the transition"
    ;; Narrowing silently would leave peers routing to a relay that has stopped
    ;; carrying — and re-announcing every tick would be noise.
    (let [s (-> (d/make-state :relay #{} {:dormant-after-ms 10000
                                          :have-interval-ms 1000})
                (assoc :carries #{[:public]})
                (d/heartbeat! 0)
                (assoc :peers {:p {:interests #{} :carries #{}}}))
          interests-of (fn [{:keys [actions]}]
                         (filter #(= :interests (:type (nth % 2))) actions))
          before (d/handler s {:type :timer :payload :have-tick} {:now 5000})
          crossing (d/handler (:state before) {:type :timer :payload :have-tick}
                              {:now 20000})
          after (d/handler (:state crossing) {:type :timer :payload :have-tick}
                           {:now 21000})]
      (is (empty? (interests-of before)) "announced while nothing had changed")
      (is (= 1 (count (interests-of crossing))) "the transition was not announced")
      (is (= #{} (:carries (nth (first (interests-of crossing)) 2)))
          "announced coverage it no longer provides")
      (is (empty? (interests-of after)) "re-announced on every tick"))))

;; =============================================================================
;; Resource bounds under attack
;; =============================================================================

(deftest serving-a-want-costs-what-it-sends-not-what-it-is-asked
  ;; The vulnerability this closes: serving a :want walked every sequence
  ;; number named, so one small message claiming {:lo 0 :hi 20000000} bought
  ;; ~2.9 seconds of CPU and sent nothing back — remote exhaustion at roughly
  ;; 10^7 : 1, from a peer that spends one map.
  (let [s (d/make-state :me #{:t} {:max-want-span 1024})]
    (testing "an enormous empty range is cheap"
      (let [t0 #?(:clj (System/currentTimeMillis) :cljs (.getTime (js/Date.)))]
        (is (empty? (d/stored s {:origin :x :epoch 0 :lo 0 :hi 50000000})))
        (let [ms (- #?(:clj (System/currentTimeMillis) :cljs (.getTime (js/Date.))) t0)]
          (is (< ms 250) (str "took " ms " ms — the span clamp is not holding")))))

    (testing "and a legitimate request still works"
      (let [[s' _] (d/publish s :t "a")
            [s' _] (d/publish s' :t "b")]
        (is (= 2 (count (d/stored s' {:origin :me :epoch 0 :lo 0 :hi 10}))))))

    (testing "nonsense bounds are refused rather than computed"
      (is (empty? (d/stored s {:origin :x :epoch 0 :lo 100 :hi 1})))))

  (testing "the number of ranges in one :want is capped too"
    ;; Otherwise the span clamp is defeated by sending a thousand ranges.
    (let [s (-> (d/make-state :me #{:t} {:max-want-ranges 4 :max-want 100})
                (as-> st (reduce (fn [a i] (first (d/publish a :t i))) st (range 50))))
          many (vec (for [i (range 50)] {:origin :me :epoch 0 :lo i :hi i}))
          {acts :actions} (d/handler s {:type :message :from :p
                                        :payload {:type :want :ranges many}}
                                     {:now 0})]
      (is (<= (count acts) 4) (str "served " (count acts) " ranges past the cap")))))

(deftest the-seen-set-cannot-be-fragmented-without-bound
  ;; "O(gaps), not O(messages)" is true and incomplete: the gaps are chosen by
  ;; the PUBLISHER. A peer emitting only even sequence numbers produced one
  ;; range per message — 10 000 messages, 10 000 ranges.
  (testing "an honest contiguous stream still costs one range"
    (let [s (reduce (fn [st i] (d/mark-seen st :honest 0 i))
                    (d/make-state :me) (range 5000))]
      (is (= 1 (is*/count-ranges (get-in s [:seen :honest 0]))))))

  (testing "and a deliberately sparse one is capped"
    (let [s (reduce (fn [st i] (d/mark-seen st :attacker 0 (* 2 i)))
                    (d/make-state :me #{} {:max-seen-ranges 64})
                    (range 5000))]
      (is (= 64 (is*/count-ranges (get-in s [:seen :attacker 0]))))))

  (testing "eviction keeps the RECENT end, because that is what repair needs"
    (let [s (reduce (fn [st i] (d/mark-seen st :a 0 (* 2 i)))
                    (d/make-state :me #{} {:max-seen-ranges 8})
                    (range 100))
          iset (get-in s [:seen :a 0])]
      (is (d/seen? s :a 0 198) "the newest message was forgotten")
      (is (not (d/seen? s :a 0 0)) "the oldest should have been evicted")
      (is (= 8 (is*/count-ranges iset))))))
