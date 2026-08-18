(ns kabel.dissemination
  "L2: topic-scoped multi-hop dissemination with interval-set repair.

  kabel's existing pub/sub is strictly one hop — a server fans out to its
  directly connected subscribers and a client sends to its server
  (`kabel/pubsub.cljc`). There is no forwarding, no message identity and no
  duplicate suppression. This namespace is the multi-hop layer: it is what
  makes a *distributed* pub/sub, and therefore what lets replikativ peers
  replicate without being wired to each other by hand.

  ## Why this can be much simpler than gossipsub

  The payloads are CRDT operations: idempotent, commutative, and repaired by
  the layer above when a peer turns out to be missing history. So dissemination
  does **not** have to be a reliable broadcast. Duplicates are free, reordering
  is free, and a peer that misses a message entirely still converges.

  That inverts the usual risk profile — normally the broadcast layer is
  correctness-critical. Here the CRDT is, so this layer can be a flood with
  good bookkeeping, and a Plumtree spanning tree can be added later as a pure
  bandwidth optimisation without touching correctness.

  ## What it does have

  - **Message identity** `{origin, epoch, seq}`, with the epoch bumped on
    restart so a node that loses its counter cannot reuse sequence numbers.
  - **Interval-set seen tracking** (`kabel.interval-set`) instead of a
    TTL cache: `O(#peers)` rows, no expiry, and it doubles as the repair query.
  - **Hop TTL.** gossipsub has none; a forwarding loop there is bounded only
    by the seen-cache, which is bounded only by time.
  - **No-echo** forwarding — never back to the peer it came from.
  - **Interest filtering**, recovering the `:sub/identities` / `extend?`
    mechanism from replikativ's deleted `connect.cljc`, so a node forwards only
    topics its neighbour wants. Relay nodes carry everything, which is what
    keeps a topic's overlay connected across uninterested peers.
  - **Authorisation on the forward path**, not only at subscribe time.
    `kabel.pubsub`'s `:authorize-publish-fn` gates messages arriving at their
    destination; in a multi-hop network the check has to happen at every hop,
    or one authorised peer relays anything.
  - **Anti-entropy repair**: peers periodically exchange interval-set digests
    and request their gaps. This is Plumtree's lazy IHAVE/GRAFT path expressed
    as a gap query, which is the same shape as replikativ's fetch.

  ## What it deliberately omits

  No peer scoring, no peer exchange, no adaptive gossip, no mesh degree
  management. Those exist in gossipsub to synthesise accountability from
  observed behaviour in an open network; a network with admission control
  already has it (`.internal/reference/gossipsub.md`). Adding them later is a
  decision that should be forced by an actual threat, not by symmetry with
  another system."
  (:require [hasch.core :refer [edn-hash]]
            [kabel.authorize :as authz]
            [kabel.topics :as topics]
            [kabel.identity :as id]
            [kabel.interval-set :as is*]
            [kabel.sim.rng :as rng]))

;; =============================================================================
;; Authenticated publishes
;; =============================================================================

(def signing-tag "kabel/gossip/v1")

(defn signing-bytes
  "Canonical bytes a publisher signs, and every hop verifies.

  Covers the message *identity* as well as its content — `origin`, `epoch` and
  `seq` alongside `topic` and `payload` — so a signature cannot be lifted off
  one message and reattached to another position in the stream. Without
  `origin` a relay could re-attribute a publish; without `seq` it could replay
  one as a later message.

  Hashed with `hasch/edn-hash` rather than `pr-str` because the bytes must be
  identical on both platforms: map key iteration order is not, and a signature
  over `pr-str` output would verify on the JVM and fail in the browser for
  payloads that happen to contain a map."
  [{:keys [origin epoch seq topic payload]}]
  (id/seq->bytes (edn-hash [signing-tag origin epoch seq topic payload])))

(defn signed?
  "Does this message carry publisher credentials?"
  [msg]
  (boolean (and (:origin-key msg) (:origin-sig msg))))

