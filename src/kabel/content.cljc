(ns kabel.content
  "L3: content routing and block transfer — *who has this value*, then *give it
  to me*, verified.

  This is the BitTorrent shape rather than the DHT shape, and the two halves
  are deliberately separate because they fail differently:

  - **Routing** answers \"who has key K\". A wrong answer costs a wasted
    request, and is self-detecting the moment the block arrives and its hash
    does not match. This is the low-trust half (`.internal/DHT_DESIGN.md` §0).
  - **Transfer** moves the bytes. This is the half that decides whether
    \"fetch a database\" is fast or unusable, and it is where IPFS spent years
    (bitswap → graphsync) after content routing was already working.

  ## Why there is no Kademlia here

  kabel peers are addressed by URL, so peer discovery needs no DHT
  (`.internal/reference/hyperswarm.md`: ~55% of hyperdht is NAT traversal a
  URL-addressed transport does not need). What remains is content routing over
  konserve's content-addressed keys, and the sharpening that makes it tractable
  is that records name **database roots**, not every block: thousands of
  records, not billions. At that scale, announce-to-neighbours plus
  query-on-miss reaches far enough, and a Kademlia lookup can replace the query
  later without changing the transfer protocol.

  **Reach is two hops.** Announcements go to direct peers; a query is answered
  by a direct peer from its own holdings *and* from what it has been told. Past
  that, this returns nothing — deliberately, and it is the honest limit to
  confirm you have outgrown before reaching for a DHT.

  ## Verification is what makes untrusted providers safe

  A block is accepted only if `(hasch/uuid value)` equals the key it was
  requested under. That is exactly konserve's own addressing under datahike's
  `:crypto-hash? true`, so a provider can serve content it cannot forge, and a
  malicious or broken peer wastes a round trip rather than corrupting a store.
  With `:verify? false` — for stores whose keys are not content hashes — a
  fetch is trust-on-delivery, and says so.

  ## Who may claim what

  An `:announce` is accepted **only for the peer that sent it**, over an
  authenticated connection. Nobody can announce on somebody else's behalf, so
  the obvious amplification (\"peer X has everything, go bother X\") is closed
  without signing every record. Records arriving in a `:found` reply are
  *hints* by construction — they cost a request to check, and the hash check is
  what settles it.

  ## What is not here

  Chunking. A value is transferred whole, so this is right for roots, commits
  and index nodes, and wrong for a multi-gigabyte blob. Piece selection,
  rarest-first and endgame mode all belong to a chunked transfer and should be
  built when a value large enough to need them exists."
  (:require [hasch.core :refer [uuid]]
            [kabel.sim.rng :as rng]))

(def default-opts
  {;; How long a provider record is believed. hyperswarm expires announce
   ;; records after 20 minutes; a record that cannot expire is a leak.
   :record-ttl-ms 1200000
   ;; Providers retained per key. hyperswarm returns a random sample of 20 for
   ;; the same reason: it bounds a hot key and randomises which peers get load.
   :max-providers-per-key 20
   ;; Hard ceiling on distinct keys we hold records for.
   :max-keys 4096
   ;; One peer must not be able to claim a million keys
   ;; (`.internal/DHT_DESIGN.md` §6).
   :max-per-provider 256
   ;; Keys offered per announcement.
   :announce-batch 32
   ;; Blocks retained locally and therefore servable.
   :max-blocks 256
   ;; How long to wait for a block before trying another provider.
   :want-timeout-ms 5000
   ;; Concurrent fetches, and how many providers each asks at once. Asking
   ;; more than one is BitTorrent's answer to a slow peer; asking all of them
   ;; is a broadcast storm.
   :max-outstanding 16
   :parallel-wants 2
   ;; Keys waiting for a free slot. Without a queue, a caller that asks for
   ;; more than :max-outstanding at once simply loses the surplus — which is
   ;; every bulk transfer, since walking a DAG means asking for thousands.
   :max-pending 8192
   ;; Give up after this many provider attempts for one key.
   :max-tries 4
   ;; Check that a delivered block hashes to the key it was requested under.
   :verify? true
   ;; How often to expire records and re-announce.
   :maintenance-ms 60000

   ;; --- Subtree transfer -----------------------------------------------------
   ;; Project a stored value to its child addresses. Injected, exactly as
   ;; konserve-sync's walkers inject it, so kabel needs no dependency on
   ;; persistent-sorted-set, konserve or datahike — and so a consumer whose
   ;; nodes are objects rather than maps can pass an object-aware projector.
   ;; The default matches konserve-sync's: plain maps with `:addresses`.
   :addresses-fn :addresses
   ;; RESIDENCY BOUND. A "send me the tree at R" request is a memory
   ;; amplification vector: persistent-sorted-set documents that a cold-tree
   ;; probe can restore 100% of blobs at low fanout (`Branch.java:715-731`),
   ;; "a resident set of the WHOLE tree, which is exactly the bound :ref-type
   ;; exists to enforce". A stranger's one-line request must not be able to
   ;; make us materialise an entire index, so the walk stops here and reports
   ;; its frontier instead.
   :max-tree-nodes 4096
   ;; Nodes per message, so a batch is bounded independently of the walk.
   :max-tree-batch 256})

(defn make-state
  ([id] (make-state id {} {}))
  ([id blocks] (make-state id blocks {}))
  ([id blocks opts]
   {:id id
    :rng (rng/make-rng 0)
    :opts (merge default-opts opts)
    :peers #{}
    ;; What we hold and can serve. Bounded — the runtime backs this with
    ;; konserve, this is the servable working set.
    :blocks (or blocks {})
    :block-order (vec (keys (or blocks {})))
    ;; key -> {:immutable? bool}. konserve already records this
    ;; (`konserve/core.cljc:353`, `:421`), and it is the honest signal for what
    ;; may be announced and served at all: an immutable content-addressed value
    ;; is safe to hand a stranger and safe for them to verify; a mutable
    ;; pointer is neither — its key is not its hash, so verification would fail,
    ;; and its value can change under a peer that cached it.
    ;;
    ;; Seeded blocks are taken as immutable (the caller asserts it); fetched
    ;; blocks derive it from having verified.
    :block-meta (into {} (for [k (keys (or blocks {}))] [k {:immutable? true}]))
    ;; key -> {provider-id -> expires-at}
    :providers {}
    ;; key -> {:asked #{peer} :providers #{} :deadline t :tries n}
    :wants {}
    ;; Keys waiting for an outstanding slot, FIFO.
    :pending []
    ;; root -> {:received n :frontier [...] :done? bool}
    :tree-wants {}
    ;; Keys fetched successfully, for callers to observe.
    :fetched []
    :failed []
    :stats {:announced 0 :records-learned 0 :records-refused 0
            :finds 0 :wants-sent 0 :blocks-served 0 :blocks-received 0
            :verify-failed 0 :expired 0 :queued 0 :fetch-refused 0
            :tree-requests 0 :tree-nodes-sent 0 :tree-nodes-received 0
            :tree-truncated 0}}))

;; =============================================================================
;; Local holdings
;; =============================================================================

(defn have? [state k] (contains? (:blocks state) k))

(defn servable?
  "May `k` be announced to, and served to, a stranger?

  Only immutable content-addressed values. Serving a mutable pointer would
  hand out something the recipient cannot verify and that goes stale the moment
  it moves."
  [state k]
  (and (have? state k)
       (boolean (get-in state [:block-meta k :immutable?]))))

(defn servable-keys [state]
  (filter #(servable? state %) (keys (:blocks state))))

(defn put-block
  "Record a value we can serve, evicting the oldest if we are at the cap.

  `immutable?` defaults to true because every path that stores a block here has
  either verified it or had it asserted; pass false for a mutable pointer, which
  is then held locally but never announced or served."
  ([state k v] (put-block state k v true))
  ([state k v immutable?]
   (if (have? state k)
     state
     (let [{:keys [max-blocks]} (:opts state)
           state (-> state
                     (assoc-in [:blocks k] v)
                     (assoc-in [:block-meta k] {:immutable? (boolean immutable?)})
                     (update :block-order conj k))]
       (if (> (count (:block-order state)) max-blocks)
         (let [evict (first (:block-order state))]
           (-> state
               (update :blocks dissoc evict)
               (update :block-meta dissoc evict)
               (update :block-order subvec 1)))
         state)))))

(defn verified?
  "Does `value` hash to `k`?

  konserve's content-addressed keys are `hasch` uuids of the value, so this is
  the same check the store itself makes — which is why a provider can serve
  content it cannot forge."
  [k value]
  (= k (uuid value)))

;; =============================================================================
;; Subtree walking
;; =============================================================================
;; The point of the whole exercise: one request, one stream, instead of one
;; round trip per node. Measured, per-key fetch of a 10 000-node index at 50 ms
;; RTT costs ~62 s at a pipeline depth of 16 — two round trips per node, since
;; each needs a provider lookup and then a block request. Walking on the
;; PROVIDER side collapses that to a request plus a stream.
;;
;; The walk is breadth-first, which matters for three reasons: a parent always
;; precedes its children so the receiver can verify incrementally; it mirrors
;; the bulk BFS warmup datahike already does against S3; and truncating a BFS
;; leaves a frontier that is a clean set of subtree roots to resume from,
;; whereas truncating a DFS leaves a path.

(defn walk-tree
  "Addresses reachable from `root` among the blocks we hold, breadth-first.

  Returns `[visited frontier]`. `have` is what the requester already holds, so
  those subtrees are pruned — which is what makes an update cheap: a
  persistent-sorted-set shares structure, so two roots differ by O(changed)
  nodes, not O(tree). That is konserve-sync's timestamp diff in its
  untrusted-peer form: timestamps are unverifiable claims, content addresses
  are verifiable.

  `frontier` is everything left unvisited — because the walk hit `limit`, or
  because we do not hold the node. Either way the requester can resume from it
  rather than being told a flat no."
  [state root have limit addresses-fn]
  (loop [queue (if (contains? have root) [] [root])
         seen #{}
         visited []
         missing []]
    (cond
      ;; Truncated by the residency bound: everything still queued becomes
      ;; frontier, so the requester resumes rather than starting over.
      (>= (count visited) limit)
      [visited (vec (distinct (concat missing (remove seen queue))))]

      (empty? queue)
      [visited (vec (distinct missing))]

      :else
      (let [k (nth queue 0)
            rest* (subvec queue 1)]
        (cond
          (or (seen k) (contains? have k))
          (recur rest* seen visited missing)

          ;; We cannot serve what we do not hold. It goes to the frontier so
          ;; the requester can look elsewhere — being silently dropped here
          ;; would leave it with an incomplete tree it believed was complete.
          ;; `servable?`, not `have?`: a mutable pointer we happen to hold is
          ;; not ours to hand out, and streaming one into somebody's tree would
          ;; give them a node they cannot verify.
          (not (servable? state k))
          (recur rest* (conj seen k) visited (conj missing k))

          :else
          (let [v (get-in state [:blocks k])
                children (or (addresses-fn v) [])]
            (recur (into rest* (remove seen children))
                   (conj seen k)
                   (conj visited k)
                   missing)))))))

;; =============================================================================
;; Provider records
;; =============================================================================

(defn provider-count [state provider]
  (count (filter (fn [[_ ps]] (contains? ps provider)) (:providers state))))

(defn add-record
  "Learn that `provider` holds `k` until `expires-at`.

  Refused when it would exceed a cap: per key, per provider, or overall. Each
  cap exists because an address book, a record store and a want list are all
  attacker-supplied, and every reviewed system leaked through exactly one of
  them."
  [state k provider expires-at]
  (let [{:keys [max-providers-per-key max-keys max-per-provider]} (:opts state)
        existing (get-in state [:providers k] {})]
    (cond
      ;; Refreshing a record we already hold is always allowed — that is how a
      ;; provider stays live, and refusing it would expire honest peers.
      (contains? existing provider)
      (assoc-in state [:providers k provider] expires-at)

      (>= (count existing) max-providers-per-key)
      (update-in state [:stats :records-refused] inc)

      (and (not (contains? (:providers state) k))
           (>= (count (:providers state)) max-keys))
      (update-in state [:stats :records-refused] inc)

      (>= (provider-count state provider) max-per-provider)
      (update-in state [:stats :records-refused] inc)

      :else
      (-> state
          (assoc-in [:providers k provider] expires-at)
          (update-in [:stats :records-learned] inc)))))

(defn providers-for
  "Live providers for `k` at `now`, ourselves excluded."
  [state k now]
  (->> (get-in state [:providers k] {})
       (filter (fn [[p expires]] (and (> expires now) (not= p (:id state)))))
       (map key)
       sort))

(defn expire-records
  "Drop records past their TTL, and keys left with none."
  [state now]
  (let [before (reduce + 0 (map count (vals (:providers state))))
        providers (reduce-kv (fn [acc k ps]
                               (let [live (into {} (filter (fn [[_ e]] (> e now)) ps))]
                                 (if (seq live) (assoc acc k live) acc)))
                             {}
                             (:providers state))
        after (reduce + 0 (map count (vals providers)))]
    (-> state
        (assoc :providers providers)
        (update-in [:stats :expired] + (max 0 (- before after))))))

;; =============================================================================
;; Messages
;; =============================================================================

(defn- announcement
  "A bounded, randomly chosen slice of what we hold.

  Random rather than the first N so that two peers holding the same store do
  not advertise the same subset to everyone."
  [state]
  (let [{:keys [announce-batch]} (:opts state)
        [rng' chosen] (rng/sample (:rng state) announce-batch (servable-keys state))]
    [(assoc state :rng rng') (vec chosen)]))

(declare drain-pending)

(defn want-providers
  "Everyone we could ask for `k`: providers we were *told about because we
  asked*, plus providers from the record store.

  The two are deliberately different things. The record store holds
  **unsolicited** announcements and is capped per provider, per key and
  overall, because an announcement is attacker-supplied
  (`.internal/DHT_DESIGN.md` §6). Solicited knowledge — a `:content/found`
  answering a query *we* issued — is transient, lives on the want, and is not
  capped, because it cannot be pushed at us.

  Keeping them merged was a real bug: `:max-per-provider` is 256, so a peer
  could never fetch more than 256 keys from a single provider — which is
  exactly what fetching a 10 000-node index from one peer is. The Sybil
  defence was silently blocking the primary use case."
  [state k now]
  (distinct (concat (sort (get-in state [:wants k :providers] #{}))
                    (providers-for state k now))))

(defn- send-wants
  "Ask up to `:parallel-wants` unasked providers for `k`."
  [state k now]
  (let [{:keys [parallel-wants want-timeout-ms max-tries]} (:opts state)
        want (get-in state [:wants k])
        asked (:asked want #{})
        candidates (->> (want-providers state k now)
                        (remove asked)
                        (remove #{(:id state)}))
        chosen (take parallel-wants candidates)]
    (cond
      (>= (:tries want 0) max-tries)
      (let [state (-> state (update :wants dissoc k) (update :failed conj k))]
        (drain-pending state now))

      (empty? chosen)
      ;; Nobody left to ask *yet* — a `:found` reply may still arrive. But the
      ;; attempt still counts and the timer is still re-armed, because
      ;; otherwise a fetch for content nobody holds never terminates: it sits
      ;; in `:wants` forever, holding one of `:max-outstanding` slots, and
      ;; after enough such fetches the peer can never fetch anything again.
      ;; Silent, permanent, and invisible to any counter.
      [(update-in state [:wants k] merge
                  {:deadline (+ now want-timeout-ms)
                   :tries (inc (:tries want 0))})
       [[:timer want-timeout-ms [:want-timeout k]]]]

      :else
      [(-> state
           (update-in [:wants k] merge
                      {:asked (into asked chosen)
                       :deadline (+ now want-timeout-ms)
                       :tries (inc (:tries want 0))})
           (update-in [:stats :wants-sent] + (count chosen)))
       (conj (vec (for [p chosen] [:send p {:type :content/want :key k}]))
             [:timer want-timeout-ms [:want-timeout k]])])))

(defn- start-fetch
  "Open a want for `k` and issue the first requests. Assumes there is a slot."
  [state k now]
  (let []
    (cond
      (have? state k) [state []]
      (contains? (:wants state) k) [state []]

      :else
      (let [{:keys [want-timeout-ms]} (:opts state)
            state (assoc-in state [:wants k]
                            {:asked #{} :providers #{} :deadline nil :tries 0})]
        (if (seq (providers-for state k now))
          (send-wants state k now)
          ;; No provider known: ask the neighbourhood. The reply supplies
          ;; providers, and `:found` handling then issues the wants — but arm
          ;; the timeout regardless, so a query nobody answers still ends.
          [(-> state
               (update-in [:stats :finds] inc)
               (assoc-in [:wants k :deadline] (+ now want-timeout-ms)))
           (conj (vec (for [p (sort (:peers state))]
                        [:send p {:type :content/find :key k}]))
                 [:timer want-timeout-ms [:want-timeout k]])])))))

(defn fetch-tree
  "Request the whole subtree at `root` in one exchange. Returns `[state actions]`.

  `have` is what we already hold — normally a previous root. Because
  persistent-sorted-set shares structure, passing the old root makes the
  provider send only what changed, which is the difference between an update
  costing O(changed) and O(tree)."
  [state root have now]
  (let [providers (providers-for state root now)]
    (cond
      (contains? (:tree-wants state) root) [state []]

      (seq providers)
      [(-> state
           (assoc-in [:tree-wants root] {:received 0 :frontier [] :have (vec have)})
           (update-in [:stats :tree-requests] inc))
       [[:send (first providers) {:type :content/want-tree
                                  :root root
                                  :have (vec have)}]]]

      :else
      ;; Nobody known yet. Remember the intent; :content/found starts it.
      [(assoc-in state [:tree-wants root] {:received 0 :frontier []
                                           :have (vec have) :pending? true})
       (vec (for [p (sort (:peers state))]
              [:send p {:type :content/find :key root}]))])))

(defn drain-pending
  "Start as many queued fetches as there are free slots. Returns `[state actions]`.

  Called whenever a want finishes, so the queue is what turns
  `:max-outstanding` from a *cap on requests accepted* into a cap on requests
  *in flight* — the difference between a bulk transfer working and silently
  losing everything past the sixteenth key."
  [state now]
  (let [{:keys [max-outstanding]} (:opts state)]
    (loop [state state acts []]
      (let [free (- max-outstanding (count (:wants state)))
            k (first (:pending state))]
        (if (or (<= free 0) (nil? k))
          [state acts]
          (let [state (update state :pending subvec 1)
                [state a] (start-fetch state k now)]
            (recur state (into acts a))))))))

(defn fetch
  "Request `k`. Returns `[state actions]`.

  Starts immediately if a slot is free, otherwise queues. A caller may ask for
  a whole DAG's worth of keys at once; what it may not do is have them
  silently discarded."
  [state k now]
  (let [{:keys [max-outstanding max-pending]} (:opts state)]
    (cond
      (have? state k) [state []]
      (contains? (:wants state) k) [state []]
      (some #{k} (:pending state)) [state []]

      (< (count (:wants state)) max-outstanding)
      (start-fetch state k now)

      (< (count (:pending state)) max-pending)
      [(-> state
           (update :pending conj k)
           (update-in [:stats :queued] inc))
       []]

      ;; The queue itself is bounded, and a refusal here is COUNTED rather than
      ;; silent — an unbounded queue is the other way this fails.
      :else
      [(update-in state [:stats :fetch-refused] inc) []])))

;; =============================================================================
;; Handler
;; =============================================================================

(defn sync-peers
  "Reconcile the peer set, announcing our holdings to newcomers."
  [state peer-ids]
  (let [current (set peer-ids)
        added (remove (:peers state) current)
        state (assoc state :peers current)]
    (if (empty? added)
      [state []]
      (let [[state offer] (announcement state)]
        [(update-in state [:stats :announced] + (count added))
         (vec (for [p added]
                [:send p {:type :content/announce :keys offer}]))]))))

(defn handler
  [state event {:keys [now] :as _ctx}]
  (case (:type event)

    :init
    {:state state
     :actions [[:timer (get-in state [:opts :maintenance-ms]) :content/maintenance]]}

    :disconnected
    {:state (update state :peers disj (:peer event)) :actions []}

    :timer
    (let [payload (:payload event)]
      (cond
        (= :content/maintenance payload)
        ;; Expiry and re-announcement on one timer: a record store without
        ;; expiry leaks, and expiry without republication makes it lossy
        ;; (`.internal/DHT_DESIGN.md` §6).
        (let [state (expire-records state now)
              [state offer] (announcement state)]
          {:state state
           :actions (conj (vec (for [p (sort (:peers state))]
                                 [:send p {:type :content/announce :keys offer}]))
                          [:timer (get-in state [:opts :maintenance-ms])
                           :content/maintenance])})

        (and (vector? payload) (= :want-timeout (first payload)))
        (let [k (second payload)
              want (get-in state [:wants k])]
          (if (and want (:deadline want) (<= (:deadline want) now))
            (let [[state actions] (send-wants state k now)]
              {:state state :actions actions})
            {:state state :actions []}))

        :else {:state state :actions []}))

    :message
    (let [from (:from event)
          payload (:payload event)]
      (case (:type payload)

        :content/fetch
        (let [[state actions] (fetch state (:key payload) now)]
          {:state state :actions actions})

        :content/fetch-tree
        (let [[state actions] (fetch-tree state (:root payload)
                                          (:have payload) now)]
          {:state state :actions actions})

        :content/loaded
        ;; A value handed to us by our own durable store — no verification and
        ;; no re-persist, because it came from there. This is what makes a
        ;; restarted peer able to serve what it already holds instead of
        ;; starting empty.
        {:state (put-block state (:key payload) (:value payload)
                           (if (contains? payload :immutable?)
                             (:immutable? payload)
                             true))
         :actions []}

        :content/announce
        ;; Accepted only for the sender. An announcement on somebody else's
        ;; behalf would let one peer point the network at another.
        {:state (reduce (fn [s k]
                          (add-record s k from
                                      (+ now (get-in s [:opts :record-ttl-ms]))))
                        state
                        (:keys payload))
         :actions []}

        :content/find
        (let [k (:key payload)
              ;; Our own holdings plus what we have been told: this is the
              ;; second hop, and the whole of the reach.
              known (cond-> (providers-for state k now)
                      (servable? state k) (conj (:id state)))
              ttl (get-in state [:opts :record-ttl-ms])]
          {:state state
           :actions (if (seq known)
                      [[:send from {:type :content/found
                                    :key k
                                    :providers (vec (distinct known))
                                    :ttl ttl}]]
                      [])})

        :content/found
        (let [k (:key payload)
              ;; If we asked, the answer belongs to the want — uncapped, and
              ;; discarded when the fetch ends. If we did not ask, it is an
              ;; unsolicited hint and goes through the capped record store.
              state (if (contains? (:wants state) k)
                      (update-in state [:wants k :providers]
                                 (fnil into #{}) (:providers payload))
                      (reduce (fn [s p]
                                (add-record s k p (+ now (or (:ttl payload) 0))))
                              state
                              (:providers payload)))]
          (cond
            (contains? (:wants state) k)
            (let [[state actions] (send-wants state k now)]
              {:state state :actions actions})

            ;; A tree request that was waiting for a provider can now start.
            (get-in state [:tree-wants k :pending?])
            (let [provider (first (:providers payload))]
              (if provider
                {:state (-> state
                            (update-in [:tree-wants k] dissoc :pending?)
                            (update-in [:stats :tree-requests] inc))
                 :actions [[:send provider
                            {:type :content/want-tree
                             :root k
                             :have (get-in state [:tree-wants k :have] [])}]]}
                {:state state :actions []}))

            :else {:state state :actions []}))

        :content/want-tree
        ;; Provider side: walk our own store and stream. One request, one
        ;; stream — instead of two round trips per node.
        (let [{:keys [addresses-fn max-tree-nodes max-tree-batch]} (:opts state)
              root (:root payload)
              have (set (:have payload))
              [visited frontier] (walk-tree state root have max-tree-nodes addresses-fn)
              batches (partition-all max-tree-batch visited)]
          {:state (-> state
                      (update-in [:stats :tree-nodes-sent] + (count visited))
                      (cond-> (seq frontier)
                        (update-in [:stats :tree-truncated] inc)))
           :actions (conj (vec (for [b batches]
                                 [:send from {:type :content/tree-batch
                                              :root root
                                              :nodes (vec (for [k b]
                                                            {:key k
                                                             :value (get-in state [:blocks k])}))}]))
                          [:send from {:type :content/tree-done
                                       :root root
                                       :count (count visited)
                                       :frontier (vec frontier)}])})

        :content/tree-batch
        ;; Requester side. Every node is verified before it is kept — the
        ;; provider is untrusted, and a batch is exactly the place where
        ;; accepting on faith would be cheapest and worst.
        (let [root (:root payload)
              verify? (get-in state [:opts :verify?])]
          (if-not (contains? (:tree-wants state) root)
            ;; Unsolicited: dropped, like an unsolicited block.
            {:state state :actions []}
            (let [[state acts]
                  (reduce (fn [[st acts] {:keys [key value]}]
                            (if (and verify? (not (verified? key value)))
                              [(update-in st [:stats :verify-failed] inc) acts]
                              [(-> st
                                   (put-block key value)
                                   (update-in [:tree-wants root :received] inc)
                                   (update-in [:stats :tree-nodes-received] inc))
                               (conj acts [:persist key value])]))
                          [state []]
                          (:nodes payload))]
              {:state state :actions acts})))

        :content/tree-done
        (let [root (:root payload)]
          (if-not (contains? (:tree-wants state) root)
            {:state state :actions []}
            (let [frontier (vec (:frontier payload))
                  state (-> state
                            (assoc-in [:tree-wants root :frontier] frontier)
                            (assoc-in [:tree-wants root :done?] true))]
              ;; A truncated walk is resumed by fetching the frontier — those
              ;; are subtree roots, so a bounded provider stays bounded and the
              ;; requester still converges.
              (let [[state acts]
                    (reduce (fn [[st acts] k]
                              (let [[st a] (fetch st k now)]
                                [st (into acts a)]))
                            [state []]
                            frontier)]
                {:state state :actions acts}))))

        :content/want
        (let [k (:key payload)]
          (if (servable? state k)
            {:state (update-in state [:stats :blocks-served] inc)
             :actions [[:send from {:type :content/block
                                    :key k
                                    :value (get-in state [:blocks k])}]]}
            {:state state
             :actions [[:send from {:type :content/dont-have :key k}]]}))

        :content/block
        (let [k (:key payload)
              v (:value payload)
              verify? (get-in state [:opts :verify?])]
          (cond
            (not (contains? (:wants state) k))
            ;; Unsolicited, or a duplicate from a second provider we asked in
            ;; parallel. Dropped rather than stored: accepting unrequested
            ;; blocks is a free way to fill somebody's cache.
            {:state state :actions []}

            (and verify? (not (verified? k v)))
            ;; The hash is what makes an untrusted provider safe. A liar costs
            ;; us one round trip and is asked no further — we simply try the
            ;; next provider.
            (let [[state actions] (send-wants (update-in state [:stats :verify-failed] inc)
                                              k now)]
              {:state state :actions actions})

            :else
            (let [state (-> state
                            (put-block k v)
                            (update :wants dissoc k)
                            (update :fetched conj k)
                            (update-in [:stats :blocks-received] inc))
                  [state acts] (drain-pending state now)]
              ;; `:persist` hands the value to durable storage. The state
              ;; machine holds a bounded working set; what makes a fetched
              ;; block survive a restart — and stay servable — is the store
              ;; behind the runtime.
              {:state state :actions (into [[:persist k v]] acts)})))

        :content/dont-have
        ;; A provider that no longer holds it. Drop it from both the record
        ;; store and the in-flight want so we stop asking, and try somebody
        ;; else.
        (let [k (:key payload)
              state (-> state
                        (update-in [:providers k] dissoc from)
                        (update-in [:wants k :providers] (fnil disj #{}) from))
              [state actions] (if (contains? (:wants state) k)
                                (send-wants state k now)
                                [state []])]
          {:state state :actions actions})

        {:state state :actions []}))

    {:state state :actions []}))
