(ns kabel.overlay.runtime-test
  "Unit tests for the overlay runtime's action interpreter and event loop.

  Effects are injected, so these run without opening a socket. The real-wire
  behaviour is covered by `kabel.overlay.integration-test`."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.overlay.runtime :as rt]
            #?@(:clj [[kabel.dissemination :as d]
                      [kabel.identity :as id]
                      [superv.async :refer [S <??]]
                      [clojure.core.async :refer [<!!]]])))

(defn- recording-effects
  "Effects that record instead of acting."
  [log]
  {:send! (fn [to m] (swap! log conj [:send to m]))
   :connect! (fn [to address m] (swap! log conj [:connect to address m]))
   :disconnect! (fn [to] (swap! log conj [:disconnect to]))
   :schedule! (fn [delay payload] (swap! log conj [:timer delay payload]))})

(defn- runtime-with
  "A runtime whose handler emits `actions` for any event."
  [actions log]
  (rt/make-runtime {:id :me
                    :state {:seen []}
                    :handler (fn [state event _ctx]
                               {:state (update state :seen conj (:type event))
                                :actions actions})
                    :effects (recording-effects log)
                    :now-fn (constantly 1000)}))

(deftest actions-become-effects
  (testing ":send is wrapped in an overlay frame"
    ;; The wrapping is what lets overlay traffic share a wire with everything
    ;; else a peer is doing; an unwrapped payload would be indistinguishable
    ;; from an application message.
    (let [log (atom [])
          ctx (runtime-with [[:send :p1 {:type :dial}]] log)]
      (rt/step! ctx {:type :init})
      (is (= [[:send :p1 {:type rt/frame-type :payload {:type :dial}}]] @log))))

  (testing ":connect carries the address and the first frame"
    (let [log (atom [])
          ctx (runtime-with [[:connect :p1 "ws://p1" {:type :dial}]] log)]
      (rt/step! ctx {:type :init})
      (is (= [[:connect :p1 "ws://p1"
               {:type rt/frame-type :payload {:type :dial}}]]
             @log))))

  (testing ":timer and :disconnect pass straight through"
    (let [log (atom [])
          ctx (runtime-with [[:timer 500 :tick] [:disconnect :p2]] log)]
      (rt/step! ctx {:type :init})
      (is (= [[:timer 500 :tick] [:disconnect :p2]] @log))))

  (testing "an unknown action is refused rather than ignored"
    ;; A typo in an action name must not be a silent no-op — that is how a
    ;; defence ends up declared and inert.
    (let [log (atom [])
          ctx (runtime-with [[:sned :p1 "oops"]] log)]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (rt/step! ctx {:type :init}))))))

(deftest state-advances-per-event
  (testing "the machine's returned state is retained between events"
    (let [log (atom [])
          ctx (runtime-with [] log)]
      (rt/step! ctx {:type :init})
      (rt/step! ctx {:type :timer :payload :tick})
      (is (= [:init :timer] (:seen @(:state ctx))))))

  (testing "a handler returning no state leaves the old state in place"
    (let [ctx (rt/make-runtime {:id :me
                                :state {:kept true}
                                :handler (fn [_ _ _] {:state nil :actions []})
                                :effects (recording-effects (atom []))})]
      (rt/step! ctx {:type :init})
      (is (= {:kept true} @(:state ctx))))))

#?(:clj
   (deftest publisher-authentication
     (let [kp (<!! (id/generate-identity))
           peer-id (id/peer-id (:genesis kp))
           msg {:type :gossip :topic :db/roots :origin peer-id
                :epoch 7 :seq 3 :hops 0 :payload {:root "abc"}}
           signed (<!! (rt/sign-gossip S kp msg))]

       (testing "a genuine publish verifies"
         (is (d/signed? signed))
         (is (true? (<!! (rt/verify-gossip S signed)))))

       (testing "an unsigned publish does not"
         (is (false? (<!! (rt/verify-gossip S msg)))))

       (testing "altering the payload breaks it"
         ;; The point of the whole exercise for db roots: a relay must not be
         ;; able to substitute a different root.
         (is (false? (<!! (rt/verify-gossip S (assoc signed :payload {:root "evil"}))))))

       (testing "altering the topic breaks it"
         (is (false? (<!! (rt/verify-gossip S (assoc signed :topic :other))))))

       (testing "replaying it at a different sequence number breaks it"
         ;; Without `seq` in the signed bytes, a relay could replay an old
         ;; publish as a newer one — which for a database root is a rollback.
         (is (false? (<!! (rt/verify-gossip S (assoc signed :seq 99)))))
         (is (false? (<!! (rt/verify-gossip S (assoc signed :epoch 1))))))

       (testing "re-attributing it to another origin breaks it"
         (let [other (<!! (id/generate-identity))]
           (is (false? (<!! (rt/verify-gossip
                             S (assoc signed :origin (id/peer-id (:genesis other)))))))))

       (testing "a valid signature under a key that does not derive the origin is refused"
         ;; Both halves are required. A signature that checks out over a
         ;; mismatched origin would let anyone publish under another's name.
         (let [other (<!! (id/generate-identity))
               forged (assoc msg :origin (id/peer-id (:genesis other)))
               forged (<!! (rt/sign-gossip S other forged))]
           (is (true? (<!! (rt/verify-gossip S forged))) "sanity: self-consistent")
           (is (false? (<!! (rt/verify-gossip
                             S (assoc forged :origin-key (id/bytes->hex (:public (:operational kp))))))))))

       (testing "garbage credentials are refused, not thrown"
         (is (false? (<!! (rt/verify-gossip S (assoc signed :origin-sig "zz")))))
         (is (false? (<!! (rt/verify-gossip S (assoc signed :origin-key "00")))))
         (is (false? (<!! (rt/verify-gossip S (assoc signed :origin-key 42)))))))))

(deftest frames-are-recognisable
  (testing "overlay frames are distinguishable from application messages"
    (is (rt/overlay-message? (rt/frame {:type :dial})))
    (is (rt/overlay-message? {:type rt/hello-type :record {}}))
    (is (not (rt/overlay-message? {:type :app/thing})))
    (is (not (rt/overlay-message? "a string")))
    (is (not (rt/overlay-message? nil)))))

(deftest overload-refuses-rather-than-throws
  (testing "submit! reports a full queue instead of queueing until it breaks"
    ;; `put!` on a full channel queues, and core.async throws past 1024 pending
    ;; puts — so overload used to arrive as an exception rather than a decision.
    ;; replikativ dropped the connection in exactly this case, which is the
    ;; right answer to a peer sending faster than we can process.
    (let [ctx (rt/make-runtime {:id :me :state {}
                                :handler (fn [s _ _] {:state s :actions []})
                                :effects {:send! (fn [_ _]) :connect! (fn [_ _ _])
                                          :disconnect! (fn [_]) :schedule! (fn [_ _])}})
          ;; nothing is draining :events, so it fills at its buffer
          accepted (count (take-while true?
                                      (repeatedly 5000 #(rt/submit! ctx {:type :init}))))]
      (is (< accepted 5000) "the queue accepted everything, so it is unbounded")
      (is (pos? accepted))
      (testing "and keeps refusing rather than throwing"
        (is (false? (rt/submit! ctx {:type :init})))))))
