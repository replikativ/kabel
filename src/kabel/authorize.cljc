(ns kabel.authorize
  "One authorization gate shape, for every layer that has one.

  ## The hazard this exists to prevent

  A positional `(fn [principal topic])` cannot be told apart from a
  `(fn [topic principal])`. Pass the wrong one and nothing throws: the gate
  answers a different question than the one the predicate was written for, and
  leans open or closed depending on how that predicate happens to be
  structured. An authorization check that silently answers the wrong question
  is worse than one that is missing, because it looks present.

  That is not hypothetical -- while `kabel.pubsub` and the overlay's
  dissemination gate were being written together, they drifted into exactly
  those two orders. It was caught before either shipped, and this namespace is
  what stops it recurring.

  ## The shape

      (fn [{:keys [op principal topic payload]}] -> truthy)

  - `:op`        — `:subscribe` or `:publish`
  - `:principal` — who is asking. In `kabel.pubsub` this is whatever an
                   upstream auth middleware stamped as `:kabel/principal`,
                   typically a JWT subject; in a self-certifying overlay it is
                   the verified peer id. **Same key, different identity
                   systems** -- a consumer serving both should discriminate on
                   `:op` rather than assume.
  - `:topic`     — the topic
  - `:payload`   — the message body, present for `:publish`

  A map rather than positional arguments, for two reasons. It cannot be
  misordered, which is the whole point. And it is extensible: `:payload` is
  here because \"may this key set the root of *that* database\" is not
  answerable from the topic alone, and the positional form had no room for it.

  ## What a user has to do

  Nothing, unless they want to. `kabel.pubsub` is released, so
  `:authorize-fn (fn [principal topic])` and `:authorize-publish-fn` keep
  working exactly as before, called with the same arguments in the same order.

  `:authorize` is the new option and takes precedence when both are given. New
  code should use it; existing code has no reason to change.

  ## A decision may take time

  A predicate may return a core.async channel instead of a boolean; the layer
  asking takes the decision from it. That is how a policy that has to consult
  storage (a permission graph in a database, say) keeps that work off the go
  dispatch pool: it decides on a thread and hands back the thread's channel.
  A predicate that answers from memory returns the boolean as before.

  There is deliberately no positional variant for layers that have not shipped
  -- a second spelling nobody depends on is a divergence invented rather than
  inherited."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-proto]))

(defn channel?
  "Is `x` a core.async channel, i.e. a decision still to be taken?"
  [x]
  (satisfies? async-proto/ReadPort x))

(defn- settle
  "A predicate's answer as the caller receives it: a channel as it is, for the
   caller to take from; anything else as a boolean."
  [v]
  (if (channel? v) v (boolean v)))

(defn decision
  "A channel yielding the boolean decision behind `v`, whether `v` is a
   channel or already a boolean, so a caller inside a go block can always
   take. A channel that closes without a value decides false."
  [v]
  (if (channel? v)
    (async/map boolean [v])
    (doto (async/promise-chan) (async/put! (boolean v)))))

(defn pubsub-legacy
  "Adapt `kabel.pubsub`'s historical `(fn [principal topic])`."
  [f]
  (fn [{:keys [principal topic]}] (f principal topic)))

(defn gate
  "Resolve an authorization predicate from `opts`.

  Returns `(fn [ctx-map] -> boolean)`, defaulting to permit-all so a layer with
  no policy configured behaves as it always has.

  `:op` is stamped into the context, so one `:authorize` can serve both the
  subscribe and publish gates and tell them apart.

  `legacy-keys` are tried in order, each adapted through `legacy-adapter` — the
  publish gate passes `[:authorize-publish-fn :authorize-fn]` so that a
  consumer who set only the latter keeps today's behaviour. A caller with no
  released positional form passes neither and gets `:authorize` or
  permit-all."
  [opts {:keys [op legacy-keys legacy-adapter]}]
  (if-let [f (:authorize opts)]
    (fn [ctx] (settle (f (assoc ctx :op op))))
    (if-let [legacy (some #(get opts %) legacy-keys)]
      (let [adapted (legacy-adapter legacy)]
        (fn [ctx] (settle (adapted ctx))))
      (constantly true))))
