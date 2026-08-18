(ns kabel.convergent
  "Replicating a convergent (CRDT) value over the overlay.

  ## The two paths, and why the transport already has both

  `yggdrasil.convergent` distinguishes them explicitly:

  - **`-join`** — the STATE path. Symmetric, commutative, associative,
    idempotent, *no ancestor*. A full replica merged with a full replica.
  - **`-apply-delta`** — the OP path. `O(δ)`, no full state, and it returns a
    replica carrying no local δ so a remote-integrated op never re-propagates.

  Those are exactly the two things this transport moves. Gossip dissemination
  assumes idempotence and commutativity — duplicates and reordering are free —
  which is precisely what `-join` demands. And the repair horizon is where the
  op path stops being able to help: within it a gap is δs to re-fetch, beyond
  it the δs are gone and only a state merge closes it.

  So a replica converges by three mechanisms, in increasing cost:

      live         δ published, δ applied            O(δ)
      recent gap   δs re-fetched from a peer's store O(gap)
      far behind   full state, merged with -join     O(state)

  That last transition is the one nothing previously drove.
  `kabel.dissemination/beyond-horizon` names the peers we cannot catch up with
  by δ, and this namespace is what turns that signal into a state request. The
  layers each had their half and did not know about each other.

  ## Nothing yggdrasil-shaped is required here

  The four operations are **injected**, exactly as `kabel.content` injects
  `addresses-fn` and `kabel.store` injects storage: kabel depends on no CRDT
  library, and a consumer with a different convergent type — or a hand-rolled
  join-semilattice — wires its own.

      {:join        (fn [a b] …)      ; yggdrasil c/-join
       :apply-delta (fn [replica δ] …); yggdrasil c/-apply-delta
       :delta-of    (fn [replica] δ)  ; yggdrasil c/delta-of
       :clear-delta (fn [replica] …)} ; yggdrasil c/clear-delta

  ## What this does not do

  It does not decide *when* a local change happens — that is the application's,
  and in spindel's case the reactive engine's. `local-change` is called with a
  mutated replica and does the propagation."
  (:require [kabel.topics :as topics]))

(def ^:const delta-type :convergent/delta)
(def ^:const state-request-type :convergent/state-request)
(def ^:const state-type :convergent/state)

(defn make-state
  "A replica bound to `topic`.

  `ops` supplies `:join`, `:apply-delta`, `:delta-of` and `:clear-delta`.
  Only `:join` is strictly required — without `:apply-delta` every update
  travels as full state, which is correct and expensive."
  [id topic value ops]
  {:id id
   :topic topic
   :value value
   :ops ops
   ;; (origin, epoch) we have asked for state, so a stranded signal that
   ;; repeats every digest tick does not become a request storm.
   :requested #{}
   :stats {:deltas-out 0 :deltas-in 0 :states-out 0 :states-in 0
           :state-requests 0 :joins 0}})

(defn value [state] (:value state))

;; =============================================================================
;; Local mutation
;; =============================================================================

(defn local-change
  "Propagate a locally mutated replica. Returns `[state actions]`.

  The δ is taken from the replica's own metadata and cleared, which is
  yggdrasil's contract: a δ that has been propagated must not propagate again,
  and a remote-integrated value carries none to begin with.

  With no δ available — either because the type has no op path or because the
  mutation produced none — nothing is published. Publishing full state on every
  keystroke is the failure mode this exists to avoid."
  [state new-replica]
  (let [{:keys [delta-of clear-delta]} (:ops state)
        d (when delta-of (delta-of new-replica))]
    (if (nil? d)
      [(assoc state :value new-replica) []]
      [(assoc state :value (if clear-delta (clear-delta new-replica) new-replica))
       [[:publish (:topic state) {:type delta-type :delta d}]]])))

;; =============================================================================
;; Incoming
;; =============================================================================

(defn apply-incoming
  "Integrate a payload delivered by dissemination on our topic.

  Returns `[state outcome]`. Anything that is not ours is `:ignored`, because a
  topic may legitimately carry more than one kind of message."
  [state payload]
  (let [{:keys [join apply-delta]} (:ops state)]
    (cond
      (= delta-type (:type payload))
      (if apply-delta
        [(-> state
             (update :value apply-delta (:delta payload))
             (update-in [:stats :deltas-in] inc))
         :delta-applied]
        ;; No op path configured: a δ we cannot apply is not an error, but it
        ;; does mean this replica can only converge by state.
        [state :no-delta-path])

      (= state-type (:type payload))
      [(-> state
           (update :value join (:value payload))
           (update-in [:stats :states-in] inc)
           (update-in [:stats :joins] inc))
       :joined]

      :else [state :ignored])))

;; =============================================================================
;; The horizon → state sync
;; =============================================================================

(defn needs-state?
  "Are we stranded beyond `peer`'s repair horizon for this topic?

  `stranded` is `kabel.dissemination/beyond-horizon`'s output."
  [state stranded]
  (boolean (seq (remove #(contains? (:requested state) [(:origin %) (:epoch %)])
                        stranded))))

(defn request-state
  "Ask peers for full state, because δ repair cannot close the gap.

  Returns `[state actions]`. Each (origin, epoch) is asked once — the stranded
  signal repeats on every digest tick, and re-asking each time would turn a
  slow peer into a broadcast storm."
  [state stranded peers]
  (let [fresh (remove #(contains? (:requested state) [(:origin %) (:epoch %)])
                      stranded)]
    (if (empty? fresh)
      [state []]
      [(-> state
           (update :requested into (map (juxt :origin :epoch) fresh))
           (update-in [:stats :state-requests] inc))
       (vec (for [p (sort peers)]
              [:send p {:type state-request-type :topic (:topic state)}]))])))

(defn handler
  "Answers state requests, and applies state replies.

  Both halves are needed, and the second is easy to forget: a δ arrives through
  dissemination and is handed to `apply-incoming` with the other delivered
  payloads, but a state REPLY is a direct send to one peer and never passes
  through dissemination at all. Handling only the request leaves a replica that
  asks for state, is sent it, and ignores it — which looks exactly like the
  horizon signal not being wired.

  Serving state is `O(state)` and is requested by strangers, so it wants a
  bound: here only a connected peer can ask, and `kabel.ratelimit` meters how
  often."
  [state event _ctx]
  (case (:type event)
    :message
    (let [from (:from event)
          payload (:payload event)]
      (cond
        (and (= state-request-type (:type payload))
             (= (:topic state) (:topic payload)))
        {:state (update-in state [:stats :states-out] inc)
         :actions [[:send from {:type state-type
                                :topic (:topic state)
                                :value (:value state)}]]}

        (and (= state-type (:type payload))
             (= (:topic state) (:topic payload)))
        {:state (first (apply-incoming state payload)) :actions []}

        :else {:state state :actions []}))

    {:state state :actions []}))

(defn interested-in?
  "Does a peer carrying `ranges` relay this replica's topic?"
  [ranges topic]
  (topics/covered? ranges topic))
