(ns kabel.middleware.transit
  (:require
   [kabel.middleware.handler :refer [handler]]
   [superv.async :refer [<? go-try]]
   [clojure.core.async :as async
    :refer [<! >! timeout chan alt! put! close! buffer]]
   [cognitect.transit :as transit]
   [incognito.transit :refer [incognito-read-handler incognito-write-handler]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [com.cognitect.transit.impl WriteHandlers$MapWriteHandler]))


(defn transit
  "Serializes all incoming and outgoing edn datastructures in transit form."
  ([[S peer [in out]]]
   (transit :json (atom {}) (atom {}) [S peer [in out]]))
  ([backend read-handlers write-handlers [S peer [in out]]]
   (handler #(go-try S
                     (with-open [bais (ByteArrayInputStream. %)]
                       (let [reader
                             (transit/reader bais backend
                                             {:handlers {"incognito" (incognito-read-handler read-handlers)}})]
                         (transit/read reader))))
            #(go-try S
                     (with-open [baos (ByteArrayOutputStream.)]
                       (let [writer (transit/writer baos backend
                                                    {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                         (transit/write writer %))
                       (.toByteArray baos)))
            [S peer [in out]])))

(comment
  (require '[superv.async :refer [S <??]])

  (let [in (chan)
        out (chan)
        [S _ [nin nout]] (transit [S nil [in out]])]
    (put! nout "hello")
    (prn (vec (<?? S out)))
    ))




