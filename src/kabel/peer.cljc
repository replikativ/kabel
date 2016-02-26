(ns kabel.peer
  "Peer 2 peer connectivity."
  (:require [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            #?(:clj [full.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]])
            [kabel.platform :refer [client-connect!]
             :include-macros true]
            #?(:cljs [full.cljs.async :refer [throw-if-throwable]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.cljs.async :refer [<<? <? go-for go-try go-loop-try go-loop-try> alt?]])))


(defn- get-error-ch [peer]
  (get-in @peer [:volatile :error-ch]))

(defn drain [[peer [in out]]]
  (go-loop-try> (get-error-ch peer)
                [i (<? in)]
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
  ([id err-ch middleware]
   (client-peer id err-ch middleware (atom {}) (atom {})))
  ([id err-ch middleware read-handlers write-handlers]
   (let [log (atom {})
         bus-in (chan)
         bus-out (pub bus-in :type)]
     (atom {:volatile {:log log
                       :middleware middleware
                       :read-handlers read-handlers
                       :write-handlers write-handlers
                       :chans [bus-in bus-out]
                       :error-ch err-ch}
            :id id}))))


(defn server-peer
  "Constructs a listening peer."
  ([handler id err-ch middleware]
   (server-peer handler id err-ch middleware (atom {}) (atom {})))
  ([handler id err-ch middleware read-handlers write-handlers]
   (let [{:keys [new-conns url]} handler
         log (atom {})
         bus-in (chan)
         bus-out (pub bus-in :type)
         peer (atom {:volatile (merge handler
                                      {:middleware middleware
                                       :read-handlers read-handlers
                                       :write-handlers write-handlers
                                       :log log
                                       :error-ch err-ch
                                       :chans [bus-in bus-out]})
                     :addresses #{(:url handler)}
                     :id id})]
     (go-loop-try> err-ch [[in out] (<? new-conns)]
                   (drain (middleware [peer [in out]]))
                   (recur (<? new-conns)))
     peer)))
