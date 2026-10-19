(ns kabel.owned-unsubscribe-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as a]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [superv.async :refer [S]]))

(defn- owner [] (proto/pub-sub-only-strategy identity))
(defn- peer [subscriptions]
  (atom {:volatile {:supervisor S} :pubsub {:subscriptions subscriptions}}))
(defn- result [ch] (a/poll! ch))
(defn- failure-type [ch] (:type (ex-data (:error (result ch)))))

(deftest replacement-owner-is-never-removed-in-local-cleanup
  (let [old (owner) replacement (owner)
        p (peer {:t {:generation (random-uuid) :strategy old}})
        expected (:strategy (pubsub/subscription p :t))]
    ;; Replacement after inspection, before the atomic cancellation claim.
    (swap! p assoc-in [:pubsub :subscriptions :t :strategy] replacement)
    (is (= :kabel.pubsub/subscription-owner-conflict
           (failure-type (pubsub/unsubscribe! p #{:t} {:expected-strategies {:t expected}}))))
    (is (identical? replacement (:strategy (pubsub/subscription p :t))))))

(deftest mixed-topics-refuse-all-without-cancelling-the-matching-owner
  (let [mine (owner) other (owner) expected (owner)
        p (peer {:a {:strategy mine :generation (random-uuid)}
                 :b {:strategy other :generation (random-uuid)}})
        before (:subscriptions (:pubsub @p))]
    (is (= :kabel.pubsub/subscription-owner-conflict
           (failure-type (pubsub/unsubscribe! p #{:a :b} {:expected-strategies {:a mine :b expected}}))))
    (is (= before (get-in @p [:pubsub :subscriptions])))))

(deftest missing-topics-and-same-owner-new-generation-are-safe
  (let [mine (owner) p (peer {:t {:strategy mine :generation (random-uuid)}})]
    (is (= {:ok true} (result (pubsub/unsubscribe! p #{:gone} {:expected-strategies {:gone mine}}))))
    (swap! p assoc-in [:pubsub :subscriptions :t :generation] (random-uuid))
    (is (= {:ok true} (result (pubsub/unsubscribe! p #{:t} {:expected-strategies {:t mine}}))))
    (is (nil? (pubsub/subscription p :t)))))

(deftest false-and-nil-topics-still-check-ownership
  (doseq [topic [nil false]]
    (let [mine (owner) other (owner) p (peer {topic {:strategy other :generation (random-uuid)}})]
      (is (= :kabel.pubsub/subscription-owner-conflict
             (failure-type (pubsub/unsubscribe! p #{topic} {:expected-strategies {topic mine}}))))
      (is (identical? other (:strategy (pubsub/subscription p topic)))))))

(deftest malformed-guards-never-silently-become-unguarded
  (let [mine (owner) p (peer {:t {:strategy mine :generation (random-uuid)}})]
    (doseq [guard [nil false {} {:other mine} {:t nil}]]
      (is (= :kabel.pubsub/invalid-subscription-owners
             (failure-type (pubsub/unsubscribe! p #{:t} {:expected-strategies guard}))))
      (is (some? (pubsub/subscription p :t))))))

(deftest custom-transport-compatibility-is-explicit
  (let [p (peer {}) calls (atom []) mine (owner)
        done #(doto (a/chan 1) (a/put! {:ok %}) a/close!)]
    (pubsub/set-transport! p {:unsubscribe! (fn [_ topics] (swap! calls conj topics) (done :legacy))})
    (is (= {:ok :legacy} (result (pubsub/unsubscribe! p #{:t}))))
    (is (= [#{:t}] @calls))
    (is (= :kabel.pubsub/unsupported-owned-unsubscribe
           (failure-type (pubsub/unsubscribe! p #{:t} {:expected-strategies {:t mine}}))))
    (is (= [#{:t}] @calls))
    (pubsub/set-transport! p {:unsubscribe-owned! (fn [_ topics opts]
                                                    (swap! calls conj [topics opts]) (done :guarded))})
    (is (= {:ok :guarded}
           (result (pubsub/unsubscribe! p #{:t} {:expected-strategies {:t mine}}))))
    (is (= [#{:t} {:expected-strategies {:t mine}}] (last @calls)))))

#?(:clj
   (deftest managed-refusal-does-not-send-or-attach-to-another-owners-drain
     (doseq [cancelling? [false true]]
       (let [mine (owner) other (owner) out (a/chan 8) pending (atom {})
             current {:strategy other :generation (random-uuid) :out out :cancelling? cancelling?}
             p (peer {:t current})]
         (swap! p assoc-in [:pubsub :unsubscribe-state :pending] pending)
         (is (= :kabel.pubsub/subscription-owner-conflict
                (failure-type (pubsub/unsubscribe! p #{:t} {:expected-strategies {:t mine}}))))
         (is (= current (pubsub/subscription p :t)))
         (is (empty? @pending))
         (is (nil? (a/poll! out)))))))

#?(:clj
   (deftest cancellation-records-the-claimed-generation-not-a-later-replacement
     (let [mine (owner) other (owner) out (a/chan 8) pending (atom {})
           original (random-uuid) replacement (random-uuid)
           p (peer {:t {:strategy mine :generation original :out out}})
           replaced? (atom false)]
       (swap! p assoc-in [:pubsub :unsubscribe-state :pending] pending)
       (add-watch p ::replace-after-claim
                  (fn [_ _ _ state]
                    (when (and (get-in state [:pubsub :subscriptions :t :cancelling?])
                               (compare-and-set! replaced? false true))
                      (swap! p assoc-in [:pubsub :subscriptions :t]
                             {:strategy other :generation replacement :out out}))))
       (let [done (pubsub/unsubscribe! p #{:t} {:expected-strategies {:t mine}})
             frame (a/poll! out) request-id (:id frame)]
         (is (= :pubsub/unsubscribe (:type frame)))
         (is (= original (get-in @pending [request-id :generations :t])))
         (@#'pubsub/complete-unsubscribe-topic! p request-id :t)
         (is (= {:ok true} (result done)))
         (is (= replacement (:generation (pubsub/subscription p :t))))))))

#?(:clj
   (deftest default-route-is-selected-with-the-cancellation-claim
     (let [mine (owner) old-out (a/chan 8) new-out (a/chan 8)
           pending (atom {}) generation (random-uuid)
           p (peer {:t {:strategy mine :generation (random-uuid) :out old-out}})
           claim! @#'pubsub/claim-unsubscribe!]
       (swap! p assoc-in [:pubsub :unsubscribe-state] {:out old-out :pending (atom {})})
       (with-redefs-fn
         {#'pubsub/claim-unsubscribe!
          (fn [p topics expected explicit-out]
            ;; Replacement after public entry, immediately before the atomic
            ;; claim. Same owner, new physical connection and drain registry.
            (swap! p (fn [s] (-> s
                                 (assoc-in [:pubsub :subscriptions :t] {:strategy mine :generation generation :out new-out})
                                 (assoc-in [:pubsub :unsubscribe-state] {:out new-out :pending pending}))))
            (claim! p topics expected explicit-out))}
         (fn []
           (let [done (pubsub/unsubscribe! p #{:t} {:expected-strategies {:t mine}})
                 frame (a/poll! new-out)]
             (is (= :pubsub/unsubscribe (:type frame)))
             (is (nil? (a/poll! old-out)))
             (is (= generation (get-in @pending [(:id frame) :generations :t])))
             (@#'pubsub/complete-unsubscribe-topic! p (:id frame) :t)
             (is (= {:ok true} (result done)))))))))

#?(:clj
   (deftest explicit-wrong-output-refuses-before-mutation
     (let [mine (owner) out (a/chan 8) wrong (a/chan 8) pending (atom {})
           current {:strategy mine :generation (random-uuid) :out out}
           p (peer {:t current})]
       (swap! p assoc-in [:pubsub :unsubscribe-state] {:out out :pending pending})
       (is (= :kabel.pubsub/subscription-connection-conflict
              (failure-type (pubsub/direct-unsubscribe! p #{:t} {:out wrong :expected-strategies {:t mine}}))))
       (is (= current (pubsub/subscription p :t)))
       (is (empty? @pending))
       (is (nil? (a/poll! out)))
       (is (nil? (a/poll! wrong))))))

#?(:clj
   (deftest mixed-cancelling-and-fresh-topics-join-both-drains
     (let [mine (owner) out (a/chan 8) pending (atom {})
           p (peer {:a {:strategy mine :generation (random-uuid) :out out}
                    :b {:strategy mine :generation (random-uuid) :out out}})]
       (swap! p assoc-in [:pubsub :unsubscribe-state] {:out out :pending pending})
       (let [first-result (pubsub/unsubscribe! p #{:a} {:expected-strategies {:a mine}})
             first-frame (a/poll! out)
             both (pubsub/unsubscribe! p #{:a :b} {:expected-strategies {:a mine :b mine}})
             second-frame (a/poll! out)]
         (is (= #{:a} (:topics first-frame)))
         (is (= #{:b} (:topics second-frame)))
         (@#'pubsub/complete-unsubscribe-topic! p (:id second-frame) :b)
         (is (nil? (a/poll! both)) "Fresh B completing cannot stand for A's earlier drain")
         (@#'pubsub/complete-unsubscribe-topic! p (:id first-frame) :a)
         (is (= {:ok true} (result first-result)))
         (let [[value port] (a/alts!! [both (a/timeout 2000)])]
           (is (= both port))
           (is (= {:ok true} value)))))))