(def default-opts
  {;; Forwarding hop limit. A message that has travelled this far is delivered
   ;; but not relayed further.
   :max-hops 8
   ;; Messages retained for serving repair requests, FIFO.
   :store-size 256
   ;; How often to offer neighbours a digest of what we hold.
   :have-interval-ms 5000
   ;; Cap on messages served for a single :want, so a repair request cannot be
   ;; used as an amplifier.
   :max-want 32
   ;; Cap on the SPAN of a requested range, and on how many ranges one :want
   ;; may contain.
   ;;
   ;; Serving a want costs O(span), not O(messages served) — the lookup walks
   ;; the requested sequence numbers. Unclamped, a single small message naming
   ;; {:lo 0 :hi 20000000} bought ~2.9 seconds of CPU, measured: remote
   ;; exhaustion at about 10^7 : 1 amplification, from a peer that has to send
   ;; nothing but one map. A :want is attacker-supplied like everything else on
   ;; the wire, and was the one place we forgot it.
   ;;
   ;; Clamped rather than refused, so an honest peer with a genuinely large gap
   ;; still makes progress — it just takes several rounds.
   :max-want-span 1024
   :max-want-ranges 16
   ;; Ranges retained per (origin, epoch) in the seen set.
   ;;
   ;; The claim that interval sets cost O(gaps) rather than O(messages) is true
   ;; and incomplete: the gaps are chosen by the PUBLISHER. Measured, a peer
   ;; emitting only even sequence numbers produces one range per message —
   ;; 10 000 messages, 10 000 ranges. Capping restores the bound; the lowest
   ;; range is evicted, so recent contiguity survives and the cost of an
   ;; evicted range is that very old messages may be accepted twice, which is
   ;; harmless for idempotent payloads.
   :max-seen-ranges 64
   ;; Cap on origins described in one :have digest.
   :max-summary-origins 64
   ;; Bound on the retained delivery log. Real integrations hand off to the
   ;; application instead; this exists so tests can assert and state stays
   ;; bounded.
   :max-delivered 1024
   ;; Authorization at EVERY hop.
   ;;
   ;; `:authorize` is `(fn [{:keys [op principal topic payload]}] -> truthy)` —
   ;; see `kabel.authorize`. `:principal` is the VERIFIED origin peer id, which
   ;; is the principal a self-certifying network actually has, and `:payload`
   ;; is present so a policy can answer "may this key set the root of *that*
   ;; database" rather than only "may it publish on this topic".
   ;;
   ;; `:authorize-fn` is the legacy `(fn [topic origin])` and still works.
   :authorize nil
   :authorize-fn nil

   ;; --- Dormant operator ------------------------------------------------
   ;; A relay carries other people's traffic on its operator's behalf. When
   ;; that operator stops showing up, the relay should stop volunteering:
   ;; unattended relaying is how an instance ends up hosting content nobody is
   ;; watching, which is the practical form of the moderation burden that
   ;; sinks fediverse admins.
   ;;
   ;; Mastodon's version of this idea is that registrations degrade after
   ;; seven days without an admin login. Ours is narrower and more directly
   ;; useful: the relay narrows its `:carries` to what it is subscribed to and
   ;; stops volunteering for anything else. It keeps working for itself; it
   ;; just stops working for strangers.
   ;;
   ;; nil disables it. `heartbeat!` is how an operator says they are present.
   :dormant-after-ms nil})

