(ns kabel.topics
  "Hierarchical topics and the ranges that cover them.

  ## Why ranges

  A boolean relay flag is all-or-nothing: carry every topic in the network, or
  only the ones you personally subscribe to. Measured on a 40-node mesh, that
  is a 4× difference in traffic — and neither end of it is what a real
  deployment wants. A relay should be able to say *which slice* it carries.

  This is also the shape replikativ already had. It filtered publications with
  `(get-in identities [user crdt-id])` — an exact match over a two-level
  `[user crdt-id]` namespace — and propagated subscriptions transitively, with
  `extend?` as the relay flag. The hierarchy was there; only the ability to
  name a *prefix* of it was missing.

  ## The model

  A topic is a keyword (`:db/roots`) or a vector (`[:db \"alice\" \"kb1\"]`).
  A **range** is a vector prefix, and `[]` covers everything:

      []                      ; the whole network — the old `:relay? true`
      [:db]                   ; every database topic
      [:db \"alice\"]           ; everything of alice's
      [:db \"alice\" \"kb1\"]     ; exactly one topic

  Prefix rather than hash-range because the hierarchy is *meaningful* here:
  \"everything of alice's\" is a thing an operator wants to say, and a hash range
  cannot express it. A hash-prefix keyspace would be the right choice if the
  goal were uniform load distribution across anonymous peers, which is a DHT's
  problem and not ours."
  (:refer-clojure :exclude [empty]))

(def everything
  "The range covering every topic — what `:relay? true` used to mean."
  [])

(defn topic-path
  "A topic as a vector path. A keyword is a one-element path, so keyword and
  vector topics live in one namespace and neither is a special case."
  [topic]
  (if (vector? topic) topic [topic]))

(defn covers?
  "Does `range` cover `topic`?

  `[]` covers everything; otherwise the range must be a prefix of the topic's
  path. A range *longer* than the topic never covers it — `[:db \"alice\" \"kb1\"]`
  does not cover `[:db \"alice\"]`, because carrying a leaf says nothing about
  carrying its parent."
  [range topic]
  (let [r (topic-path range)
        t (topic-path topic)]
    (and (<= (count r) (count t))
         (= r (subvec t 0 (count r))))))

(defn covered?
  "Does any range in `ranges` cover `topic`?"
  [ranges topic]
  (boolean (some #(covers? % topic) ranges)))

(defn overlaps?
  "Could a peer carrying `ranges` be useful to someone wanting `topics`?

  True when any range covers any topic. This is what makes peer selection
  topic-aware: without it, membership picks peers blind to what they carry and
  a subscriber has no way to prefer a peer that actually serves its interests."
  [ranges topics]
  (boolean (some (fn [t] (covered? ranges t)) topics)))

(defn normalise
  "Canonical form of a range set: paths as vectors, and any range that is
  already covered by a broader one removed.

  `#{[] [:db \"alice\"]}` is just `#{[]}` — keeping the redundant entry would
  make two equal advertisements compare unequal, and would let a peer inflate
  its advertised coverage with subsumed noise."
  [ranges]
  (let [rs (into #{} (map topic-path) ranges)]
    (into #{}
          (remove (fn [r]
                    (some (fn [other]
                            (and (not= other r)
                                 (< (count other) (count r))
                                 (covers? other r)))
                          rs)))
          rs)))

(defn subscribes-to?
  "Does a peer with these `topics` want `topic` delivered to it?

  Subscription is exact: a peer subscribed to `[:db \"alice\"]` is not thereby
  subscribed to `[:db \"alice\" \"kb1\"]`. Ranges say what you *relay*;
  subscriptions say what you *deliver*, and conflating them would silently
  deliver a peer everything under a prefix it merely agreed to carry."
  [topics topic]
  (boolean (contains? (set topics) topic)))
