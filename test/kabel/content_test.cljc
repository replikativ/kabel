(ns kabel.content-test
  (:require [clojure.test :refer [deftest testing is]]
            [hasch.core :refer [uuid]]
            [kabel.content :as c]
            [kabel.overlay :as o]
            [kabel.sim :as sim]))

(defn- content-state [id blocks opts]
  (c/make-state id blocks opts))

(defn- addressed
  "A map of konserve-style content-addressed blocks for `values`."
  [values]
  (into {} (for [v values] [(uuid v) v])))

;; =============================================================================
;; Pure
;; =============================================================================

(deftest verification-is-the-whole-safety-argument
  (testing "a value hashes to its konserve key"
    (let [v {:datoms [[1 :name "a"]]}
          k (uuid v)]
      (is (c/verified? k v))
      (is (not (c/verified? k {:datoms [[1 :name "b"]]})))))

  (testing "a substituted value is rejected even though it is well-formed"
    ;; This is why an untrusted provider is safe: it can serve content it
    ;; cannot forge, and a lie costs one round trip.
    (let [k (uuid {:a 1})]
      (is (not (c/verified? k {:a 2}))))))

(deftest local-holdings-are-bounded
  (testing "the servable set evicts oldest first"
    (let [s (reduce (fn [st i] (c/put-block st i {:n i}))
                    (content-state :me {} {:max-blocks 10})
                    (range 50))]
      (is (= 10 (count (:blocks s))))
      (is (c/have? s 49))
      (is (not (c/have? s 0)))))

  (testing "re-adding what we hold is a no-op"
    (let [s (-> (content-state :me {} {}) (c/put-block :k "v") (c/put-block :k "v"))]
      (is (= 1 (count (:block-order s)))))))

(deftest records-are-bounded-three-ways
  (testing "per key"
    (let [s (reduce (fn [st i] (c/add-record st :k (keyword (str "p" i)) 9999))
                    (content-state :me {} {:max-providers-per-key 3})
                    (range 20))]
      (is (= 3 (count (get-in s [:providers :k]))))
      (is (pos? (get-in s [:stats :records-refused])))))

  (testing "per provider — one peer cannot claim everything"
    ;; `.internal/DHT_DESIGN.md` §6: a single peer announcing itself as
    ;; provider for a million keys should not be able to.
    (let [s (reduce (fn [st i] (c/add-record st (keyword (str "k" i)) :greedy 9999))
                    (content-state :me {} {:max-per-provider 5})
                    (range 100))]
      (is (= 5 (c/provider-count s :greedy)))))

  (testing "overall key count"
    (let [s (reduce (fn [st i] (c/add-record st (keyword (str "k" i))
                                             (keyword (str "p" i)) 9999))
                    (content-state :me {} {:max-keys 4})
                    (range 50))]
      (is (= 4 (count (:providers s))))))

  (testing "refreshing an existing record is always allowed"
    ;; Otherwise an honest provider at the cap could never renew and would
    ;; expire out of a store that is still willing to hold it.
    (let [s (-> (content-state :me {} {:max-providers-per-key 1})
                (c/add-record :k :p1 100)
                (c/add-record :k :p1 500))]
      (is (= 500 (get-in s [:providers :k :p1]))))))