(defn make-state
  ([id] (make-state id #{} {}))
  ([id topics] (make-state id topics {}))
  ([id topics opts]
   {:id id
    :rng (rng/make-rng 0)
    :opts (merge default-opts opts)
    :topics (set topics)
    ;; Which topic RANGES we relay — topics we forward without necessarily
    ;; subscribing to them. `[]` is everything, which is what `:relay? true`
    ;; used to mean and remains the default.
    ;;
    ;; Relaying is what keeps a topic's overlay connected across uninterested
    ;; peers (DHT_DESIGN §4). Relaying *everything* costs 4× the traffic of
    ;; relaying nothing, measured on a 40-node mesh at 10% subscribers — so the
    ;; useful setting is usually neither extreme but a slice.
    :carries #{topics/everything}
    :peers {}
    ;; When the operator was last seen. nil means never, which counts as
    ;; present until the first heartbeat — a relay must not narrow itself
    ;; before anyone has had a chance to say hello.
    :operator-seen-at nil
    :dormant? false
    :seen {}
    :store {}
    :store-order []
    :epoch 0
    :next-seq 0
    :delivered []
    :stats {:published 0 :forwarded 0 :delivered 0 :duplicates 0
            :hop-expired 0 :unauthorized 0 :repaired 0 :want-served 0}}))

;; =============================================================================
;; Seen tracking
;; =============================================================================

(defn heartbeat!
  "Record that the operator is present.

  Called from wherever a human actually shows up — an admin login, a console
  command, a health endpoint someone has to authenticate to. What must NOT
  drive it is automated traffic: a relay that heartbeats itself is answering
  the wrong question."
  [state now]
  (-> state (assoc :operator-seen-at now) (assoc :dormant? false)))

(defn dormant?
  "Has the operator been absent longer than `:dormant-after-ms`?"
  [state now]
  (let [after (get-in state [:opts :dormant-after-ms])
        seen (:operator-seen-at state)]
    (boolean (and after seen (> (- now seen) after)))))

(defn effective-carries
  "The ranges we actually relay right now.

  A dormant relay carries nothing beyond its own subscriptions: it keeps
  working for itself and stops volunteering for strangers."
  [state now]
  (if (dormant? state now)
    #{}
    (:carries state)))

(defn seen?
  [state origin epoch seq-no]
  (is*/contains? (get-in state [:seen origin epoch] is*/empty) seq-no))

(defn mark-seen
  [state origin epoch seq-no]
  (let [{:keys [max-seen-ranges]} (:opts state)
        updated (is*/add (get-in state [:seen origin epoch] is*/empty) seq-no)
        ;; A sparse publisher fragments the set one range per message. Evict
        ;; the lowest, which keeps the recent end contiguous.
        trimmed (if (and max-seen-ranges (> (is*/count-ranges updated) max-seen-ranges))
                  (vec (drop (- (is*/count-ranges updated) max-seen-ranges) updated))
                  updated)]
    (assoc-in state [:seen origin epoch] trimmed)))

(defn summary
  "A digest of what we hold: `{origin {epoch interval-set}}`, capped.

  Compact by construction — a peer that has received an origin's whole stream
  contributes one range, however many messages that was."
  [state]
  (let [{:keys [max-summary-origins]} (:opts state)]
    (into {} (take max-summary-origins (:seen state)))))

(defn gaps-against
  "What `their-summary` shows that we do not have, as want-ranges.

  Only gaps *below* a peer's high-water mark are requested: above it there is
  nothing to ask for, and asking would mean requesting messages that do not
  exist yet."
  [state their-summary]
  (vec (for [[origin epochs] their-summary
             [epoch their-iset] epochs
             :let [mine (get-in state [:seen origin epoch] is*/empty)
                   hi (is*/maximum their-iset)]
             :when hi
             [lo hi'] (is*/missing mine (or (is*/minimum their-iset) 0) hi)
             ;; Only ask for what they actually claim to hold.
             :when (some (fn [[a b]] (and (<= a hi') (<= lo b))) their-iset)]
         {:origin origin :epoch epoch :lo lo :hi hi'})))

;; =============================================================================
;; Message store
;; =============================================================================

(defn- store-message
  [state {:keys [origin epoch seq] :as msg}]
  (let [k [origin epoch seq]
        {:keys [store-size]} (:opts state)
        state (-> state
                  (assoc-in [:store k] msg)
                  (update :store-order conj k))]
    (if (> (count (:store-order state)) store-size)
      (let [evict (first (:store-order state))]
        (-> state
            (update :store dissoc evict)
            (update :store-order subvec 1)))
      state)))

(defn stored
  "Messages held for `range`, capped at `:max-want` served and
  `:max-want-span` examined.

  Both caps are load-bearing. `:max-want` bounds what we send; `:max-want-span`
  bounds what we LOOK AT, and without it the lookup walks every sequence number
  named — so an empty range of ten million costs ten million probes and sends
  nothing."
  [state {:keys [origin epoch lo hi]}]
  (let [{:keys [max-want max-want-span]} (:opts state)
        lo (max 0 (or lo 0))
        hi (min (or hi lo) (+ lo (dec max-want-span)))]
    (if (< hi lo)
      []
      (->> (range lo (inc hi))
           (keep #(get-in state [:store [origin epoch %]]))
           (take max-want)
           vec))))

;; =============================================================================
;; Peers and interest
;; =============================================================================

(defn sync-peers
  "Reconcile the peer set with `peer-ids` (normally `kabel.membership`'s
  connections).

  Returns `[state actions]`. A newly seen peer is told our interests **and
  offered a digest immediately**.

  The eager digest is what makes joining fast. Without it a fresh peer waits up
  to `:have-interval-ms` before it discovers anything it missed — the forward
  path works at once, but the backlog arrives on the next tick, so joining and
  being current cost seconds for no reason. Anti-entropy is cheap here
  precisely because an interval-set digest is compact, so there is no reason to
  make a new peer wait for a timer.

  A peer whose interests we do not yet know is treated as a relay — optimistic,
  and self-correcting the moment it announces. The alternative, withholding
  traffic until interests arrive, deadlocks a fresh connection."
  ([state peer-ids] (sync-peers state peer-ids nil))
  ([state peer-ids now]
   (let [current (set peer-ids)
         known (set (keys (:peers state)))
         added (remove known current)
         removed (remove current known)
         state (as-> state s
                 (reduce (fn [s p] (assoc-in s [:peers p]
                                             {:interests nil
                                              :carries #{topics/everything}}))
                         s added)
                 (reduce (fn [s p] (update s :peers dissoc p)) s removed))]
     [state
      (vec (concat
            (for [p added]
              [:send p {:type :interests
                        :topics (:topics state)
                       ;; The EFFECTIVE ranges: a dormant relay must not keep
                       ;; advertising coverage it has stopped providing, or
                       ;; peers go on routing to it.
                        :carries (if now
                                   (effective-carries state now)
                                   (:carries state))}])
           ;; Offer the digest at once rather than on the next tick.
            (let [digest (summary state)]
              (for [p added]
                [:send p {:type :have :summary digest}]))))])))

(defn- interested?
  "Should we forward `topic` to `peer-id`?

  Either the peer subscribes to it, or it relays a range covering it."
  [state peer-id topic]
  (let [{:keys [interests carries]} (get-in state [:peers peer-id])]
    (boolean (or
              ;; Nothing announced yet: forward optimistically. Withholding
              ;; until interests arrive deadlocks a fresh connection.
              (nil? interests)
              (topics/subscribes-to? interests topic)
              (topics/covered? carries topic)))))

(defn- forward-targets
  [state topic except]
  (->> (keys (:peers state))
       (remove #(= % except))
       (filter #(interested? state % topic))
       sort))

;; =============================================================================
;; Delivery
;; =============================================================================

(defn- deliver-local
  [state {:keys [topic payload] :as _msg}]
  (if (contains? (:topics state) topic)
    (let [{:keys [max-delivered]} (:opts state)
          delivered (conj (:delivered state) payload)]
      (-> state
          (assoc :delivered (if (> (count delivered) max-delivered)
                              (subvec delivered 1)
                              delivered))
          (update-in [:stats :delivered] inc)))
    state))

(defn- authorized?
  [state topic origin payload]
  ((authz/gate (:opts state)
               {:op :publish
                :legacy-keys [:authorize-fn]
                :legacy-adapter authz/dissemination-legacy})
   {:principal origin :topic topic :payload payload}))

(defn- accept-and-forward
  "Common path for a message we have not seen: record, deliver, relay."
  [state msg from]
  (let [{:keys [origin epoch seq topic hops]} msg
        {:keys [max-hops]} (:opts state)
        state (-> state
                  (mark-seen origin epoch seq)
                  (store-message msg)
                  (deliver-local msg))]
    (if (>= hops max-hops)
      [(update-in state [:stats :hop-expired] inc) []]
      (let [targets (forward-targets state topic from)
            msg' (assoc msg :hops (inc hops))]
        [(update-in state [:stats :forwarded] + (count targets))
         (vec (for [t targets] [:send t msg']))]))))

;; =============================================================================
;; Handler
;; =============================================================================

(defn publish
  "Originate a message on `topic`. Returns `[state actions]`.

  Exposed separately from the handler so an application can call it directly."
  [state topic payload]
  (let [seq-no (:next-seq state)
        msg {:type :gossip
             :topic topic
             :origin (:id state)
             :epoch (:epoch state)
             :seq seq-no
             :hops 0
             :payload payload}
        state (-> state
                  (assoc :next-seq (inc seq-no))
                  (update-in [:stats :published] inc))
        [state actions] (accept-and-forward state msg nil)]
    [state actions]))

(defn handler
  "Dissemination state machine, in the `kabel.sim` handler form."
  [state event {:keys [now] :as _ctx}]
  (case (:type event)

    :init
    {:state state
     :actions [[:timer (get-in state [:opts :have-interval-ms]) :have-tick]]}

    :timer
    (if (= :have-tick (:payload event))
      (let [digest (summary state)
            targets (sort (keys (:peers state)))
            ;; Notice a dormancy transition here rather than on a timer of its
            ;; own — this tick already exists and already talks to everyone.
            now-dormant? (dormant? state now)
            transitioned? (not= now-dormant? (:dormant? state))
            state (assoc state :dormant? now-dormant?)]
        {:state state
         :actions (vec (concat
                        (for [t targets]
                          [:send t {:type :have :summary digest}])
                        ;; Re-announce only on a transition: peers must learn
                        ;; that coverage changed, and saying so every tick
                        ;; would be noise.
                        (when transitioned?
                          (for [t targets]
                            [:send t {:type :interests
                                      :topics (:topics state)
                                      :carries (effective-carries state now)}]))
                        [[:timer (get-in state [:opts :have-interval-ms]) :have-tick]]))})
      {:state state :actions []})

    :message
    (let [from (:from event)
          payload (:payload event)]
      (case (:type payload)

        :publish
        (let [[state actions] (publish state (:topic payload) (:payload payload))]
          {:state state :actions actions})

        :interests
        {:state (assoc-in state [:peers from]
                          {:interests (set (:topics payload))
                           :carries (topics/normalise
                                     (or (:carries payload)
                                         ;; Compatibility with the boolean flag.
                                         (when (:relay? payload)
                                           #{topics/everything})
                                         #{}))})
         :actions []}

        :gossip
        (let [{:keys [origin epoch seq topic]} payload]
          (cond
            ;; Authorisation first, and at every hop — a message we must not
            ;; relay must also not be recorded as seen, or a later authorised
            ;; copy would be suppressed as a duplicate.
            (not (authorized? state topic origin (:payload payload)))
            {:state (update-in state [:stats :unauthorized] inc) :actions []}

            (seen? state origin epoch seq)
            {:state (update-in state [:stats :duplicates] inc) :actions []}

            :else
            (let [[state actions] (accept-and-forward state payload from)]
              {:state state :actions actions})))

        :have
        (let [wants (gaps-against state (:summary payload))]
          {:state state
           :actions (if (seq wants)
                      [[:send from {:type :want :ranges wants}]]
                      [])})

        :want
        (let [{:keys [max-want-ranges]} (:opts state)
              msgs (mapcat #(stored state %)
                           (take max-want-ranges (:ranges payload)))]
          {:state (update-in state [:stats :want-served] + (count msgs))
           :actions (vec (for [m msgs]
                           [:send from (assoc m :hops 0 :repair? true)]))})

        {:state state :actions []}))

    {:state state :actions []}))
