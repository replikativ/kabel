(ns kabel.peer
  "Peer 2 peer connectivity."
  (:require [replikativ.logging :as log]
            [clojure.set :as set]
            #?(:clj [superv.async :refer [<? <<? go-try go-loop-try alt?
                                          go-loop-super]])
            [kabel.client :refer [client-connect!]]
            [kabel.transport :as transport]
            [kabel.middleware.transit :refer [transit]]
            #?(:cljs [superv.async :refer [throw-if-exception
                                           -track-exception -free-exception
                                           -register-go -unregister-go]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan promise-chan put! pub sub unsub close! alts!]]
               :cljs [clojure.core.async :as async
                      :refer [>! timeout chan promise-chan put! pub sub unsub close! alts!] :include-macros true]))
  #?(:cljs (:require-macros [superv.async :refer [<<? <? go-try go-loop-try alt?
                                                  go-loop-super]])))

;; ============================================================================
;; Peer Registry
;; ============================================================================
;; Global registry of peers by id. Enables lookup from handlers that need
;; to access peer for pubsub or other operations.

(defonce peers (atom {}))

(defn register-peer!
  "Register a peer in the global registry. Called automatically by client-peer
   and server-peer."
  [peer]
  (let [id (:id @peer)]
    (swap! peers assoc id peer)
    peer))

(defn unregister-peer!
  "Unregister a peer from the global registry."
  [peer-id]
  (swap! peers dissoc peer-id))

(defn get-peer
  "Get a peer by id from the global registry."
  [peer-id]
  (get @peers peer-id))

(defn drain [[S peer [in out]]]
  (go-loop-super S [i (<? S in)]
                 (if i
                   (recur (<? S in))
                   (close! out))))

(defn status!
  "Publish a connection status on the peer's bus as
   `{:type :kabel.peer/status :status s ...}`. Statuses kabel itself emits:
   `:connecting`, `:connected`, `:disconnected`, `:failed` (an attempt, with
   `:error`), `:authenticated` (with `:principal`, from the auth middleware),
   `:stopped`. Layers above may publish their own."
  [peer status & {:as more}]
  (let [[bus-in _] (get-in @peer [:volatile :chans])]
    (put! bus-in (merge {:type ::status :status status :peer (:id @peer)} more))
    nil))

(defn- run-connection!
  [S peer channels role attributes]
  (let [{{:keys [middleware serialization-middleware transport-middleware
                 connection-context]} :volatile} @peer
        context (transport/new-context role (merge connection-context attributes))]
    (transport/register! peer context)
    (try
      (let [pipeline (->> (transport/with-context [S peer channels] context)
                          (transport/apply-middleware transport-middleware)
                          (transport/apply-middleware serialization-middleware)
                          (transport/apply-middleware middleware))
            done (drain pipeline)]
        ;; Registration follows a physical connection, not a peer. Keeping
        ;; cleanup beside `drain` also covers middleware-initiated shutdown.
        (go-try S
                (try
                  (<? S done)
                  (finally
                    (transport/unregister! peer context))))
        done)
      (catch #?(:clj Throwable :cljs :default) e
        (transport/unregister! peer context)
        (throw e)))))

(defn connect
  "Connect peer to url. Yields a channel that closes when the connection does;
  throws when the connection cannot be made.

  The optional `attributes` map adds dial-local connection context such as
  `::transport/expected-target`. It is per physical connection rather than
  peer-wide because one client peer may dial many authenticated authorities.
  The actual dial address is transport-owned and cannot be overridden."
  ([S peer url]
   (connect S peer url {}))
  ([S peer url attributes]
   (go-try S
           (let [{{:keys [read-handlers write-handlers]} :volatile
                  :keys [id]} @peer
                 [c-in c-out] (<? S (client-connect! S url
                                                     id
                                                     read-handlers
                                                     write-handlers))]
             (swap! peer assoc-in [:volatile :connection] {:url url :in c-in :out c-out})
             (run-connection! S peer [c-in c-out] :initiator
                              (assoc attributes ::transport/dial-address url))))))

