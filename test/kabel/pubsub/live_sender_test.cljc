(ns kabel.pubsub.live-sender-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop #?@(:cljs [:include-macros true])]
            [kabel.pubsub.live-sender :as ls]))

(defn- sender
  ([] (sender {}))
  ([opts]
   (ls/initial-state (merge {:topic :t :epoch :e :mode :windowed
                             :grant {:frames 4 :bytes 100} :phase :live}
                            opts))))

(defn- entry [i bytes] {:payload i :bytes bytes})

(defn- enq
  ([s e] (enq s e :resync))
  ([s e policy] (ls/enqueue s e policy (* 64 1024 1024))))

(defn- take-all
  "Step `take-next` until it waits or is done. Returns [state actions]."
  [s]
  (loop [s s actions []]
    (let [[s action] (ls/take-next s)]
      (if (#{:wait :done} (first action))
        [s (conj actions action)]
        (recur s (conj actions action))))))

;; =============================================================================
;; Randomized: window, order, progress
;; =============================================================================

(def ^:private op-gen
  (gen/frequency
   [[6 (gen/fmap (fn [b] [:enqueue b]) (gen/choose 1 60))]
    [3 (gen/return [:send])]
    [3 (gen/fmap (fn [k] [:apply k]) (gen/choose 1 5))]
    [1 (gen/fmap (fn [[f b]] [:grant {:frames f :bytes b}])
                 (gen/tuple (gen/choose 1 8) (gen/choose 20 150)))]]))

(defn- apply-frames
  "The receiver applies up to `k` delivered frames in order and acknowledges
  each one that requested it, as the lifecycle lane does."
  [{:keys [s delivered applied] :as world} k]
  (let [todo (take k (drop applied delivered))]
    (reduce (fn [world {:keys [seq ack?]}]
              (cond-> (update world :applied inc)
                ack? (update :s #(first (ls/on-ack % :e seq)))))
            world
            todo)))

(defn- send-all
  [{:keys [s] :as world}]
  (let [[s actions] (take-all s)
        frames (keep (fn [[kind x]] (when (= :publish kind) x)) actions)
        world (-> world
                  (assoc :s s :last-action (peek actions))
                  (update :delivered into frames))]
    world))

(defn- check-invariants
  [{:keys [s delivered last-action]}]
  (let [{:keys [unacked unacked-bytes grant queue]} s]
    (and
     ;; never more in flight than granted
     (<= (count unacked) (:frames grant))
     (or (<= (count unacked) 1) (<= unacked-bytes (:bytes grant)))
     ;; a waiting loop with work left waits on an ack it asked for
     (or (not= [:wait] last-action)
         (empty? queue)
         (and (seq unacked)
              (:ack? (peek delivered))
              (contains? unacked (:seq (peek delivered))))))))

(defn- run-ops
  [ops]
  (reduce (fn [{:keys [n] :as world} [op x]]
            (let [world (case op
                          :enqueue (-> world
                                       (update :s #(first (enq % (entry n x))))
                                       (update :n inc))
                          :send (send-all world)
                          :apply (apply-frames world x)
                          :grant (update world :s #(first (ls/on-grant % x))))]
              (if (check-invariants (cond-> world (not= :send op) (dissoc :last-action)))
                world
                (reduced (assoc world :violated op)))))
          {:s (sender {:limits {:max-queued-publishes 10000
                                :max-queued-bytes 10000000}})
           :n 0 :delivered [] :applied 0}
          ops))

(defn- drain-to-end
  "Send and apply until everything is delivered. Fails on a round that makes
  no progress: the sender would wait forever."
  [world]
  (loop [world world rounds 0]
    (let [before [(count (:delivered world)) (:applied world)]
          world (-> world send-all (apply-frames 1000000) (dissoc :last-action))]
      (cond
        (and (empty? (:queue (:s world))) (= (:applied world) (count (:delivered world))))
        world

        (or (= before [(count (:delivered world)) (:applied world)])
            (> rounds 100000))
        (assoc world :stalled true)

        :else (recur world (inc rounds))))))

(deftest window-order-and-progress-property
  (let [result (tc/quick-check
                300
                (prop/for-all [ops (gen/vector op-gen 0 200)]
                              (let [world (run-ops ops)]
                                (and (not (:violated world))
                                     (let [end (drain-to-end world)]
                                       (and (not (:stalled end))
                                            (check-invariants end)
                                ;; FIFO, nothing lost or duplicated
                                            (= (range (:n world))
                                               (map :payload (:delivered end)))))))))]
    (is (:result result) (pr-str (:shrunk result)))))

(deftest byte-window-does-not-stall-on-a-later-large-entry
  ;; 1 MiB sent without an ack request would leave a 3.5 MiB entry waiting on
  ;; nothing: the frame before an entry that does not fit asks for an ack.
  (let [mib (* 1024 1024)
        s (sender {:grant {:frames 16 :bytes (* 4 mib)}})
        [s _] (enq s (entry 0 mib))
        [s [_ first-frame]] (ls/take-next s)
        [s _] (enq s (entry 1 (* 7 (quot mib 2))))
        [s action] (ls/take-next s)]
    (is (:ack? first-frame) "the queue was empty after it")
    (is (= [:wait] action))
    (let [[s _] (ls/on-ack s :e 0)
          [_ [kind frame]] (ls/take-next s)]
      (is (= :publish kind))
      (is (= 1 (:payload frame))))))

;; =============================================================================
;; Transitions
;; =============================================================================

(deftest control-frames-go-first-and-bypass-the-window
  (let [s (sender {:grant {:frames 1 :bytes 100} :phase :handshaking})
        [s _] (enq s (entry 0 10))
        [s _] (enq s (entry 1 10))
        [s [_ f0]] (ls/take-next s)
        [s outcome] (ls/finish s)
        [s a1] (ls/take-next s)
        [s a2] (ls/take-next s)]
    (is (= 0 (:payload f0)))
    (is (= :live outcome))
    (is (= [:control :handshake-complete] a1) "ahead of the queued publish, window full")
    (is (= [:wait] a2))
    (is (= :live (:phase s)))))

(deftest overflow-while-live-resyncs-and-closes
  (let [s (sender {:limits {:max-queued-publishes 2}})
        s (first (enq s (entry 0 1)))
        s (first (enq s (entry 1 1)))
        [s decision] (enq s (entry 2 1))]
    (is (= :resync decision))
    (is (= :closing (:phase s)))
    (is (empty? (:queue s)) "the backlog is dropped")
    (is (= [:dropped] (rest (enq s (entry 3 1)))) "nothing is queued after it")
    (let [[s actions] (take-all s)]
      (is (= [[:control :resync] [:done]] actions))
      (is (= :closed (:phase s))))))

(deftest overflow-during-handshake-defers-resync-to-finish
  (let [s (sender {:phase :handshaking :limits {:max-queued-publishes 1}})
        s (first (enq s (entry 0 1)))
        [s decision] (enq s (entry 1 1))]
    (is (= :resync-deferred decision))
    (is (:overflowed? s))
    (is (= :dropped (second (enq s (entry 2 1)))))
    (let [[s outcome] (ls/finish s)
          [_ actions] (take-all s)]
      (is (= :resync outcome))
      (is (= [[:control :resync] [:done]] actions)
          "resync instead of handshake-complete"))))

(deftest a-resync-after-finish-follows-handshake-complete
  (let [s (sender {:phase :handshaking :limits {:max-queued-publishes 1}})
        [s _] (ls/finish s)
        s (first (enq s (entry 0 1)))
        [s decision] (enq s (entry 1 1))]
    (is (= :resync decision))
    (is (= [[:control :handshake-complete] [:control :resync] [:done]]
           (second (take-all s))))))

(deftest fail-policy-and-legacy-senders-close
  (is (= :fail (second (enq (sender {:limits {:max-queued-publishes 0}})
                            (entry 0 1) :fail))))
  (let [[s decision] (enq (sender {:mode :legacy :limits {:max-queued-publishes 0}})
                          (entry 0 1) :resync)]
    (is (= :fail decision) "a peer without windows cannot be told to resync")
    (is (= :closed (:phase s)))))

(deftest connection-cap-applies-the-policy
  (is (= :resync (second (ls/enqueue (sender) (entry 0 10) :resync 9)))))

(deftest pending-grant-queues-then-upgrades-or-falls-back
  (let [s (sender {:mode :pending-grant :grant nil :limits {:max-queued-publishes 2}})
        s (first (enq s (entry 0 1)))]
    (is (= [:wait] (second (ls/take-next s))) "nothing goes out before the decision")
    (testing "a grant upgrades it"
      (let [[s _] (ls/on-grant s {:frames 4 :bytes 100})
            [_ [kind frame]] (ls/take-next s)]
        (is (= :publish kind))
        (is (= 0 (:seq frame)))))
    (testing "a legacy decision sends unpaced"
      (let [[s _] (ls/on-legacy s)
            [_ [kind frame]] (ls/take-next s)]
        (is (= :publish kind))
        (is (nil? (:seq frame)))))
    (testing "overflow before the decision falls back to unpaced, not failure"
      (let [s (first (enq s (entry 1 1)))
            [s decision] (enq s (entry 2 1))]
        (is (= :queued decision))
        (is (= :legacy (:mode s)))))))

(deftest acks-out-of-range-or-stale-are-ignored
  (let [s (sender)
        s (first (enq s (entry 0 1)))
        [s _] (ls/take-next s)]
    (is (= s (first (ls/on-ack s :other-epoch 0))))
    (is (= s (first (ls/on-ack s :e 5))) "beyond what was sent")
    (let [[s n] (ls/on-ack s :e 0)]
      (is (= 1 n))
      (is (= s (first (ls/on-ack s :e 0))) "not newer"))))

(deftest shrinking-grant-waits-for-the-window-to-empty
  (let [s (sender {:grant {:frames 4 :bytes 100}})
        s (reduce #(first (enq %1 (entry %2 1))) s (range 3))
        [s _] (take-all s)
        [s _] (ls/on-grant s {:frames 1 :bytes 100})]
    (is (= 4 (get-in s [:grant :frames])) "held while frames are unacknowledged")
    (let [[s _] (ls/on-ack s :e 2)]
      (is (= 1 (get-in s [:grant :frames]))))))

(deftest drain-sends-the-queue-then-closes
  (let [s (sender {:mode :legacy})
        s (first (enq s (entry 0 1)))
        [s _] (ls/drain s)
        [s actions] (take-all s)]
    (is (= [[:publish {:payload 0}] [:done]] actions))
    (is (= :closed (:phase s)))
    (is (= :dropped (second (enq s (entry 1 1)))))))

(deftest close-drops-everything
  (let [s (first (enq (sender) (entry 0 1)))
        [s _] (ls/finish (assoc s :phase :handshaking))
        [s _] (ls/close s)]
    (is (= [[:done]] (second (take-all s))))))

(deftest a-reservation-is-taken-over-not-counted-twice
  (let [total (atom 0)
        s (ls/create nil total {:topic :t :epoch :e :mode :windowed
                                :grant {:frames 4 :bytes 100}})]
    (testing "queued: the reservation becomes the queued bytes"
      (swap! total + 60)
      (is (= :queued (ls/swap-sender-reserved! s 60 ls/enqueue (entry 0 60) :resync 60)))
      (is (= 60 @total)))
    (testing "overflow: the reservation and the dropped queue are released"
      (swap! total + 30)
      (is (= :resync (ls/swap-sender-reserved! s 30 ls/enqueue (entry 1 30) :resync -1)))
      (is (= 0 @total)))))
