(ns kabel.sim.rng-test
  "Tests for the portable seeded RNG.

  Two kinds of test here, and both are deliberate:

  - **Known-answer tests** pin the exact stream, so the JVM and ClojureScript
    are proven to agree rather than assumed to. A 32-bit truncation would show
    up on exactly one platform, which is the failure mode
    `.internal/DHT_DESIGN.md` §5 is about.
  - **Property tests on `shuffle` and `sample`**, because a peer-selection
    function that is silently not random is a documented, shipped bug —
    partisan's `shuffle/2` returns the first K peers and reorders them, and
    nothing caught it."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.sim.rng :as rng]))

(defn- take-u32
  "The first `n` draws from `rng`, as a vector."
  [rng n]
  (loop [rng rng acc [] n n]
    (if (zero? n)
      acc
      (let [[rng' v] (rng/next-u32 rng)]
        (recur rng' (conj acc v) (dec n))))))

;; =============================================================================
;; The 32-bit trap
;; =============================================================================

(deftest u32-is-unsigned-on-both-platforms
  (testing "the exact expression that cost a day in urd"
    ;; (bit-and 0xFFFFFFFF 0xFFFFFFFF) is -1 in ClojureScript. If u32 ever
    ;; regressed to a bare bit-and, this is the assertion that fails — and it
    ;; fails only on cljs, which is why the cljs suite must run it.
    (is (= 4294967295 (rng/u32 0xFFFFFFFF)))
    (is (= 4294967295 (rng/u32 -1)))
    (is (= 0 (rng/u32 0)))
    (is (= 1 (rng/u32 1)))
    (is (= 2147483648 (rng/u32 2147483648))))

  (testing "every draw is a valid unsigned 32-bit value"
    (doseq [seed [0 1 42 -1 123456789]]
      (doseq [v (take-u32 (rng/make-rng seed) 200)]
        (is (and (>= v 0) (< v 4294967296))
            (str "seed " seed " produced out-of-range " v))))))

;; =============================================================================
;; Known answers — the cross-platform lock
;; =============================================================================

(deftest stream-known-answer
  (testing "seed 42 produces exactly this stream on every platform"
    (is (= [3846735079 2924517991 2338178090 259161812 2931600481]
           (take-u32 (rng/make-rng 42) 5))))

  (testing "shuffle and sample are pinned too"
    (is (= [9 8 3 5 2 0 1 7 4 6]
           (second (rng/shuffle (rng/make-rng 7) (range 10)))))
    (is (= [9 8 3]
           (second (rng/sample (rng/make-rng 7) 3 (range 10))))))

  (testing "bounded draws are pinned"
    (is (= [4 2 3 5 2 2 0 1]
           (loop [r (rng/make-rng 1) acc [] n 8]
             (if (zero? n)
               acc
               (let [[r' v] (rng/rand-int r 6)]
                 (recur r' (conj acc v) (dec n)))))))))

(deftest seeding
  (testing "the same seed gives the same stream"
    (is (= (take-u32 (rng/make-rng 99) 50)
           (take-u32 (rng/make-rng 99) 50))))

  (testing "adjacent seeds give unrelated streams"
    ;; Without the warm-up in make-rng, neighbouring seeds start out similar.
    (is (not= (take-u32 (rng/make-rng 1) 20)
              (take-u32 (rng/make-rng 2) 20)))
    (is (not= (take-u32 (rng/make-rng 0) 20)
              (take-u32 (rng/make-rng 1) 20))))

  (testing "an all-zero state cannot arise, since it would emit only zeros"
    (is (not= [0 0 0 0 0] (take-u32 (rng/make-rng 0) 5)))))

(deftest purity
  (testing "drawing does not mutate the rng it was given"
    (let [r (rng/make-rng 5)
          [_ a] (rng/next-u32 r)
          [_ b] (rng/next-u32 r)]
      (is (= a b) "the same rng value must yield the same draw"))))

;; =============================================================================
;; Bounded draws
;; =============================================================================

(deftest rand-int-properties
  (testing "always in range"
    (doseq [n [1 2 3 6 7 100 1000]]
      (loop [r (rng/make-rng n) i 0]
        (when (< i 500)
          (let [[r' v] (rng/rand-int r n)]
            (is (and (>= v 0) (< v n)) (str "n=" n " produced " v))
            (recur r' (inc i)))))))

  (testing "bound of 1 is always 0"
    (is (= [0 0 0] (loop [r (rng/make-rng 3) acc [] n 3]
                     (if (zero? n)
                       acc
                       (let [[r' v] (rng/rand-int r 1)]
                         (recur r' (conj acc v) (dec n))))))))

  (testing "a non-positive bound is refused rather than looping"
    (is (thrown? #?(:clj Exception :cljs js/Error) (rng/rand-int (rng/make-rng 1) 0)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (rng/rand-int (rng/make-rng 1) -5))))

  (testing "the distribution is not obviously skewed"
    ;; Rejection sampling exists so that `mod` bias does not quietly skew peer
    ;; selection. 6000 draws over 6 buckets should land near 1000 each; a
    ;; modulo-biased generator over a bound that does not divide 2^32 shows up
    ;; as a systematic tilt, not as noise.
    (let [counts (loop [r (rng/make-rng 12345) acc (vec (repeat 6 0)) n 6000]
                   (if (zero? n)
                     acc
                     (let [[r' v] (rng/rand-int r 6)]
                       (recur r' (update acc v inc) (dec n)))))]
      (doseq [c counts]
        (is (< 850 c 1150) (str "bucket counts skewed: " counts)))))

  (testing "rand-range is inclusive at both ends"
    (let [vs (loop [r (rng/make-rng 8) acc #{} n 400]
               (if (zero? n)
                 acc
                 (let [[r' v] (rng/rand-range r 3 5)]
                   (recur r' (conj acc v) (dec n)))))]
      (is (= #{3 4 5} vs))))

  (testing "rand-range on a single point does not draw"
    (is (= [(rng/make-rng 1) 7] (rng/rand-range (rng/make-rng 1) 7 7)))))

(deftest rand-bool-properties
  (testing "p=0 never, p=1 always"
    (let [draw (fn [p] (loop [r (rng/make-rng 4) acc #{} n 100]
                         (if (zero? n)
                           acc
                           (let [[r' v] (rng/rand-bool r p)]
                             (recur r' (conj acc v) (dec n))))))]
      (is (= #{false} (draw 0.0)))
      (is (= #{true} (draw 1.0)))
      (is (= #{true false} (draw 0.5))))))

;; =============================================================================
;; shuffle / sample — the partisan bug
;; =============================================================================

(deftest shuffle-is-a-permutation
  (testing "shuffle preserves the multiset"
    (doseq [seed (range 20)]
      (let [in (range 12)
            [_ out] (rng/shuffle (rng/make-rng seed) in)]
        (is (= (frequencies in) (frequencies out))))))

  (testing "degenerate inputs"
    (is (= [] (second (rng/shuffle (rng/make-rng 1) []))))
    (is (= [:a] (second (rng/shuffle (rng/make-rng 1) [:a]))))))

(deftest shuffle-actually-randomises
  (testing "every element can reach every position"
    ;; THE regression test for partisan's shuffle/2, which selects the first K
    ;; and only reorders them — meaning later elements never reach an early
    ;; position. Over enough seeds a correct Fisher-Yates puts every element
    ;; in every slot at least once; a first-K selector cannot.
    (let [n 6
          seen (reduce (fn [acc seed]
                         (let [[_ out] (rng/shuffle (rng/make-rng seed) (range n))]
                           (reduce (fn [a pos] (update a pos (fnil conj #{}) (nth out pos)))
                                   acc
                                   (range n))))
                       {}
                       (range 300))]
      (doseq [pos (range n)]
        (is (= (set (range n)) (get seen pos))
            (str "position " pos " only ever saw " (get seen pos))))))

  (testing "the identity permutation is not the only outcome"
    (let [outs (set (for [seed (range 50)]
                      (second (rng/shuffle (rng/make-rng seed) (range 8)))))]
      (is (> (count outs) 20) "shuffle produced suspiciously few distinct orders"))))

(deftest sample-properties
  (testing "returns k distinct elements"
    (doseq [seed (range 20)]
      (let [[_ out] (rng/sample (rng/make-rng seed) 3 (range 10))]
        (is (= 3 (count out)))
        (is (= 3 (count (set out))))
        (is (every? (set (range 10)) out)))))

  (testing "asking for more than exists yields everything, not an error"
    (let [[_ out] (rng/sample (rng/make-rng 1) 99 [:a :b :c])]
      (is (= #{:a :b :c} (set out)))))

  (testing "sample is not 'the first k'"
    ;; The same anti-regression as above, at the call site peer selection
    ;; actually uses. If sample degenerated to `take`, every draw over any
    ;; seed would be [0 1 2].
    (let [outs (set (for [seed (range 100)]
                      (second (rng/sample (rng/make-rng seed) 3 (range 20)))))]
      (is (> (count outs) 30))
      (is (not= #{[0 1 2]} outs))))

  (testing "sampling zero"
    (is (= [] (second (rng/sample (rng/make-rng 1) 0 (range 5)))))))

(deftest rand-nth-properties
  (testing "picks members, and eventually all of them"
    (let [picked (loop [r (rng/make-rng 2) acc #{} n 200]
                   (if (zero? n)
                     acc
                     (let [[r' v] (rng/rand-nth r [:a :b :c])]
                       (recur r' (conj acc v) (dec n)))))]
      (is (= #{:a :b :c} picked))))

  (testing "an empty collection is refused"
    (is (thrown? #?(:clj Exception :cljs js/Error) (rng/rand-nth (rng/make-rng 1) [])))))

(deftest weighted-choice-properties
  (testing "zero-weight items are never chosen"
    (let [picked (loop [r (rng/make-rng 11) acc #{} n 300]
                   (if (zero? n)
                     acc
                     (let [[r' v] (rng/weighted-choice r {:a 1 :b 0 :c 1})]
                       (recur r' (conj acc v) (dec n)))))]
      (is (= #{:a :c} picked))))

  (testing "weights are respected approximately"
    (let [counts (loop [r (rng/make-rng 13) acc {} n 2000]
                   (if (zero? n)
                     acc
                     (let [[r' v] (rng/weighted-choice r {:rare 1 :common 9})]
                       (recur r' (update acc v (fnil inc 0)) (dec n)))))]
      (is (< 100 (:rare counts) 300) (str "counts " counts))
      (is (< 1700 (:common counts) 1900) (str "counts " counts))))

  (testing "the result does not depend on map iteration order"
    ;; Hash order differs between platforms; weighted-choice sorts its entries
    ;; so that two equal maps built differently draw identically.
    (is (= (second (rng/weighted-choice (rng/make-rng 5) {:a 1 :b 2 :c 3}))
           (second (rng/weighted-choice (rng/make-rng 5) (into (sorted-map) {:c 3 :b 2 :a 1}))))))

  (testing "a zero total weight is refused"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (rng/weighted-choice (rng/make-rng 1) {:a 0 :b 0})))))
