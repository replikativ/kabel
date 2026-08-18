(ns kabel.labels-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.labels :as lab]
            [kabel.topics :as tp]))

(defn- lbl [labeler subject value action]
  (lab/make-label {:labeler labeler :subject subject :value value :action action}))

(defn- with [state & labels]
  (reduce (fn [s l] (first (lab/accept s l))) state labels))

;; =============================================================================
;; The seam
;; =============================================================================

(deftest a-labeler-cannot-take-anything-down
  ;; The property copied from AT Protocol, including the part that looks like a
  ;; weakness: assertion and enforcement are different things, and only the
  ;; receiver holds the second.
  (let [state (with (lab/make-state) (lbl :spamwatch "peer-1" :spam :takedown))]

    (testing "an untrusted labeler is heard, recorded, and ignored"
      (is (= 1 (count (lab/labels-for state "peer-1" 0))))
      (is (= :none (:action (lab/verdict state "peer-1" 0))))
      (is (not (lab/hidden? state "peer-1" 0))))

    (testing "trust is what turns an assertion into an action"
      (let [trusted (lab/trust! state :spamwatch :takedown)]
        (is (= :takedown (:action (lab/verdict trusted "peer-1" 0))))
        (is (lab/hidden? trusted "peer-1" 0))))

    (testing "and trust CAPS it — :takedown from a labeler trusted to :warn is a warning"
      ;; An assertion is never stronger than the trust its author was given.
      (let [capped (lab/trust! state :spamwatch :warn)
            v (lab/verdict capped "peer-1" 0)]
        (is (= :warn (:action v)))
        (is (= :takedown (:asserted (first (:reasons v))))
            "the original assertion should still be visible")
        (is (not (lab/hidden? capped "peer-1" 0)))))

    (testing "untrusting makes existing labels inert without deleting them"
      (let [revoked (-> state (lab/trust! :spamwatch :takedown) (lab/untrust! :spamwatch))]
        (is (= :none (:action (lab/verdict revoked "peer-1" 0))))
        (is (= 1 (count (lab/labels-for revoked "peer-1" 0))))))))

(deftest the-strongest-trusted-label-wins
  (let [state (-> (lab/make-state)
                  (lab/trust! :gentle :warn)
                  (lab/trust! :strict :takedown)
                  (with (lbl :gentle "x" :rude :warn)
                        (lbl :strict "x" :abuse :takedown)))]
    (is (= :takedown (:action (lab/verdict state "x" 0))))
    (is (= 2 (count (:reasons (lab/verdict state "x" 0))))))

  (testing "an untrusted labeler cannot raise the verdict"
    (let [state (-> (lab/make-state)
                    (lab/trust! :gentle :warn)
                    (with (lbl :gentle "x" :rude :warn)
                          (lbl :loudmouth "x" :abuse :takedown)))]
      (is (= :warn (:action (lab/verdict state "x" 0)))))))

;; =============================================================================
;; Retraction and expiry
;; =============================================================================

(deftest a-labeler-can-change-its-mind
  ;; A labeler that cannot retract is one nobody should subscribe to.
  (let [state (-> (lab/make-state)
                  (lab/trust! :l :takedown)
                  (with (lbl :l "x" :spam :takedown)))]
    (is (= :takedown (:action (lab/verdict state "x" 0))))
    (let [retracted (first (lab/accept state (lab/make-label
                                              {:labeler :l :subject "x" :value :spam
                                               :action :takedown :negate? true})))]
      (is (= :none (:action (lab/verdict retracted "x" 0))))
      (is (empty? (lab/labels-for retracted "x" 0)))))

  (testing "retraction is per (labeler, value), not per subject"
    (let [state (-> (lab/make-state)
                    (lab/trust! :l :takedown)
                    (with (lbl :l "x" :spam :takedown)
                          (lbl :l "x" :nsfw :hide))
                    (as-> s (first (lab/accept s (lab/make-label
                                                  {:labeler :l :subject "x" :value :spam
                                                   :action :takedown :negate? true})))))]
      (is (= [:nsfw] (mapv :kabel.label/value (lab/labels-for state "x" 0))))
      (is (= :hide (:action (lab/verdict state "x" 0)))))))

(deftest labels-expire
  (let [state (-> (lab/make-state)
                  (lab/trust! :l :takedown)
                  (with (lab/make-label {:labeler :l :subject "x" :value :spam
                                         :action :takedown :expires-at 1000})))]
    (is (= :takedown (:action (lab/verdict state "x" 500))))
    (is (= :none (:action (lab/verdict state "x" 1500))))
    (is (empty? (lab/labels-for state "x" 1500)))))

;; =============================================================================
;; Bounds — labels are attacker-supplied
;; =============================================================================

(deftest label-storage-is-bounded
  (testing "per subject"
    (let [state (reduce (fn [s i]
                          (first (lab/accept s (lbl (keyword (str "l" i)) "x" :v :warn))))
                        (lab/make-state {:max-per-subject 5})
                        (range 100))]
      (is (= 5 (count (lab/labels-for state "x" 0))))
      (is (pos? (get-in state [:stats :refused])))))

  (testing "and overall"
    (let [state (reduce (fn [s i]
                          (first (lab/accept s (lbl :l (str "subject-" i) :v :warn))))
                        (lab/make-state {:max-subjects 8})
                        (range 200))]
      (is (<= (count (:labels state)) 9))))

  (testing "an existing (labeler, value) may always be updated at the cap"
    ;; Otherwise a labeler at the cap could never correct itself.
    (let [state (-> (lab/make-state {:max-per-subject 1})
                    (with (lbl :l "x" :spam :warn)
                          (lbl :l "x" :spam :takedown)))]
      (is (= 1 (count (lab/labels-for state "x" 0))))
      (is (= :takedown (:kabel.label/action (first (lab/labels-for state "x" 0))))))))

(deftest malformed-labels-are-refused
  (doseq [bad [{} nil "a string"
               {:kabel.label/kind "kabel/label/v1"}
               (assoc (lbl :l "x" :v :warn) :kabel.label/action :nonsense)]]
    (is (= :refused (second (lab/accept (lab/make-state) bad))))))

;; =============================================================================
;; Topics
;; =============================================================================

(deftest a-relay-can-carry-one-labeler-and-not-another
  (testing "labels ride the ordinary topic machinery"
    (is (= [:labels :spamwatch "peer-1"] (lab/topic-for :spamwatch "peer-1")))
    (is (lab/carries-labeler? #{[:labels :spamwatch]} :spamwatch))
    (is (lab/carries-labeler? #{[:labels]} :spamwatch))
    (is (lab/carries-labeler? #{tp/everything} :spamwatch))
    (is (not (lab/carries-labeler? #{[:labels :someone-else]} :spamwatch)))
    (is (not (lab/carries-labeler? #{[:db]} :spamwatch)))))
