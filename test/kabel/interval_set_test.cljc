(ns kabel.interval-set-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.interval-set :as is*]
            [kabel.sim.rng :as rng]))

(deftest basic-membership
  (testing "the empty set contains nothing"
    (is (not (is*/contains? is*/empty 0)))
    (is (nil? (is*/maximum is*/empty)))
    (is (nil? (is*/minimum is*/empty))))

  (testing "what goes in is in, and nothing else is"
    (let [s (is*/add-all is*/empty [3 1 2 7])]
      (is (every? #(is*/contains? s %) [1 2 3 7]))
      (is (not-any? #(is*/contains? s %) [0 4 5 6 8])))))

(deftest ranges-merge
  (testing "consecutive additions collapse to a single range"
    (let [s (is*/add-all is*/empty (range 1 101))]
      (is (= [[1 100]] s))
      (is (= 1 (is*/count-ranges s)))))

  (testing "adjacency merges, not just overlap"
    ;; If this regressed to merging only on overlap, the set would grow one
    ;; range per message under out-of-order arrival — the exact case it exists
    ;; to survive.
    (let [s (-> is*/empty (is*/add-all [1 2 3]) (is*/add-all [5 6 7]))]
      (is (= [[1 3] [5 7]] s))
      (is (= [[1 7]] (is*/add s 4)) "adding the bridging element did not merge")))

  (testing "a gap that fills from both sides collapses"
    (let [s (-> is*/empty (is*/add-all [1 2 3 8 9 10]) (is*/add-all [4 5 6 7]))]
      (is (= [[1 10]] s))))

  (testing "adding an existing member changes nothing"
    (let [s (is*/add-all is*/empty [1 2 3])]
      (is (= s (is*/add s 2))))))

(deftest canonical-form-is-preserved
  (testing "any arrival order yields the same canonical set"
    ;; The property that matters: dissemination delivers out of order, so the
    ;; structure must not depend on the order it learned things in.
    (doseq [seed (range 40)]
      (let [ns* (range 40)
            [_ shuffled] (rng/shuffle (rng/make-rng seed) ns*)
            a (is*/add-all is*/empty ns*)
            b (is*/add-all is*/empty shuffled)]
        (is (= a b) (str "order mattered for seed " seed))
        (is (is*/canonical? b))
        (is (= [[0 39]] b)))))

  (testing "canonical after sparse, randomised insertion"
    (doseq [seed (range 40)]
      (let [[_ picks] (rng/sample (rng/make-rng seed) 25 (range 200))
            s (is*/add-all is*/empty picks)]
        (is (is*/canonical? s) (str "non-canonical for seed " seed))
        (is (= (set picks)
               (set (filter #(is*/contains? s %) (range 200))))
            "membership disagreed with what was inserted")
        (is (= (count (set picks)) (is*/cardinality s)))))))

(deftest missing-is-the-gap-query
  (testing "nothing seen means everything is missing"
    (is (= [[1 10]] (is*/missing is*/empty 1 10))))

  (testing "everything seen means nothing is missing"
    (is (= [] (is*/missing (is*/add-all is*/empty (range 1 11)) 1 10))))

  (testing "interior gaps are reported exactly"
    (let [s (is*/add-all is*/empty [1 2 3 7 8 12])]
      (is (= [[4 6] [9 11]] (is*/missing s 1 12)))))

  (testing "gaps at the edges of the window"
    (let [s (is*/add-all is*/empty [5 6 7])]
      (is (= [[1 4] [8 10]] (is*/missing s 1 10)))
      (is (= [[1 4]] (is*/missing s 1 7)))
      (is (= [[8 10]] (is*/missing s 5 10)))))

  (testing "a window outside anything seen"
    (let [s (is*/add-all is*/empty [1 2 3])]
      (is (= [[10 20]] (is*/missing s 10 20)))))

  (testing "an inverted window is empty, not an error"
    (is (= [] (is*/missing is*/empty 10 1))))

  (testing "missing agrees with membership, over random sets"
    ;; The invariant tying the two uses of the structure together: a number is
    ;; in a reported gap exactly when it is not a member. If these could
    ;; disagree, repair would either loop forever or silently skip messages.
    (doseq [seed (range 30)]
      (let [[_ picks] (rng/sample (rng/make-rng seed) 15 (range 60))
            s (is*/add-all is*/empty picks)
            gaps (is*/missing s 0 59)
            in-gap? (fn [n] (some (fn [[lo hi]] (and (<= lo n) (<= n hi))) gaps))]
        (doseq [n (range 60)]
          (is (= (boolean (in-gap? n)) (not (is*/contains? s n)))
              (str "gap/membership disagreed at " n " for seed " seed)))))))

(deftest bounds-and-size
  (testing "minimum and maximum"
    (let [s (is*/add-all is*/empty [5 9 1])]
      (is (= 1 (is*/minimum s)))
      (is (= 9 (is*/maximum s)))))

  (testing "cardinality counts integers, count-ranges counts memory"
    (let [s (is*/add-all is*/empty (concat (range 1 101) [500]))]
      (is (= 101 (is*/cardinality s)))
      (is (= 2 (is*/count-ranges s)))))

  (testing "a fully received stream costs exactly one range"
    ;; This is the claim that lets us drop the TTL: memory is bounded by the
    ;; number of *gaps*, not by the number of messages or by a time window.
    (let [s (is*/add-all is*/empty (range 10000))]
      (is (= 1 (is*/count-ranges s)))
      (is (= 10000 (is*/cardinality s))))))

(deftest merging-sets
  (testing "union"
    (let [a (is*/add-all is*/empty [1 2 3])
          b (is*/add-all is*/empty [3 4 5])]
      (is (= [[1 5]] (is*/merge-sets a b)))))

  (testing "union with the empty set"
    (let [a (is*/add-all is*/empty [1 2 3])]
      (is (= a (is*/merge-sets a is*/empty)))
      (is (= a (is*/merge-sets is*/empty a))))))
