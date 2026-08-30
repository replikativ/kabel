(ns kabel.pubsub.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [kabel.pubsub.lifecycle :as lifecycle]))

(defn- step
  [state event]
  (lifecycle/transition state event))

(defn- execute-effects
  [state effects applied]
  (loop [state state
         effects effects
         applied applied]
    (if-let [{:keys [op items values value]} (first effects)]
      (case op
        :apply-snapshot-batch
        (let [{next-state :state next-effects :effects}
              (step state {:type :snapshot/batch-result :ok true})]
          (recur next-state
                 (into (vec (rest effects)) next-effects)
                 (into applied (map #(vector :snapshot %) items))))

        :apply-captured-live
        (let [{next-state :state next-effects :effects}
              (step state {:type :snapshot/drain-result :ok true})]
          (recur next-state
                 (into (vec (rest effects)) next-effects)
                 (into applied (map #(vector :live %) values))))

        :apply-live
        (let [{next-state :state next-effects :effects}
              (step state {:type :live/result :ok true})]
          (recur next-state
                 (into (vec (rest effects)) next-effects)
                 (conj applied [:live value])))

        (recur state (rest effects) applied))
      {:state state :applied applied})))

(defn- run-events
  [events]
  (reduce (fn [{:keys [state applied]} event]
            (let [{next-state :state effects :effects} (step state event)]
              (execute-effects next-state effects applied)))
          {:state (lifecycle/initial-state :topic) :applied []}
          events))

(deftest snapshot-live-barrier-test
  (let [s0 (lifecycle/initial-state :topic)
        {s1 :state} (step s0 {:type :snapshot/item :value :old :bytes 3})
        {s2 :state batch-effects :effects}
        (step s1 {:type :snapshot/batch :index 0 :count 1})
        {s3 :state ack-effects :effects}
        (step s2 {:type :snapshot/batch-result :ok true})
        {s4 :state} (step s3 {:type :live/publication :value :new :bytes 3})
        {s5 :state drain-effects :effects} (step s4 {:type :snapshot/complete})
        {s6 :state ready-effects :effects}
        (step s5 {:type :snapshot/drain-result :ok true})]
    (is (= [{:op :apply-snapshot-batch
             :topic :topic
             :index 0
             :items [:old]}]
           batch-effects))
    (is (= [{:op :ack-snapshot-batch :topic :topic :index 0}] ack-effects))
    (is (= [{:op :apply-captured-live :topic :topic :values [:new]}]
           drain-effects))
    (is (= [{:op :settle-ready :ok true :topic :topic}] ready-effects))
    (is (= :live (:phase s6)))))

(deftest every-publication-placement-is-covered-test
  (let [snapshot-item {:type :snapshot/item :value :old :bytes 1}
        batch {:type :snapshot/batch :index 0 :count 1}
        live {:type :live/publication :value :new :bytes 1}
        complete {:type :snapshot/complete}
        schedules [[live snapshot-item batch complete]
                   [snapshot-item live batch complete]
                   [snapshot-item batch live complete]
                   [snapshot-item batch complete live]]]
    (doseq [schedule schedules]
      (let [{:keys [state applied]} (run-events schedule)]
        (is (= :live (:phase state)) (pr-str schedule))
        (is (= [[:snapshot :old] [:live :new]] applied)
            (pr-str schedule))))))

(deftest exact-batch-validation-test
  (let [{state :state}
        (step (lifecycle/initial-state :topic)
              {:type :snapshot/item :value :only :bytes 1})]
    (doseq [[event code]
            [[{:type :snapshot/batch :index 1 :count 1} :unexpected-batch]
             [{:type :snapshot/batch :index 0 :count 2} :incomplete-batch]]]
      (let [{failed :state effects :effects} (step state event)]
        (is (= :failed (:phase failed)))
        (is (= code (get-in failed [:failure :code])))
        (is (= :fail (-> effects first :op)))))))

(deftest explicit-success-is-required-test
  (let [{applying :state}
        (-> (lifecycle/initial-state :topic)
            (step {:type :snapshot/item :value :v :bytes 1})
            :state
            (step {:type :snapshot/batch :index 0 :count 1}))]
    (doseq [result [{:type :snapshot/batch-result :error :boom}
                    {:type :snapshot/batch-result :ok false}
                    {:type :snapshot/batch-result}]]
      (let [{state :state effects :effects} (step applying result)]
        (is (= :failed (:phase state)))
        (is (not-any? #(= :ack-snapshot-batch (:op %)) effects))
        (is (= #{:fail :settle-ready :settle-closed}
               (set (map :op effects)))))))
  (let [{draining :state}
        (step (lifecycle/initial-state :topic) {:type :snapshot/complete})
        {state :state effects :effects}
        (step draining {:type :snapshot/drain-result :error :boom})]
    (is (= :failed (:phase state)))
    (is (not-any? #(and (= :settle-ready (:op %)) (:ok %)) effects))))

(deftest overflow-fails-without-ready-test
  (doseq [[limits publications code]
          [[{:max-pending-publishes 1}
            [{:type :live/publication :value :a :bytes 1}
             {:type :live/publication :value :b :bytes 1}]
            :pending-item-overflow]
           [{:max-pending-bytes 1}
            [{:type :live/publication :value :a :bytes 2}]
            :pending-byte-overflow]]]
    (let [result (reduce (fn [{:keys [state]} event]
                           (step state event))
                         {:state (lifecycle/initial-state :topic limits)}
                         publications)]
      (is (= :failed (get-in result [:state :phase])))
      (is (= code (get-in result [:state :failure :code])))
      (is (not-any? #(and (= :settle-ready (:op %)) (:ok %))
                    (:effects result))))))

(deftest completion-with-uncommitted-items-fails-test
  (let [{state :state}
        (step (lifecycle/initial-state :topic)
              {:type :snapshot/item :value :prefix :bytes 1})
        {failed :state effects :effects}
        (step state {:type :snapshot/complete})]
    (is (= :failed (:phase failed)))
    (is (= :uncommitted-snapshot-items (get-in failed [:failure :code])))
    (is (not-any? #(and (= :settle-ready (:op %)) (:ok %)) effects))))

(deftest mandatory-byte-bounds-test
  (doseq [event [{:type :snapshot/item :value :v}
                 {:type :snapshot/item :value :v :bytes -1}
                 {:type :live/publication :value :v}
                 {:type :live/publication :value :v :bytes 1.5}]]
    (let [{state :state} (step (lifecycle/initial-state :topic) event)]
      (is (= :failed (:phase state)) (pr-str event))
      (is (= :invalid-byte-count (get-in state [:failure :code])))))

  (let [{draining :state}
        (step (lifecycle/initial-state :topic) {:type :snapshot/complete})
        {live :state}
        (step draining {:type :snapshot/drain-result :ok true})]
    (doseq [event [{:type :live/publication :value :v}
                   {:type :live/publication :value :v :bytes -1}
                   {:type :live/publication :value :v :bytes 1.5}]]
      (let [{state :state} (step live event)]
        (is (= :failed (:phase state)) (pr-str event))
        (is (= :invalid-byte-count (get-in state [:failure :code]))))))

  (doseq [[limits events code]
          [[{:max-batch-items 1}
            [{:type :snapshot/item :value :a :bytes 1}
             {:type :snapshot/item :value :b :bytes 1}]
            :batch-item-overflow]
           [{:max-batch-bytes 1}
            [{:type :snapshot/item :value :a :bytes 2}]
            :batch-byte-overflow]]]
    (let [result (reduce (fn [{:keys [state]} event] (step state event))
                         {:state (lifecycle/initial-state :topic limits)}
                         events)]
      (is (= :failed (get-in result [:state :phase])))
      (is (= code (get-in result [:state :failure :code]))))))

(deftest empty-batch-is-rejected-test
  (let [{state :state effects :effects}
        (step (lifecycle/initial-state :topic)
              {:type :snapshot/batch :index 0 :count 0})]
    (is (= :failed (:phase state)))
    (is (= :empty-batch (get-in state [:failure :code])))
    (is (not-any? #(= :ack-snapshot-batch (:op %)) effects))))

(deftest terminal-paths-settle-exactly-once-test
  (testing "failure settles both waiters and releases payloads"
    (let [{with-payload :state}
          (step (lifecycle/initial-state :topic)
                {:type :live/publication :value :large :bytes 100})
          {failed :state effects :effects}
          (step with-payload {:type :abort :code :test-abort})
          late-close (step failed {:type :close})]
      (is (= #{:fail :settle-ready :settle-closed}
             (set (map :op effects))))
      (is (empty? (:pending failed)))
      (is (empty? (:effects late-close)))))

  (testing "close before ready fails ready and succeeds closed"
    (let [{state :state effects :effects}
          (step (lifecycle/initial-state :topic) {:type :close})]
      (is (= :closed (:phase state)))
      (is (= [{:op :settle-ready
               :error {:code :closed-before-ready :topic :topic}}
              {:op :settle-closed :ok true}]
             effects))))

  (testing "close after ready settles only closed"
    (let [{draining :state}
          (step (lifecycle/initial-state :topic) {:type :snapshot/complete})
          {live :state}
          (step draining {:type :snapshot/drain-result :ok true})
          {closed :state effects :effects} (step live {:type :close})]
      (is (= :closed (:phase closed)))
      (is (= [{:op :settle-closed :ok true}] effects)))))

(deftest live-apply-failure-settles-session-test
  (let [{draining :state}
        (step (lifecycle/initial-state :topic) {:type :snapshot/complete})
        {live :state}
        (step draining {:type :snapshot/drain-result :ok true})
        {applying :state}
        (step live {:type :live/publication :value :v :bytes 1})
        {failed :state effects :effects}
        (step applying {:type :live/result :error :boom})]
    (is (= :failed (:phase failed)))
    (is (= :live-apply-failed (get-in failed [:failure :code])))
    (is (= :succeeded (:ready-status failed)))
    (is (= #{:fail :settle-closed} (set (map :op effects))))))

(deftest producer-requires-close-and-explicit-success-test
  (doseq [events [[{:type :producer/source-closed}
                   {:type :producer/result :ok true}
                   {:type :producer/sender-drained}]
                  [{:type :producer/result :ok true}
                   {:type :producer/sender-drained}
                   {:type :producer/source-closed}]
                  [{:type :producer/sender-drained}
                   {:type :producer/source-closed}
                   {:type :producer/result :ok true}]]]
    (let [result (reduce (fn [{:keys [state]} event]
                           (lifecycle/producer-transition state event))
                         {:state (lifecycle/initial-producer-state :topic)}
                         events)]
      (is (= :complete (get-in result [:state :phase])))
      (is (= [{:op :send-snapshot-complete :topic :topic}]
             (:effects result)))))

  (testing "source close and success do not complete before batch ACK drain"
    (let [{:keys [state effects]}
          (reduce (fn [{:keys [state]} event]
                    (lifecycle/producer-transition state event))
                  {:state (lifecycle/initial-producer-state :topic)}
                  [{:type :producer/source-closed}
                   {:type :producer/result :ok true}])]
      (is (= :producing (:phase state)))
      (is (empty? effects))))

  (doseq [result [{:type :producer/result :error :boom}
                  {:type :producer/result :ok false}
                  {:type :producer/result}]]
    (let [{state :state effects :effects}
          (lifecycle/producer-transition
           (lifecycle/initial-producer-state :topic)
           result)]
      (is (= :failed (:phase state)))
      (is (= :fail-producer (-> effects first :op)))
      (is (not-any? #(= :send-snapshot-complete (:op %)) effects)))))