(defn disconnect!
  "Close the client peer's current connection, if any. `maintain` reconnects
   unless it was stopped first."
  [peer]
  (when-let [{:keys [out]} (get-in @peer [:volatile :connection])]
    (close! out)
    (swap! peer update :volatile dissoc :connection)
    true))

(def default-backoff
  "Exponential backoff between reconnection attempts, in milliseconds, with
   proportional jitter."
  {:initial-ms 500 :max-ms 30000 :factor 2 :jitter 0.2})

(defn- backoff-ms [{:keys [initial-ms max-ms factor jitter]} attempt]
  (let [base (min max-ms (* initial-ms (Math/pow factor attempt)))]
    (long (* base (+ 1 (* jitter (- (* 2 (rand)) 1)))))))

(defn- exception? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defn maintain
  "Keep a client peer connected to `url`: connect, reconnect with backoff when
   the connection drops or an attempt fails, and report every transition
   through `status!` on the peer's bus and to `:on-status`.

   Each reconnection runs the peer's middleware afresh, so the layers above
   see a new connection exactly as they saw the first: the auth middleware
   authenticates again, `kabel.remote` announces itself again, pub/sub
   subscriptions have to be made again.

   `opts`:
     :on-status    (fn [{:keys [status attempt error ...]}]), called for every
                   status the peer publishes while maintained, kabel's own and
                   those of layers above.
     :backoff      see `default-backoff`.
     :max-attempts give up after this many consecutive failed attempts
                   (default unlimited); status `:stopped` with `:reason :gave-up`.

   Returns `{:stop! (fn []) :status (atom last-status) :done ch}`; `:done`
   closes once the loop has ended."
  [S peer url {:keys [on-status backoff max-attempts]}]
  (let [backoff (merge default-backoff backoff)
        [_ bus-out] (get-in @peer [:volatile :chans])
        status-ch (chan 100)
        last-status (atom nil)
        stop-ch (promise-chan)
        stopped? (atom false)
        done (promise-chan)]
    (sub bus-out ::status status-ch)
    (go-loop-super S [s (<? S status-ch)]
                   (when s
                     (reset! last-status s)
                     (when on-status
                       (try (on-status s)
                            (catch #?(:clj Throwable :cljs :default) e
                              (log/warn :on-status-failed {:error (ex-message e)}))))
                     (if (= :stopped (:status s))
                       (do (unsub bus-out ::status status-ch)
                           (close! status-ch)
                           (close! done))
                       (recur (<? S status-ch)))))
    (go-loop-super S [attempt 0 failures 0]
                   (if @stopped?
                     (status! peer :stopped :reason :stopped)
                     (do
                       (status! peer :connecting :url url :attempt attempt)
                       (let [closed (try (<? S (connect S peer url))
                                         (catch #?(:clj Throwable :cljs :default) e e))]
                         (if (exception? closed)
                           (do
                             (status! peer :failed :url url :attempt attempt :error (ex-message closed))
                             (log/info :connect-failed {:url url :attempt attempt :error (ex-message closed)})
                             (if (and max-attempts (>= (inc failures) max-attempts))
                               (status! peer :stopped :reason :gave-up :attempts (inc failures))
                               (do (alts! [stop-ch (timeout (backoff-ms backoff failures))])
                                   (recur (inc attempt) (inc failures)))))
                           (do
                             (status! peer :connected :url url :attempt attempt)
                             (<? S closed)
                             (swap! peer update :volatile dissoc :connection)
                             (status! peer :disconnected :url url)
                             (if @stopped?
                               (status! peer :stopped :reason :stopped)
                               (do (alts! [stop-ch (timeout (backoff-ms backoff 0))])
                                   (recur (inc attempt) 0)))))))))
    {:stop! (fn []
              (reset! stopped? true)
              (close! stop-ch)
              (disconnect! peer)
              nil)
     :status last-status
     :done done}))

(defn client-peer
  "Creates a client-side peer only.

  The final optional map accepts `:transport-middleware`, applied outside the
  serialization middleware, and a base `:connection-context` map. Earlier
  arities retain their exact behavior."
  ([S id middleware]
   (client-peer S id middleware transit))
  ([S id middleware serialization-middleware]
   (client-peer S id middleware serialization-middleware (atom {}) (atom {})))
  ([S id middleware serialization-middleware read-handlers write-handlers]
   (client-peer S id middleware serialization-middleware read-handlers
                write-handlers {}))
  ([S id middleware serialization-middleware read-handlers write-handlers
    {:keys [transport-middleware connection-context]
     :or {transport-middleware identity connection-context {}}}]
   (let [log (atom {})
         bus-in (chan)
         bus-out (pub bus-in :type)
         peer (atom {:volatile {:log log
                                :middleware middleware
                                :serialization-middleware serialization-middleware
                                :transport-middleware transport-middleware
                                :connection-context connection-context
                                :read-handlers read-handlers
                                :write-handlers write-handlers
                                :supervisor S
                                :chans [bus-in bus-out]}
                     :id id})]
     (register-peer! peer))))

(defn server-peer
  "Constructs a listening peer.

  The final optional map accepts `:transport-middleware`, applied outside the
  serialization middleware, and a base `:connection-context` map. Earlier
  arities retain their exact behavior."
  ([S handler id middleware]
   (server-peer S handler id middleware transit))
  ([S handler id middleware serialization-middleware]
   (server-peer S handler id middleware serialization-middleware (atom {}) (atom {})))
  ([S handler id middleware serialization-middleware read-handlers write-handlers]
   (server-peer S handler id middleware serialization-middleware read-handlers
                write-handlers {}))
  ([S handler id middleware serialization-middleware read-handlers write-handlers
    {:keys [transport-middleware connection-context]
     :or {transport-middleware identity connection-context {}}}]
   (let [{:keys [new-conns url]} handler
         log (atom {})
         bus-in (chan)
         bus-out (pub bus-in :type)
         peer (atom {:volatile (merge handler
                                      {:middleware middleware
                                       :serialization-middleware serialization-middleware
                                       :transport-middleware transport-middleware
                                       :connection-context connection-context
                                       :read-handlers read-handlers
                                       :write-handlers write-handlers
                                       :log log
                                       :supervisor S
                                       :chans [bus-in bus-out]})
                     :addresses #{(:url handler)}
                     :id id})]
     (go-loop-super S [connection (<? S new-conns)]
                    (when connection
                      (let [[in out attributes] connection]
                        (run-connection! S peer [in out] :responder
                                         (or attributes {})))
                      (recur (<? S new-conns))))
     (register-peer! peer))))

(defn start [peer]
  (let [{{S :supervisor} :volatile} @peer]
    (go-try S
            (if (:started? @peer)
              false
              (let [stop-fn (-> @peer :volatile :handler :stop-fn)]
                (log/info :starting-peer {:id (:id @peer)})
                (swap! peer update-in [:volatile] (get-in @peer [:volatile :start-fn]))
                (swap! peer assoc :started? true)
                true)))))

(defn stop [peer]
  (let [{{S :supervisor} :volatile} @peer]
    (go-try S
            (if-not (:started? @peer)
              false
              (do
                (log/info :stopping-peer {:id (:id @peer)})
                (when-let [stop-fn (get-in @peer [:volatile :stop-fn])]
                  (stop-fn :timeout 1000))
                (<? S (timeout 200))
                (when-let [hub (get-in @peer [:volatile :channel-hub])]
                  (reset! hub {}))
                (when-let [in (-> @peer :volatile :chans first)]
                  (close! in))
                (swap! peer assoc :started? false)
          ;; Unregister from global registry
                (unregister-peer! (:id @peer))
                true)))))
