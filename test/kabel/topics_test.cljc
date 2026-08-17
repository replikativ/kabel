(ns kabel.topics-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.topics :as tp]))

(deftest coverage
  (testing "the empty range covers everything"
    (is (tp/covers? [] [:db "alice" "kb1"]))
    (is (tp/covers? [] :anything)))

  (testing "a prefix covers its descendants"
    (is (tp/covers? [:db] [:db "alice" "kb1"]))
    (is (tp/covers? [:db "alice"] [:db "alice" "kb1"]))
    (is (tp/covers? [:db "alice" "kb1"] [:db "alice" "kb1"])))

  (testing "a sibling branch is not covered"
    (is (not (tp/covers? [:db "alice"] [:db "bob" "kb1"])))
    (is (not (tp/covers? [:db] [:rooms "x"]))))

  (testing "a longer range never covers a shorter topic"
    ;; Carrying a leaf says nothing about carrying its parent.
    (is (not (tp/covers? [:db "alice" "kb1"] [:db "alice"]))))

  (testing "keywords are one-element paths, not a special case"
    (is (tp/covers? [:t] :t))
    (is (tp/covers? :t :t))
    (is (not (tp/covers? :t :u)))))

(deftest normalisation
  (testing "a range subsumed by a broader one is dropped"
    ;; Otherwise two equal advertisements compare unequal, and a peer can pad
    ;; its advertised coverage with subsumed noise.
    (is (= #{[]} (tp/normalise #{[] [:db] [:db "alice"]})))
    (is (= #{[:db]} (tp/normalise #{[:db] [:db "alice"] [:db "bob" "x"]}))))

  (testing "disjoint ranges are all kept"
    (is (= #{[:db] [:rooms]} (tp/normalise #{[:db] [:rooms]}))))

  (testing "keywords normalise to paths, so equal sets compare equal"
    (is (= (tp/normalise #{:t}) (tp/normalise #{[:t]}))))

  (testing "the empty set stays empty"
    (is (= #{} (tp/normalise #{})))))

(deftest overlap-drives-peer-selection
  (testing "a peer is relevant when any range covers any wanted topic"
    (is (tp/overlaps? #{[:db "alice"]} #{[:db "alice" "kb1"]}))
    (is (tp/overlaps? #{[]} #{[:anything "at" "all"]}))
    (is (not (tp/overlaps? #{[:db "alice"]} #{[:db "bob" "kb1"]})))
    (is (not (tp/overlaps? #{} #{[:db "alice" "kb1"]})))
    (is (not (tp/overlaps? #{[:db]} #{})))))

(deftest relaying-is-not-subscribing
  (testing "a range covers, but does not subscribe"
    ;; Conflating them would hand a relay every message under a prefix it
    ;; merely agreed to forward.
    (is (tp/covered? #{[:db "alice"]} [:db "alice" "kb1"]))
    (is (not (tp/subscribes-to? #{[:db "alice"]} [:db "alice" "kb1"])))
    (is (tp/subscribes-to? #{[:db "alice" "kb1"]} [:db "alice" "kb1"]))))
