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
                     :refer [>! timeout chan put! pub sub unsub close! alts!]]
               :cljs [clojure.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close! alts!] :include-macros true]))
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
  "Connect peer to url.

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
             (run-connection! S peer [c-in c-out] :initiator
                              (assoc attributes ::transport/dial-address url))))))

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
