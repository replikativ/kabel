(ns kabel.negotiate
  "How two peers' capability sets converge on one agreement.

  This is **logic without a wire**, deliberately. `agree` is a pure function;
  nothing here sends, receives, or defines a handshake. It exists so that
  whichever handshake ends up carrying capabilities does not have to re-derive
  the one subtle part.

  ## Why there is no middleware here

  An earlier version of this namespace shipped a middleware that exchanged its
  own hello on connect. It was removed before release, because the overlay
  already has the handshake it was trying to be:

  - `kabel.overlay.runtime` announces a **signed identity record** on every
    connection, and `register!` binds a peer id to its channel only after that
    record verifies. A peer that does not run the overlay never sends one, is
    never registered, and never receives an overlay frame — so feature gating
    already exists, and unlike a bare capability hello it is *authenticated*.
  - Frames for an unregistered peer go to an **outbox** and are flushed on
    registration, so nothing is written to a peer before its handshake
    resolves. That is the send barrier a codec agreement needs, and it is
    already there.

  A second, unauthenticated hello at a second layer added a round trip, a
  timeout race, and a way for a stranger to assert `:max-frame` and be
  believed — in exchange for an agreement nothing consumed.

  ## What still needs solving

  Codec selection. Inbound is already self-describing — every frame carries its
  codec id (`kabel.binary.table`), so a peer can READ anything it supports.
  What is missing is knowing what the peer can read **before you write**, and
  `kabel.middleware.dual` is today's answer: read both, write one, in a
  three-step deployment that cannot skip step 1.

  The shape that would replace it is the identity hello carrying capabilities,
  so registration and agreement become one authenticated event. `agree` is the
  part of that worth keeping in advance.

  ## Agreement without a tie-break

  Both ends must independently reach the *same* answer, and \"first of my
  preferences that appears in yours\" does not: given `[:cbor :fressian]` and
  `[:fressian :cbor]`, each end picks its own favourite and neither notices.

  So the choice is the intersection ranked by a **canonical** order both sides
  already share — the frame id, where ids are assigned strictly increasing and
  never recycled. No tie-break, and the outcome does not depend on who dialled.
  That is the part easy to get wrong twice, which is why it is tested and kept."
  (:require [kabel.binary.table :as table]
            [clojure.set :as set]))

(defn- rank
  "Canonical rank of a codec: its frame id. Unknown codecs rank below
  everything, so a peer advertising a name we have never heard of cannot win."
  [c]
  (get table/encoding-table c -1))

(def text-codecs
  "Codecs whose payload is text and therefore survives a transport that cannot
  carry bytes — SSE, or anything else without a binary frame."
  #{:transit-json :string :pr-str})

(defn capabilities
  "A capability set, for `agree`.

  `codecs` must be what the peer's middleware stack actually **installs**, not
  what this namespace can name. There is deliberately no default: a stack is a
  composed function and cannot be introspected, so only the caller knows — and
  a wrong advertisement is an agreement on a codec neither end can decode."
  [{:keys [codecs features max-frame binary?]}]
  {:kabel/protocol 1
   :kabel/codecs (vec codecs)
   :kabel/features (set features)
   :kabel/max-frame max-frame
   :kabel/binary? (boolean binary?)})

(defn agree
  "Agreed capabilities from ours and theirs, or `nil` if there is no common
  ground. Both arguments are `capabilities` maps.

  Returns `{:codec :codecs :features :max-frame :binary? :protocol}`.

  Deterministic and symmetric: both ends compute the same map from the same two
  inputs, which is the property that makes it safe to run on both sides at once
  and the one the tests actually assert.

  Does **no validation** of `theirs`, because it has no wire of its own: a
  caller that reads capabilities off a network must validate first, and
  `:protocol` is recorded rather than enforced."
  [ours theirs]
  (let [mine (set (:kabel/codecs ours))
        yours (set (:kabel/codecs theirs))
        ;; Binary is a veto, not a vote: if EITHER transport cannot carry
        ;; bytes, a binary codec is not an option however much both ends like
        ;; it. This is how a transport's constraint reaches the codec choice.
        binary? (and (boolean (:kabel/binary? ours))
                     (boolean (:kabel/binary? theirs)))
        common (cond-> (set/intersection mine yours)
                 (not binary?) (set/intersection text-codecs))]
    (when (seq common)
      {:codec (apply max-key rank (sort common))
       :codecs common
       :features (set/intersection (set (:kabel/features ours))
                                   (set (:kabel/features theirs)))
       ;; The MINIMUM, so neither end can talk the other into buffering more
       ;; than it chose to.
       :max-frame (min (:kabel/max-frame ours)
                       (or (:kabel/max-frame theirs) (:kabel/max-frame ours)))
       :binary? binary?
       :protocol (or (:kabel/protocol theirs) 1)})))
