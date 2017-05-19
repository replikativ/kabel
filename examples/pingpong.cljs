(ns kabel.examples.pingpong
  (:require [kabel.client :as cli]
            [kabel.peer :as peer]
            [superv.async :refer [S put?]]
            [clojure.core.async :refer [chan]]
            [hasch.core :refer [uuid]])
  (:require-macros [superv.async :refer [<? go-try]]))


;; this url is needed for the server to open the proper
;; socket and for the client to know where to connect to
(def url "ws://localhost:47291")


(def client-id #uuid "a15c628b-b151-4967-ae0a-7c83e5622d0e")

;; client
(def client (peer/client-peer S client-id
                              ;; Here we have a simple middleware to trigger some roundtrips
                              ;; from the client
                              (fn [[S peer [in out]]]
                                (let [new-in (chan)
                                      new-out (chan)]
                                  (go-try S
                                          (put? S out "ping cljs")
                                          (println "1. client incoming message:" (<? S in))
                                          (put? S out "ping2 cljs")
                                          (println "2. client incoming message:" (<? S in)))
                                  [S peer [new-in new-out]]))
                              ;; we need to pick the same middleware for serialization
                              ;; (no auto-negotiation yet)
                              identity))


;; let's connect the client to the server
(peer/connect S client url)






