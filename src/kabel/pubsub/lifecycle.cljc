(ns kabel.pubsub.lifecycle
  "Pure receiver-side lifecycle for one Kabel pub/sub subscription.

  The transport runner owns asynchronous strategy calls. This namespace owns
  only ordering, validation, bounded hold-back, and the effects the runner must
  execute. Keeping the transition function pure makes snapshot/live schedules
  exhaustively testable without sockets or wall-clock timing.")

(def default-limits
  {:max-batch-items 1024
   :max-batch-bytes (* 4 1024 1024)
   :max-pending-publishes 1024
   :max-pending-bytes (* 4 1024 1024)})

(defn initial-state
  ([topic]
   (initial-state topic {}))
  ([topic limits]
   {:phase :joining
    :topic topic
    :next-batch 0
    :batch []
    :batch-bytes 0
    :pending []
    :pending-bytes 0
    :ready-status :pending
    :closed-status :pending
    :limits (merge default-limits limits)}))

(defn terminal?
  [state]
  (contains? #{:failed :closed} (:phase state)))

(defn- release-payloads
  [state]
  (assoc state
         :batch []
         :batch-bytes 0
         :pending []
         :pending-bytes 0))

(defn- fail
  [state code details]
  (let [failure (merge {:code code :topic (:topic state)} details)]
    {:state (-> state
                release-payloads
                (assoc :phase :failed
                       :failure failure
                       :ready-status (if (= :pending (:ready-status state))
                                       :failed
                                       (:ready-status state))
                       :closed-status (if (= :pending (:closed-status state))
                                        :failed
                                        (:closed-status state))))
     :effects (cond-> [{:op :fail :failure failure}]
                (= :pending (:ready-status state))
                (conj {:op :settle-ready :error failure})

                (= :pending (:closed-status state))
                (conj {:op :settle-closed :error failure}))}))

(defn- invalid-phase
  [state event]
  (fail state :invalid-phase
        {:phase (:phase state) :event-type (:type event)}))

(defn- non-negative-int?
  [x]
  (and (int? x) (not (neg? x))))

(defn transition
  "Advance a subscription lifecycle.

  Events:
  - `{:type :snapshot/item :value v :bytes n}`
  - `{:type :snapshot/batch :index n :count n}`
  - `{:type :snapshot/batch-result :ok true|false ...}`
  - `{:type :live/publication :value v :bytes n}`
  - `{:type :snapshot/complete}`
  - `{:type :snapshot/drain-result :ok true|false ...}`
  - `{:type :live/result :ok true|false ...}`
  - `{:type :abort ...}` or `{:type :close}`

  Returned effects are interpreted sequentially. The runner MUST feed the
  corresponding result event back before dequeuing another ordinary event for
  this lane. Abort and connection close cancel the in-flight effect through a
  separate preemption path. Only an explicit `:ok true` is success.

  Bulk effects (`:apply-snapshot-batch` and `:apply-captured-live`) mean apply
  values sequentially, stop at the first non-success result, and report success
  only after every value returned explicit success."
  [state {:keys [type bytes] :as event}]
  (if (terminal? state)
    {:state state :effects []}
    (case type
      :snapshot/item
      (if (not= :joining (:phase state))
        (invalid-phase state event)
        (let [bytes bytes
              item-count (inc (count (:batch state)))
              byte-count (when (non-negative-int? bytes)
                           (+ (:batch-bytes state) bytes))
              {:keys [max-batch-items max-batch-bytes]} (:limits state)]
          (cond
            (not (non-negative-int? bytes))
            (fail state :invalid-byte-count {:bytes bytes})

            (> item-count max-batch-items)
            (fail state :batch-item-overflow
                  {:limit max-batch-items :actual item-count})

            (> byte-count max-batch-bytes)
            (fail state :batch-byte-overflow
                  {:limit max-batch-bytes :actual byte-count})

            :else
            {:state (-> state
                        (update :batch conj (:value event))
                        (assoc :batch-bytes byte-count))
             :effects []})))

      :snapshot/batch
      (if (not= :joining (:phase state))
        (invalid-phase state event)
        (let [expected-index (:next-batch state)
              expected-count (count (:batch state))
              index (:index event)
              declared-count (:count event)]
          (cond
            (not (non-negative-int? index))
            (fail state :invalid-batch-index {:index index})

            (not (non-negative-int? declared-count))
            (fail state :invalid-batch-count {:count declared-count})

            (zero? declared-count)
            (fail state :empty-batch {})

            (not= expected-index index)
            (fail state :unexpected-batch
                  {:expected expected-index :actual index})

            (not= expected-count declared-count)
            (fail state :incomplete-batch
                  {:expected declared-count :actual expected-count})

            :else
            {:state (assoc state :phase :applying-batch)
             :effects [{:op :apply-snapshot-batch
                        :topic (:topic state)
                        :index index
                        :items (:batch state)}]})))

      :snapshot/batch-result
      (if (not= :applying-batch (:phase state))
        (invalid-phase state event)
        (if (true? (:ok event))
          (let [index (:next-batch state)]
            {:state (-> state
                        (assoc :phase :joining
                               :next-batch (inc index)
                               :batch []
                               :batch-bytes 0))
             :effects [{:op :ack-snapshot-batch
                        :topic (:topic state)
                        :index index}]})
          (fail state :snapshot-apply-failed
                {:error (:error event) :index (:next-batch state)})))

      :live/publication
      (if-not (non-negative-int? bytes)
        (fail state :invalid-byte-count {:bytes bytes})
        (case (:phase state)
          (:joining :applying-batch)
          (let [message-count (inc (count (:pending state)))
                byte-count (+ (:pending-bytes state) bytes)
                {:keys [max-pending-publishes max-pending-bytes]} (:limits state)]
            (cond
              (> message-count max-pending-publishes)
              (fail state :pending-item-overflow
                    {:limit max-pending-publishes :actual message-count})

              (> byte-count max-pending-bytes)
              (fail state :pending-byte-overflow
                    {:limit max-pending-bytes :actual byte-count})

              :else
              {:state (-> state
                          (update :pending conj (:value event))
                          (assoc :pending-bytes byte-count))
               :effects []}))

          :live
          {:state (assoc state :phase :applying-live)
           :effects [{:op :apply-live
                      :topic (:topic state)
                      :value (:value event)}]}

          (invalid-phase state event)))

      :snapshot/complete
      (if (not= :joining (:phase state))
        (invalid-phase state event)
        (if (seq (:batch state))
          (fail state :uncommitted-snapshot-items
                {:count (count (:batch state))})
          {:state (assoc state :phase :draining)
           :effects [{:op :apply-captured-live
                      :topic (:topic state)
                      :values (:pending state)}]}))

      :snapshot/drain-result
      (if (not= :draining (:phase state))
        (invalid-phase state event)
        (if (true? (:ok event))
          {:state (assoc state
                         :phase :live
                         :pending []
                         :pending-bytes 0
                         :ready-status :succeeded)
           :effects [{:op :settle-ready
                      :ok true
                      :topic (:topic state)}]}
          (fail state :captured-live-apply-failed {:error (:error event)})))

      :live/result
      (if (not= :applying-live (:phase state))
        (invalid-phase state event)
        (if (true? (:ok event))
          {:state (assoc state :phase :live) :effects []}
          (fail state :live-apply-failed {:error (:error event)})))

      :abort
      (fail state (or (:code event) :aborted) {:error (:error event)})

      :close
      (let [failure {:code :closed-before-ready :topic (:topic state)}]
        {:state (-> state
                    release-payloads
                    (assoc :phase :closed
                           :ready-status (if (= :pending (:ready-status state))
                                           :failed
                                           (:ready-status state))
                           :closed-status :succeeded))
         :effects (cond-> []
                    (= :pending (:ready-status state))
                    (conj {:op :settle-ready :error failure})

                    (= :pending (:closed-status state))
                    (conj {:op :settle-closed :ok true}))})

      (fail state :unknown-event {:event-type type}))))

(defn initial-producer-state
  "Create the sender-side snapshot terminal state.

  The item stream and producer result are independent signals. A completion
  marker is emitted only after both a clean item-stream close and explicit
  producer success have occurred."
  [topic]
  {:phase :producing
   :topic topic
   :source-closed? false
   :sender-drained? false
   :producer-result nil})

(defn- producer-fail
  [state code details]
  (let [failure (merge {:code code :topic (:topic state)} details)]
    {:state (assoc state :phase :failed :failure failure)
     :effects [{:op :fail-producer :failure failure}]}))

(defn- maybe-complete-producer
  [state]
  (if (and (:source-closed? state)
           (:sender-drained? state)
           (true? (get-in state [:producer-result :ok])))
    {:state (assoc state :phase :complete)
     :effects [{:op :send-snapshot-complete :topic (:topic state)}]}
    {:state state :effects []}))

(defn producer-transition
  "Advance the sender-side snapshot completion gate.

  Events are `:producer/source-closed`, `:producer/sender-drained`,
  `:producer/result`, `:abort`, and `:close`. Result may race with source close
  and transmission. Completion requires explicit producer success and an
  acknowledged, fully drained sender pipeline. A result without explicit
  `:ok true` fails."
  [state {:keys [type] :as event}]
  (if (contains? #{:complete :failed :closed} (:phase state))
    {:state state :effects []}
    (case type
      :producer/source-closed
      (if (:source-closed? state)
        (producer-fail state :duplicate-source-close {})
        (maybe-complete-producer (assoc state :source-closed? true)))

      :producer/sender-drained
      (if (:sender-drained? state)
        (producer-fail state :duplicate-sender-drained {})
        (maybe-complete-producer (assoc state :sender-drained? true)))

      :producer/result
      (cond
        (:producer-result state)
        (producer-fail state :duplicate-producer-result {})

        (not (true? (:ok event)))
        (producer-fail state :snapshot-producer-failed {:error (:error event)})

        :else
        (maybe-complete-producer (assoc state :producer-result event)))

      :abort
      (producer-fail state (or (:code event) :aborted) {:error (:error event)})

      :close
      {:state (assoc state :phase :closed)
       :effects [{:op :producer-closed}]}

      (producer-fail state :unknown-producer-event {:event-type type}))))
