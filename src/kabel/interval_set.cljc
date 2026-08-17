(ns kabel.interval-set
  "A compact set of integers held as merged, sorted, disjoint ranges.

  This is what a dissemination layer should remember instead of a cache of
  message ids.

  ## Why not a seen-cache with a TTL

  gossipsub remembers message ids in a time-bounded cache — 2 minutes, no size
  cap, no overflow path — so its memory is `arrival-rate × 120 s` and a burst
  is unbounded (`.internal/reference/gossipsub.md`). It has to work that way,
  because it cannot assume its message ids are anything but opaque hashes.

  partisan can do better, and does: its Plumtree backend tracks
  `{origin, epoch, monotonic-seq}` and collapses each origin's history to a
  handful of intervals — `O(#peers)` rows, **no TTL and no GC at all**
  (`.internal/reference/partisan.md`). A peer that has received everything
  from an origin needs exactly one interval to say so.

  We can use that because our senders number their own messages, which
  replikativ's per-peer CRDT operations already do.

  ## The second job

  An interval set is not only a membership test, it is a *query*: `missing`
  answers \"what have I not seen from this origin\" directly, which is the
  repair path. In Plumtree terms it turns the lazy IHAVE/GRAFT dance into a
  gap query; in replikativ terms it is the same shape as the fetch that pulls
  commits an operation referenced but the peer does not hold.

  ## Representation

  A vector of inclusive `[lo hi]` pairs, sorted ascending, non-overlapping and
  non-adjacent — `[[1 3] [5 5]]`, never `[[1 3] [4 5]]` (which would be
  `[[1 5]]`) and never `[[5 5] [1 3]]`. Every function preserves that, and
  `kabel.interval-set-test/canonical-form-is-preserved` checks it after
  arbitrary insertion orders."
  (:refer-clojure :exclude [contains? empty merge count])
  (:require [clojure.core :as core]))

(def empty
  "The empty interval set."
  [])

(defn contains?
  "Is `n` in the set?"
  [iset n]
  (boolean (some (fn [[lo hi]] (and (<= lo n) (<= n hi))) iset)))

(defn add
  "Add `n`, merging with any range it touches or bridges.

  Adjacency merges as well as overlap: adding 4 to `[[1 3] [5 7]]` yields
  `[[1 7]]`, not three ranges. Without that the set would grow one entry per
  message under out-of-order arrival, which is exactly the case it exists to
  survive."
  [iset n]
  (if (contains? iset n)
    iset
    (let [touching? (fn [[lo hi]] (and (<= lo (inc n)) (<= (dec n) hi)))
          before (filterv (fn [[_ hi]] (< hi (dec n))) iset)
          after (filterv (fn [[lo _]] (> lo (inc n))) iset)
          mid (filterv touching? iset)
          lo (apply min n (map first mid))
          hi (apply max n (map second mid))]
      (vec (concat before [[lo hi]] after)))))

(defn add-all
  "Add every element of `ns`."
  [iset ns]
  (reduce add iset ns))

(defn maximum
  "The largest member, or nil when empty."
  [iset]
  (when (seq iset) (second (last iset))))

(defn minimum
  "The smallest member, or nil when empty."
  [iset]
  (when (seq iset) (first (first iset))))

(defn missing
  "Ranges within `[lo hi]` that are **not** in the set, as `[lo hi]` pairs.

  This is the \"what am I missing\" query, and the reason an interval set beats
  a seen-cache: the answer is derived from the same structure that answers the
  duplicate check, so the two can never disagree."
  [iset lo hi]
  (if (> lo hi)
    []
    (let [clipped (->> iset
                       (keep (fn [[a b]]
                               (let [a' (max a lo) b' (min b hi)]
                                 (when (<= a' b') [a' b']))))
                       (sort-by first))]
      (loop [cursor lo
             [[a b] & more] clipped
             gaps []]
        (cond
          (nil? a) (if (<= cursor hi) (conj gaps [cursor hi]) gaps)
          (> a cursor) (recur (inc b) more (conj gaps [cursor (dec a)]))
          :else (recur (max cursor (inc b)) more gaps))))))

(defn count-ranges
  "How many ranges the set occupies — its actual memory cost.

  Worth asserting in tests: a set that has received everything should be one
  range, and a pathological arrival order should still collapse once the gaps
  fill."
  [iset]
  (core/count iset))

(defn cardinality
  "How many integers are in the set."
  [iset]
  (reduce + 0 (map (fn [[lo hi]] (inc (- hi lo))) iset)))

(defn merge-sets
  "Union of two interval sets."
  [a b]
  (reduce (fn [acc [lo hi]]
            (reduce add acc (range lo (inc hi))))
          a
          b))

(defn canonical?
  "Is `iset` sorted, disjoint and non-adjacent?

  Exposed because it is the invariant every other function promises, and a
  test that checks it after arbitrary operations is worth more than one that
  checks a few examples."
  [iset]
  (and (every? (fn [[lo hi]] (<= lo hi)) iset)
       (every? (fn [[[_ hi1] [lo2 _]]] (> lo2 (inc hi1)))
               (partition 2 1 iset))))
