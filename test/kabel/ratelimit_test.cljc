(ns kabel.ratelimit-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.ratelimit :as rl]))

(defn- send-n
  "Push `n` messages from `conn` at `now`, returning [state verdicts]."
  [state conn now n]
  (reduce (fn [[s vs] _]
            (let [[s' v] (rl/check s conn now)]
              [s' (conj vs v)]))
          [state []]
          (range n)))

(deftest degrades-before-it-refuses
  ;; Synapse's rc_federation shape: slow, then queue, then reject. A plain
  ;; threshold cannot tell a busy peer from a hostile one and punishes both.
  (let [opts {:rate 10 :window-ms 1000 :buckets 10
              :burst-factor 2.0 :reject-factor 5.0}
        [_ verdicts] (send-n (rl/make-state opts) :peer 0 60)]
    (testing "under the sustained rate, accepted"
      (is (every? #{:accept} (take 10 verdicts))))

    (testing "over it but within burst, slowed rather than dropped"
      (is (= :slow (nth verdicts 12))))

    (testing "past burst, queued"
      (is (= :queue (nth verdicts 25))))

    (testing "past the ceiling, rejected"
      (is (= :reject (nth verdicts 55))))

    (testing "and the progression never goes backwards"
      (let [ranks {:accept 0 :slow 1 :queue 2 :reject 3}]
        (is (apply <= (map ranks verdicts)))))))

(deftest connections-are-metered-independently
  (testing "one noisy peer does not throttle a quiet one"
    (let [opts {:rate 5 :window-ms 1000 :buckets 10 :reject-factor 5.0}
          [s _] (send-n (rl/make-state opts) :noisy 0 100)
          [s' v] (rl/check s :quiet 0)]
      (is (= :accept v))
      (is (= 1 (rl/rate-for s' :quiet 0)))
      (is (= 100 (rl/rate-for s' :noisy 0))))))

(deftest the-window-slides
  (testing "a fixed window would let double the rate through at a boundary"
    ;; The classic failure: 10 at the end of one window and 10 at the start of
    ;; the next is 20 in a 1000 ms span, which a fixed window happily allows.
    (let [opts {:rate 10 :window-ms 1000 :buckets 10 :burst-factor 2.0}
          [s _] (send-n (rl/make-state opts) :p 900 10)
          [_ v] (send-n s :p 1000 10)]
      (is (some #{:slow :queue} v)
          "the boundary let a full second rate through twice")))

  (testing "and budget genuinely returns once the window passes"
    (let [opts {:rate 10 :window-ms 1000 :buckets 10}
          [s _] (send-n (rl/make-state opts) :p 0 15)
          [_ v] (rl/check s :p 5000)]
      (is (= :accept v))))

  (testing "old sub-windows are dropped rather than accumulated"
    (let [opts {:rate 10 :window-ms 1000 :buckets 10}
          [s _] (reduce (fn [[st _] t] (send-n st :p t 5))
                        [(rl/make-state opts) nil]
                        (range 0 20000 1000))]
      (is (< (count (get-in s [:conns :p :buckets])) 12)
          "sub-window counts grew without bound"))))

(deftest rejected-messages-still-count
  (testing "being refused does not refund the budget"
    ;; Otherwise a peer that keeps sending after a rejection gets its
    ;; allowance back by ignoring the answer.
    (let [opts {:rate 2 :window-ms 1000 :buckets 10 :reject-factor 2.0}
          [s _] (send-n (rl/make-state opts) :p 0 50)
          [s' v] (rl/check s :p 0)]
      (is (= :reject v))
      (is (= 51 (rl/rate-for s' :p 0))
          "a rejected message was refunded"))))

(deftest connection-tracking-is-bounded
  (testing "a flood of new connections cannot grow the table without limit"
    (let [s (reduce (fn [st i] (first (rl/check st (keyword (str "c" i)) i)))
                    (rl/make-state {:max-connections 16})
                    (range 500))]
      (is (<= (count (:conns s)) 17))
      (is (pos? (get-in s [:stats :evicted])))))

  (testing "eviction takes the stalest, so an active peer is never displaced"
    ;; The failure to avoid: a flood of new connections evicting the peers
    ;; actually doing work.
    (let [s (-> (rl/make-state {:max-connections 2})
                (rl/check :active 0) first
                (rl/check :active 1000) first
                (rl/check :stale 1) first
                (rl/check :newcomer 2000) first)]
      (is (contains? (:conns s) :active))
      (is (contains? (:conns s) :newcomer))
      (is (not (contains? (:conns s) :stale))))))

(deftest forgetting-a-closed-connection
  (let [[s _] (send-n (rl/make-state) :p 0 5)]
    (is (= 5 (rl/rate-for s :p 0)))
    (is (= 0 (rl/rate-for (rl/forget s :p) :p 0)))))
