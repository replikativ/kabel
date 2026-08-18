(ns kabel.overlay
  "L1 + L2 composed: membership below, dissemination above.

  This is the whole point of the two layers — seed a node with one address and
  it finds peers, forms a mesh, and publishes reach everyone on the topic,
  with no peer wired by hand. That is what replikativ needed and did not have:
  its P2P was explicit wiring of every pair (`replikativ`'s deleted
  `connect.cljc`), and this replaces it.

  The composition is deliberately mechanical. Both layers are pure
  `(state, event, ctx) -> {:state, :actions}` machines over disjoint message
  and timer vocabularies, so composing them is: run both, concatenate the
  actions, and feed membership's connection set into dissemination's peer set
  between the two. The rng is threaded through both so the whole node remains
  a pure function of its seed."
  (:require [kabel.content :as c]
            [kabel.topics]
            [kabel.dissemination :as d]
            [kabel.membership :as m]
            [kabel.sim.rng :as rng]))

(defn make-state
  "State for an overlay node.

  Options:
  - `:addresses`     — our own addresses, announced to peers on connect
  - `:seeds`         — configured peers, `[{:peer-id … :addresses […] :group …}]`
  - `:topics`        — topics this node subscribes to
  - `:membership`    — options passed to `kabel.membership/make-state`
  - `:dissemination` — options passed to `kabel.dissemination/make-state`
  - `:content`       — options passed to `kabel.content/make-state`
  - `:blocks`        — content we hold and can serve, `{key value}`
  - `:carries`       — topic RANGES we relay, `#{[]}` (everything) by default
  - `:relay?`        — legacy boolean; false means carry nothing but our own"
  [id {:keys [addresses seeds topics membership dissemination content blocks
              relay? carries]
       :or {seeds [] topics #{} relay? true}}]
  {:id id
   :rng (rng/make-rng 0)
   :membership (m/make-state id seeds (assoc (or membership {})
                                             :addresses (vec addresses)))
   :dissemination (assoc (d/make-state id topics (or dissemination {}))
                         :carries (cond
                                    carries (kabel.topics/normalise carries)
                                    relay? #{kabel.topics/everything}
                                    :else #{}))
   :content (c/make-state id (or blocks {}) (or content {}))})

(defn handler
  [state event ctx]
  (let [;; Membership first: its connection set is dissemination's input.
        ms (assoc (:membership state) :rng (:rng state) :id (:id state))
        {ms' :state ma :actions} (m/handler ms event ctx)

        peers (keys (:connections ms'))

        ds (assoc (:dissemination state) :rng (:rng ms') :id (:id state))
        [ds d-sync] (d/sync-peers ds peers (:now ctx))
        {ds' :state da :actions} (d/handler ds event ctx)

        cs (assoc (:content state) :rng (:rng ds') :id (:id state))
        [cs c-sync] (c/sync-peers cs peers)
        {cs' :state ca :actions} (c/handler cs event ctx)]
    {:state (assoc state
                   :membership (dissoc ms' :rng)
                   :dissemination (dissoc ds' :rng)
                   :content (dissoc cs' :rng)
                   :rng (:rng cs'))
     :actions (vec (concat ma d-sync da c-sync ca))}))

;; =============================================================================
;; Inspection
;; =============================================================================

(defn connections [state] (keys (get-in state [:membership :connections])))

(defn delivered [state] (get-in state [:dissemination :delivered]))

(defn stats [state]
  (merge (get-in state [:membership :stats])
         (get-in state [:dissemination :stats])
         (get-in state [:content :stats])))

(defn have?
  "Do we hold `k` locally?"
  [state k]
  (c/have? (:content state) k))

(defn blocks [state] (get-in state [:content :blocks]))
