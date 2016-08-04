(ns kabel.peer
  "Peer 2 peer connectivity."
  (:require [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            #?(:clj [full.async :refer [<? <<? go-try go-loop-try alt?]])
            #?(:clj [full.lab :refer [go-loop-super]])
            [kabel.platform :refer [client-connect!]
             :include-macros true]
            #?(:cljs [full.async :refer [throw-if-exception
                                         *super* -track-exception -free-exception
                                         -register-go -unregister-go]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.async :refer [<<? <? go-try go-loop-try alt?]]
                            [full.lab :refer [go-loop-super]])))


(defn drain [[peer [in out]]]
  (go-loop-super [i (<? in)]
                 (when i
                   (recur (<? in)))))

(defn connect
  "Connect peer to url."
  [peer url error-ch]
  (go-try
   (let [{{:keys [middleware read-handlers write-handlers]} :volatile
          :keys [id]} @peer
         [c-in c-out] (<? (client-connect! url
                                           error-ch
                                           id
                                           read-handlers
                                           write-handlers))]
     (drain (middleware [peer [c-in c-out]])))))

(defn client-peer
  "Creates a client-side peer only."
  ([id middleware]
   (client-peer id middleware (atom {}) (atom {})))
  ([id middleware read-handlers write-handlers]
   (let [log (atom {})
         bus-in (chan)
         bus-out (pub bus-in :type)]
     (atom {:volatile {:log log
                       :middleware middleware
                       :read-handlers read-handlers
                       :write-handlers write-handlers
                       :chans [bus-in bus-out]}
            :id id}))))


(defn server-peer
  "Constructs a listening peer."
  ([handler id middleware]
   (server-peer handler id middleware (atom {}) (atom {})))
  ([handler id middleware read-handlers write-handlers]
   (let [{:keys [new-conns url]} handler
         log (atom {})
         bus-in (chan)
         bus-out (pub bus-in :type)
         peer (atom {:volatile (merge handler
                                      {:middleware middleware
                                       :read-handlers read-handlers
                                       :write-handlers write-handlers
                                       :log log
                                       :chans [bus-in bus-out]})
                     :addresses #{(:url handler)}
                     :id id})]
     (go-loop-super [[in out] (<? new-conns)]
                    (drain (middleware [peer [in out]]))
                    (recur (<? new-conns)))
     peer)))