(deftest records-expire
  (testing "stale records are dropped and their keys with them"
    (let [s (-> (content-state :me {} {})
                (c/add-record :k1 :p1 100)
                (c/add-record :k2 :p2 5000))]
      (is (= 2 (count (:providers s))))
      (let [s' (c/expire-records s 1000)]
        (is (= #{:k2} (set (keys (:providers s')))))
        (is (= 1 (get-in s' [:stats :expired]))))))

  (testing "providers-for hides expired records and ourselves"
    (let [s (-> (content-state :me {} {})
                (c/add-record :k :live 5000)
                (c/add-record :k :dead 100)
                (c/add-record :k :me 5000))]
      (is (= [:live] (c/providers-for s :k 1000))))))

;; =============================================================================
;; Under simulation
;; =============================================================================

(defn- net
  "`holder` holds `blocks`; every node knows every other as a peer."
  [ids blocks-by-id sim-opts node-opts]
  (reduce (fn [s id]
            (sim/add-node s id
                          (fn [state event ctx]
                            ;; Peers are static here: content routing is being
                            ;; tested, not membership.
                            (let [[state acts] (c/sync-peers state (remove #{id} ids))
                                  {st :state a2 :actions} (c/handler state event ctx)]
                              {:state st :actions (vec (concat acts a2))}))
                          (content-state id (get blocks-by-id id {}) node-opts)))
          (sim/make-sim sim-opts)
          ids))

(deftest fetch-from-a-direct-provider
  (testing "a peer fetches a value it does not hold"
    (let [v {:root "db-1"}
          k (uuid v)
          s (-> (net [:a :b] {:b (addressed [v])} {:seed 3} {})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch :key k})
                (sim/run-until 20000))
          sa (sim/node-state s :a)]
      (is (c/have? sa k) "the value was never fetched")
      (is (= v (get-in sa [:blocks k])))
      (is (= [k] (:fetched sa))))))

(deftest fetch-through-a-second-hop
  (testing "a query is answered from what a neighbour was told, not only what it holds"
    ;; :a is connected to :b; :c holds the value and announced it to :b. This
    ;; is the two-hop reach, and the honest limit of routing without a DHT.
    (let [v {:root "db-2"}
          k (uuid v)
          ids [:a :b :c]
          s (-> (net ids {:c (addressed [v])} {:seed 5} {})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch :key k})
                (sim/run-until 30000))
          sa (sim/node-state s :a)]
      (is (c/have? sa k))
      (is (= v (get-in sa [:blocks k]))))))

(deftest a-lying-provider-cannot-corrupt-us
  (testing "a block that does not hash to its key is refused, and another provider tried"
    ;; The effect, not a counter: :a must end up with the CORRECT value even
    ;; though a provider served a wrong one under the right key.
    (let [good {:root "honest"}
          k (uuid good)
          bad {:root "tampered"}
          s (-> (net [:a :liar :honest]
                     {:liar {k bad}          ; wrong value under the right key
                      :honest {k good}}
                     {:seed 11} {})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch :key k})
                (sim/run-until 40000))
          sa (sim/node-state s :a)]
      (is (c/have? sa k))
      (is (= good (get-in sa [:blocks k]))
          "a tampered block was accepted")
      (is (pos? (get-in sa [:stats :verify-failed]))
          "the liar was never actually asked, so the test proves nothing"))))

(deftest announcements-cannot-be-made-on-anothers-behalf
  (testing "an announce is credited only to the peer that sent it"
    ;; Otherwise one peer points the whole network at another — free
    ;; amplification, and no signature needed to close it.
    (let [s (content-state :me {} {})
          {s' :state} (c/handler s
                                 {:type :message :from :liar
                                  :payload {:type :content/announce :keys [:k1]}}
                                 {:now 0})]
      (is (= [:liar] (c/providers-for s' :k1 0)))
      (is (not (some #{:victim} (c/providers-for s' :k1 0)))))))

(deftest unsolicited-blocks-are-dropped
  (testing "a block nobody asked for is not stored"
    ;; Accepting unrequested blocks is a free way to fill somebody's cache.
    (let [v {:a 1}
          k (uuid v)
          s (content-state :me {} {})
          {s' :state} (c/handler s
                                 {:type :message :from :p
                                  :payload {:type :content/block :key k :value v}}
                                 {:now 0})]
      (is (not (c/have? s' k))))))

(deftest a-missing-value-fails-rather-than-hanging
  (testing "asking for something nobody holds gives up after bounded tries"
    (let [k (uuid {:nowhere true})
          s (-> (net [:a :b] {} {:seed 7} {:max-tries 2 :want-timeout-ms 1000})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch :key k})
                (sim/run-until 60000))
          sa (sim/node-state s :a)]
      (is (not (c/have? sa k)))
      (is (empty? (:fetched sa)))
      (testing "and the want does not linger, holding an outstanding slot forever"
        ;; The bug this caught: a fetch nobody can answer used to sit in
        ;; :wants permanently, consuming one of :max-outstanding. After enough
        ;; such fetches the peer could never fetch anything again — silent,
        ;; permanent, and invisible to every counter.
        (is (empty? (:wants sa)))
        (is (= [k] (:failed sa))))))

  (testing "and the slot is genuinely reusable afterwards"
    (let [missing (uuid {:nowhere true})
          v {:present true}
          k (uuid v)
          s (-> (net [:a :b] {:b {k v}} {:seed 7}
                     {:max-tries 2 :want-timeout-ms 1000 :max-outstanding 1})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch :key missing})
                (sim/run-until 30000)
                (sim/send-message :app :a {:type :content/fetch :key k})
                (sim/run-until 60000))
          sa (sim/node-state s :a)]
      (is (c/have? sa k)
          "the failed fetch was still holding the only outstanding slot"))))

;; =============================================================================
;; Composed with membership and dissemination
;; =============================================================================

(defn- overlay-net
  "`k` overlay nodes seeded only from `:n0`; `blocks-by-id` says who holds what.

  The whole stack — membership dialling, dissemination, content routing — so
  this is the test that the standalone content tests could not be: they wire
  the peer set by hand, which is exactly the step that could be broken."
  [k blocks-by-id sim-opts]
  (reduce (fn [s i]
            (let [id (keyword (str "n" i))]
              (sim/add-node s id o/handler
                            (o/make-state id
                                          {:addresses [(str "ws://n" i)]
                                           :seeds (if (zero? i)
                                                    []
                                                    [{:peer-id :n0
                                                      :addresses ["ws://n0"]
                                                      :group "seed"}])
                                           :topics #{:db/roots}
                                           :blocks (get blocks-by-id id {})
                                           :membership {:max-peers 4}}))))
          (sim/make-sim sim-opts)
          (range k)))

(deftest end-to-end-publish-then-fetch
  (testing "a root is published, then the database it names is fetched"
    ;; This is the whole system: one seed address assembles a mesh, a publish
    ;; announces a new root, and a peer that wants it locates a provider and
    ;; fetches the value, verifying it on arrival.
    (let [db-value {:eavt "index-root-node" :datoms 12345}
          db-key (uuid db-value)
          s (-> (overlay-net 8 {:n7 {db-key db-value}}
                             {:seed 41 :latency-min 5 :latency-max 30})
                (sim/run-until 90000))]

      (testing "the mesh assembled itself"
        (doseq [id (sim/node-ids s)]
          (is (seq (o/connections (sim/node-state s id)))
              (str id " never connected"))))

      (let [published (-> s
                          (sim/send-message :app :n7
                                            {:type :publish
                                             :topic :db/roots
                                             :payload {:root db-key}})
                          (sim/run-until 130000))]

        (testing "every peer learns the new root"
          (doseq [id (sim/node-ids published)]
            (is (= [{:root db-key}] (o/delivered (sim/node-state published id)))
                (str id " did not learn the root"))))

        (testing "and a peer that wants the value fetches and verifies it"
          (let [fetched (-> published
                            (sim/send-message :app :n3
                                              {:type :content/fetch :key db-key})
                            (sim/run-until 220000))
                st (sim/node-state fetched :n3)]
            (is (o/have? st db-key) "the value was never fetched")
            (is (= db-value (get (o/blocks st) db-key)))

            (testing "and the fetcher becomes a provider itself"
              ;; The seeding behaviour, for free: a verified block is servable.
              (let [second-hop (-> fetched
                                   (sim/send-message :app :n1
                                                     {:type :content/fetch :key db-key})
                                   (sim/run-until 320000))]
                (is (o/have? (sim/node-state second-hop :n1) db-key))))))))))

(deftest requests-beyond-capacity-are-queued-not-dropped
  (testing "a caller may ask for more than :max-outstanding at once"
    ;; Found by measurement, not by reading: `fetch` used to return unchanged
    ;; state when at capacity, so a caller asking for a DAG's worth of keys
    ;; silently lost everything past the sixteenth. No counter moved, no error
    ;; surfaced, and the caller had no way to know.
    (let [s (reduce (fn [st i] (first (c/fetch st (keyword (str "k" i)) 0)))
                    (content-state :me {} {:max-outstanding 4 :max-pending 100})
                    (range 50))]
      (is (= 4 (count (:wants s))))
      (is (= 46 (count (:pending s))))
      (is (= 46 (get-in s [:stats :queued])))))

  (testing "the queue drains as wants complete"
    (let [v {:a 1}
          k (uuid v)
          s (-> (content-state :me {} {:max-outstanding 1 :max-pending 10})
                (assoc :peers #{:p}))
          ;; Two fetches: the first takes the slot, the second queues.
          [s _] (c/fetch s k 0)
          [s _] (c/fetch s :other 0)
          _ (is (= [:other] (:pending s)))
          ;; The first completes; the queued one must start.
          {s' :state} (c/handler s
                                 {:type :message :from :p
                                  :payload {:type :content/block :key k :value v}}
                                 {:now 100})]
      (is (c/have? s' k))
      (is (empty? (:pending s')) "the queued fetch never started")
      (is (contains? (:wants s') :other))))

  (testing "the queue is bounded, and a refusal is counted rather than silent"
    ;; An unbounded queue is the other way this fails.
    (let [s (reduce (fn [st i] (first (c/fetch st (keyword (str "k" i)) 0)))
                    (content-state :me {} {:max-outstanding 1 :max-pending 5})
                    (range 50))]
      (is (= 5 (count (:pending s))))
      (is (pos? (get-in s [:stats :fetch-refused])))))

  (testing "a duplicate request neither re-fetches nor re-queues"
    (let [s (-> (content-state :me {} {:max-outstanding 1})
                (assoc :peers #{:p}))
          [s _] (c/fetch s :a 0)
          [s _] (c/fetch s :b 0)
          [s _] (c/fetch s :b 0)
          [s _] (c/fetch s :a 0)]
      (is (= 1 (count (:wants s))))
      (is (= [:b] (:pending s))))))

(deftest bulk-fetch-from-one-provider
  (testing "hundreds of keys can be fetched from a single peer"
    ;; The regression test for a conflict between two correct-looking rules:
    ;; `:max-per-provider` (a Sybil defence — one peer must not claim a million
    ;; keys) was also capping how many keys we could *ask* one peer for. It made
    ;; the primary use case — pulling a whole index from one provider —
    ;; impossible past 256 keys, and the failures looked like timeouts.
    (let [n 400
          blocks (into {} (for [i (range n)] [(uuid {:node i}) {:node i}]))
          ks (vec (keys blocks))
          s (-> (net [:a :b] {:b blocks} {:seed 3}
                     {:max-blocks (inc n) :max-outstanding 8 :max-pending 1000})
                (sim/run-until 5000))
          s (reduce (fn [acc k] (sim/send-message acc :app :a
                                                  {:type :content/fetch :key k}))
                    s ks)
          done (sim/run-until s 400000)
          st (sim/node-state done :a)]
      (is (= n (count (:fetched st)))
          (str "only " (count (:fetched st)) " of " n " were fetched"))
      (is (empty? (:pending st)))
      (is (empty? (:wants st))))))

(deftest content-routing-is-deterministic
  (testing "the same seed reproduces the same fetches"
    (let [v {:root "d"}
          k (uuid v)
          run #(-> (net [:a :b :c] {:c (addressed [v])} {:seed 31} {})
                   (sim/run-until 5000)
                   (sim/send-message :app :a {:type :content/fetch :key k})
                   (sim/run-until 30000))
          a (run) b (run)]
      (is (= (:fetched (sim/node-state a :a)) (:fetched (sim/node-state b :a))))
      (is (= (:providers (sim/node-state a :a)) (:providers (sim/node-state b :a)))))))

;; =============================================================================
;; Subtree transfer
;; =============================================================================

(defn- tree-blocks
  "A content-addressed tree of `depth` levels with `branching` children each.

  Built bottom-up so every node's address is the hash of a value that already
  names its children — the same shape a persistent-sorted-set has on disk."
  [depth branching]
  (let [build (fn build [level idx]
                (if (zero? level)
                  (let [v {:leaf idx :addresses []}]
                    {:key (uuid v) :entries [[(uuid v) v]]})
                  (let [children (mapv #(build (dec level) (+ (* idx branching) %))
                                       (range branching))
                        v {:level level :idx idx :addresses (mapv :key children)}]
                    {:key (uuid v)
                     :entries (into [[(uuid v) v]] (mapcat :entries children))})))
        {:keys [key entries]} (build depth 0)]
    {:root key :blocks (into {} entries)}))

(deftest walk-is-breadth-first-and-bounded
  (let [blocks {:root {:addresses [:a :b]} :a {:addresses [:c]} :b {} :c {}}
        st (content-state :me blocks {})]
    (testing "parents precede children, so a receiver can verify incrementally"
      (is (= [[:root :a :b :c] []] (c/walk-tree st :root #{} 100 :addresses))))

    (testing "what the requester already holds is pruned, subtree and all"
      ;; The structural-sharing diff: pruning :a also prunes :c. This is what
      ;; makes an update O(changed) rather than O(tree).
      (is (= [[:root :b] []] (c/walk-tree st :root #{:a} 100 :addresses))))

    (testing "the residency bound truncates and reports a resumable frontier"
      ;; A stranger's one-line request must not materialise a whole index.
      (is (= [[:root :a] [:b :c]] (c/walk-tree st :root #{} 2 :addresses))))

    (testing "nodes we do not hold become frontier, not silence"
      ;; Dropping them would hand back an incomplete tree the requester
      ;; believed was complete.
      (is (= [[:root :a :b] [:c]]
             (c/walk-tree (content-state :me (dissoc blocks :c) {})
                          :root #{} 100 :addresses))))

    (testing "a root the requester already has yields nothing"
      (is (= [[] []] (c/walk-tree st :root #{:root} 100 :addresses))))))

(deftest subtree-fetch-in-one-exchange
  (testing "a whole tree arrives without a round trip per node"
    (let [{:keys [root blocks]} (tree-blocks 3 4)   ; 85 nodes
          n (count blocks)
          s (-> (net [:a :b] {:b blocks} {:seed 5}
                     {:max-blocks (inc n) :max-tree-nodes 1000})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch-tree :root root})
                (sim/run-until 40000))
          sa (sim/node-state s :a)]
      (is (= n (count (:blocks sa)))
          (str "got " (count (:blocks sa)) " of " n " nodes"))
      (is (every? #(c/have? sa %) (keys blocks)))
      (is (= n (get-in sa [:stats :tree-nodes-received]))))))

(deftest subtree-fetch-verifies-every-node
  (testing "a tampered node in a batch is rejected while the rest are kept"
    (let [{:keys [root blocks]} (tree-blocks 2 3)
          victim (first (remove #{root} (keys blocks)))
          tampered (assoc blocks victim {:evil true :addresses []})
          n (count blocks)
          s (-> (net [:a :b] {:b tampered} {:seed 6}
                     {:max-blocks (inc n) :max-tree-nodes 1000})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch-tree :root root})
                (sim/run-until 40000))
          sa (sim/node-state s :a)]
      (is (not (c/have? sa victim)) "a tampered node was accepted")
      (is (pos? (get-in sa [:stats :verify-failed])))
      (testing "and the untampered nodes still arrived"
        (is (c/have? sa root))))))

(deftest subtree-fetch-resumes-past-the-residency-bound
  (testing "a truncated walk still converges, via the frontier"
    (let [{:keys [root blocks]} (tree-blocks 3 4)
          n (count blocks)
          s (-> (net [:a :b] {:b blocks} {:seed 8}
                     {:max-blocks (inc n) :max-outstanding 32 :max-pending 500
                      ;; Far below the tree size, so the provider truncates.
                      :max-tree-nodes 5})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch-tree :root root})
                (sim/run-until 200000))
          sa (sim/node-state s :a)]
      (is (pos? (get-in (sim/node-state s :b) [:stats :tree-truncated]))
          "the provider never truncated, so the test proves nothing")
      (is (> (count (:blocks sa)) 5)
          "the frontier was never followed"))))

(deftest unsolicited-tree-batches-are-dropped
  (testing "nodes arriving for a tree nobody asked for are not stored"
    (let [{:keys [root blocks]} (tree-blocks 1 2)
          s (content-state :me {} {})
          {s' :state} (c/handler s
                                 {:type :message :from :p
                                  :payload {:type :content/tree-batch
                                            :root root
                                            :nodes (vec (for [[k v] blocks]
                                                          {:key k :value v}))}}
                                 {:now 0})]
      (is (empty? (:blocks s'))))))

;; =============================================================================
;; Immutability — what may be handed to a stranger
;; =============================================================================

(deftest only-immutable-values-are-offered
  ;; konserve already records `{:immutable? true}` for content-addressed values
  ;; (`konserve/core.cljc:353`). A mutable pointer must not be announced or
  ;; served: its key is not its hash, so the recipient cannot verify it, and its
  ;; value changes under anyone who cached it.
  (let [v {:node 1}
        k (uuid v)
        s (-> (content-state :me {} {})
              (c/put-block k v true)
              (c/put-block :roots-pointer {:root k} false))]

    (testing "both are held locally"
      (is (c/have? s k))
      (is (c/have? s :roots-pointer)))

    (testing "but only the immutable one is servable"
      (is (c/servable? s k))
      (is (not (c/servable? s :roots-pointer)))
      (is (= [k] (vec (c/servable-keys s)))))

    (testing "a want for the mutable pointer is refused"
      (let [{acts :actions} (c/handler s {:type :message :from :p
                                          :payload {:type :content/want
                                                    :key :roots-pointer}}
                                       {:now 0})]
        (is (= :content/dont-have (:type (nth (first acts) 2))))))

    (testing "a want for the immutable value is served"
      (let [{acts :actions} (c/handler s {:type :message :from :p
                                          :payload {:type :content/want :key k}}
                                       {:now 0})]
        (is (= :content/block (:type (nth (first acts) 2))))))

    (testing "and we do not name ourselves provider for the mutable one"
      (let [{acts :actions} (c/handler s {:type :message :from :p
                                          :payload {:type :content/find
                                                    :key :roots-pointer}}
                                       {:now 0})]
        (is (empty? acts))))

    (testing "nor does a tree walk stream it"
      (let [s2 (c/put-block s :parent {:addresses [k :roots-pointer]} true)
            [visited frontier] (c/walk-tree s2 :parent #{} 100 :addresses)]
        (is (= [:parent k] visited))
        (is (= [:roots-pointer] frontier)
            "a mutable node was streamed instead of reported as frontier")))))

(deftest verified-content-is-handed-to-durable-storage
  (testing "a fetched block emits :persist, so it survives a restart"
    ;; The working set is bounded; what makes a fetched block still servable
    ;; tomorrow is the store behind the runtime.
    (let [v {:root "keep-me"}
          k (uuid v)
          s (-> (net [:a :b] {:b {k v}} {:seed 3} {})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch :key k})
                (sim/run-until 20000))]
      (is (c/have? (sim/node-state s :a) k))
      (is (pos? (get-in s [:stats :persisted]))
          "nothing was ever handed to storage")))

  (testing "a tree fetch persists every verified node"
    (let [{:keys [root blocks]} (tree-blocks 2 3)
          n (count blocks)
          s (-> (net [:a :b] {:b blocks} {:seed 4}
                     {:max-blocks (inc n) :max-tree-nodes 1000})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch-tree :root root})
                (sim/run-until 40000))]
      (is (= n (count (:blocks (sim/node-state s :a)))))
      (is (>= (get-in s [:stats :persisted]) n))))

  (testing "a value loaded from our own store is not re-persisted"
    ;; It came from there; writing it back would be an echo.
    (let [v {:from "disk"}
          k (uuid v)
          s (content-state :me {} {})
          {s' :state acts :actions}
          (c/handler s {:type :message :from :app
                        :payload {:type :content/loaded :key k :value v}}
                     {:now 0})]
      (is (c/have? s' k))
      (is (c/servable? s' k))
      (is (empty? acts)))))

(deftest fetches-cannot-be-injected-from-the-wire
  ;; Same class as the :publish / :subscribe / :content/loaded guards: the
  ;; runtime funnels inbound frames in with the SAME event shape the local
  ;; application uses, so an ungated handler is remotely reachable. A fetch
  ;; spends an :max-outstanding slot and fans :content/find to every peer, so
  ;; an attacker could spend our budget on keys of its choosing and make us
  ;; interrogate our neighbours on its behalf.
  (let [s (-> (c/make-state :me {} {})
              (assoc :peers {:attacker {}}))]
    (testing "a remote :content/fetch does nothing"
      (let [{:keys [actions]} (c/handler s {:type :message :from :attacker
                                            :payload {:type :content/fetch
                                                      :key "k"}}
                                         {:now 0})]
        (is (empty? actions))))

    (testing "a remote :content/fetch-tree does nothing"
      (let [{:keys [actions]} (c/handler s {:type :message :from :attacker
                                            :payload {:type :content/fetch-tree
                                                      :root "r"}}
                                         {:now 0})]
        (is (empty? actions))))

    (testing "but our own application can still fetch"
      (let [{:keys [actions]} (c/handler s {:type :message :from :app
                                            :payload {:type :content/fetch
                                                      :key "k"}}
                                         {:now 0})]
        (is (seq actions) "the app path must be unaffected")))))
