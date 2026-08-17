(ns kabel.authorize
  "One authorization gate shape, for every layer that has one.

  ## The bug this exists to fix

  `:authorize-fn` meant two incompatible things under one name:

  - `kabel.pubsub` called it `(fn [principal topic])`
  - `kabel.dissemination` called it `(fn [topic origin])`

  A consumer who wrote one predicate and passed it to both got `principal`
  bound to a topic and `topic` bound to an origin. Nothing threw; the gate
  simply answered a different question than the one it was written for, and
  leaned open or closed depending on how the predicate happened to be
  structured. An authorization check that silently answers the wrong question
  is worse than one that is missing, because it looks present.

  ## The shape

      (fn [{:keys [op principal topic payload]}] -> truthy)

  - `:op`        — `:subscribe` or `:publish`
  - `:principal` — who is asking. In `kabel.pubsub` this is whatever an
                   upstream auth middleware stamped as `:kabel/principal`; in
                   the overlay it is the **verified origin peer id**, which is
                   the principal a self-certifying network actually has.
  - `:topic`     — the topic
  - `:payload`   — the message body, present for `:publish`

  A map rather than positional arguments, for two reasons. It cannot be
  misordered, which is the whole point. And it is extensible: `:payload` is
  here because \"may this key set the root of *that* database\" is not
  answerable from the topic alone, and the positional form had no room for it.

  ## Compatibility

  `kabel.pubsub` is released, so its `:authorize-fn` / `:authorize-publish-fn`
  keep working unchanged, called exactly as before. `:authorize` is the new
  option and takes precedence. The two legacy shapes are adapted here rather
  than at each call site, so the divergence cannot reappear."
  (:refer-clojure :exclude [resolve]))

(defn pubsub-legacy
  "Adapt `kabel.pubsub`'s historical `(fn [principal topic])`."
  [f]
  (fn [{:keys [principal topic]}] (f principal topic)))

(defn dissemination-legacy
  "Adapt `kabel.dissemination`'s historical `(fn [topic origin])`.

  Note the argument order — reversed relative to pubsub's, which is the bug."
  [f]
  (fn [{:keys [topic principal]}] (f topic principal)))

(defn gate
  "Resolve an authorization predicate from `opts`.

  Returns `(fn [ctx-map] -> boolean)`, defaulting to permit-all so a layer with
  no policy configured behaves as it always has.

  `:op` is stamped into the context, so one `:authorize` can serve both the
  subscribe and publish gates and tell them apart.

  `legacy-keys` are tried in order, each adapted through `legacy-adapter` — the
  publish gate passes `[:authorize-publish-fn :authorize-fn]` so that a
  consumer who set only the latter keeps today's behaviour."
  [opts {:keys [op legacy-keys legacy-adapter]}]
  (if-let [f (:authorize opts)]
    (fn [ctx] (boolean (f (assoc ctx :op op))))
    (if-let [legacy (some #(get opts %) legacy-keys)]
      (let [adapted (legacy-adapter legacy)]
        (fn [ctx] (boolean (adapted ctx))))
      (constantly true))))
