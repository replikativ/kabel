(ns kabel.store.konserve-test
  "Tests for the konserve-backed peer store.

  Runs only under the `:konserve` alias — kabel's base library has no storage
  dependency, so these are not part of the default suite."
  (:require [clojure.test :refer [deftest testing is]]
            [hasch.core :refer [uuid]]
            [kabel.content :as content]
            [kabel.identity :as id]
            [kabel.overlay.runtime :as rt]
            [kabel.peer :as kpeer]
            [kabel.store.konserve :as ks]
            [kabel.store.protocol :as p]
            [konserve.memory :refer [new-mem-store]]
            [superv.async :refer [S <??]]
            [clojure.core.async :refer [<!! timeout]]))

(defn- wait-for [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (<?? S (timeout 25)) (recur))))))

(defn- fresh []
  (ks/new-konserve-store (<!! (new-mem-store))))

(deftest satisfies-the-peer-store-contract
  (let [s (fresh)]
    (testing "a missing key yields nil"
      (is (nil? (<!! (p/-load s :nothing)))))

    (testing "store and load round-trip"
      (is (= {:a 1} (<!! (p/-store! s :book {:a 1}))))
      (is (= {:a 1} (<!! (p/-load s :book)))))

    (testing "overwrite"
      (<!! (p/-store! s :book {:a 2}))
      (is (= {:a 2} (<!! (p/-load s :book)))))

    (testing "keys reflect what is stored"
      (<!! (p/-store! s :epoch 7))
      (is (= #{:book :epoch} (<!! (p/-keys* s)))))

    (testing "durable state survives a fresh wrapper over the same store"
      ;; The point of the whole exercise: a peer that restarts is the same
      ;; peer. Wrapping the same konserve store again must see the same state.
      (let [underlying (:store s)
            reopened (ks/new-konserve-store underlying)]
        (is (= {:a 2} (<!! (p/-load reopened :book))))
        (is (= 7 (<!! (p/-load reopened :epoch))))))))

(deftest prefix-isolates-the-overlay
  (testing "overlay state does not collide with application data"
    (let [underlying (<!! (new-mem-store))
          a (ks/new-konserve-store underlying {:prefix :peer-a})
          b (ks/new-konserve-store underlying {:prefix :peer-b})]
      (<!! (p/-store! a :epoch 1))
      (<!! (p/-store! b :epoch 2))
      (is (= 1 (<!! (p/-load a :epoch))))
      (is (= 2 (<!! (p/-load b :epoch)))
          "two prefixes in one store overwrote each other"))))

(deftest identity-survives-a-restart
  (testing "an identity written once is read back, so the peer keeps its name"
    (let [underlying (<!! (new-mem-store))
          kp {:public "deadbeef" :private "cafebabe"}]
      (<!! (p/-store! (ks/new-konserve-store underlying) :identity kp))
      (is (= kp (<!! (p/-load (ks/new-konserve-store underlying) :identity)))))))

;; =============================================================================
;; Content durability, end to end
;; =============================================================================

(deftest verified-content-survives-a-restart
  (testing "a peer that restarts is a provider again after warming"
    ;; Identity and the address book already survive a restart; the content
    ;; working set did not — so a restarted peer silently stopped providing
    ;; everything it still held on disk.
    (let [store (fresh)
          v {:index-node 42}
          k (uuid v)
          kp (<?? S (id/generate-identity))
          peer-id (id/peer-id (:genesis kp))
          make-running (fn []
                         (let [[mw _] (rt/deferred-middleware)
                               peer (kpeer/client-peer S peer-id mw identity)]
                           (<?? S (rt/start! S peer
                                             {:identity kp :addresses [] :topics #{:t}
                                              :store store}))))
          first-run (make-running)]

      (testing "persisting a verified value writes through to konserve"
        ((get-in first-run [:ctx :effects :persist!]) k v)
        (is (wait-for 3000 #(some? (<!! (p/-load store [:blocks k]))))
            "the value never reached durable storage"))

      (testing "a fresh runtime starts with an empty working set"
        (let [second-run (make-running)]
          (is (not (content/have? (:content (rt/overlay-state second-run)) k))
              "the working set was not empty, so warming proves nothing")

          (testing "and warming makes it servable again"
            (is (= 1 (<?? S (rt/warm! S second-run store [k]))))
            (is (wait-for 3000
                          #(content/servable? (:content (rt/overlay-state second-run)) k))
                "warming did not restore servability")))))))
