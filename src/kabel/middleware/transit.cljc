(ns kabel.middleware.transit
  (:require
   [kabel.middleware.handler :refer [handler]]
   [superv.async :refer [<? go-try]]
   [cognitect.transit :as transit]
   [incognito.transit :refer [incognito-read-handler incognito-write-handler]])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
                   [com.cognitect.transit.impl WriteHandlers$MapWriteHandler])))


(defn transit
  "Serializes all incoming and outgoing edn datastructures in transit form."
  ([[S peer [in out]]]
   (transit :json (atom {}) (atom {}) [S peer [in out]]))
  ([backend read-handlers write-handlers [S peer [in out]]]
   (handler #(go-try S
                     #?(:clj (with-open [bais (ByteArrayInputStream. %)]
                               (let [reader
                                     (transit/reader bais backend
                                                     {:handlers {
                                                                 "incognito" (incognito-read-handler read-handlers)}})]
                                 (transit/read reader)))
                        :cljs (let [reader
                                    (transit/reader % backend
                                                    {"u" (fn [v] (cljs.core/uuid v))
                                                     :handlers {"incognito" (incognito-read-handler read-handlers)}})]
                                (transit/read reader))))
             #(go-try S
                      #?(:clj (with-open [baos (ByteArrayOutputStream.)]
                                (let [writer (transit/writer baos backend
                                                             {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                  (transit/write writer %))
                                (.toByteArray baos))
                         :cljs (let [writer (transit/writer backend
                                                            {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                 (transit/write writer %))))
             [S peer [in out]])))

(comment
  (require '[superv.async :refer [S <??]])

  (let [in (chan)
        out (chan)
        [S _ [nin nout]] (transit [S nil [in out]])]
    (put! nout "hello")
    (prn (vec (<?? S out)))
    )


(let [reader (transit/reader :json {:handlers ;; remove if uuid problem is gone
                                                              {"u" (fn [v] (cljs.core/uuid v))
                                                               "incognito" (incognito-read-handler read-handlers)}})]
                            (if-not (on-node?)
                              ;; Browser
                              (let [fr (js/FileReader.)]
                                (set! (.-onload fr) #(let [res (js/String. (.. % -target -result))]
                                                       #_(debug "Received message: " res)
                                                       (put! in (assoc (transit/read reader res) :host host))))

                                (.readAsText fr (.-message evt)))
                              ;; nodejs
                              (let [s  (js/String.fromCharCode.apply
                                        nil
                                        (js/Uint8Array. (.. evt -message)))]
                                (put! in (assoc (transit/read reader s) :host host)))))

  (let [i-write-handler (incognito-write-handler write-handlers)
        writer (transit/writer
                :json
                {:handlers {"default" i-write-handler}})
        to-send (transit/write writer (assoc m :sender peer-id))]
    (if-not (on-node?)
      ;(.send channel (js/Blob. #js [to-send])) ;; Browser
      (.send channel (js/Buffer. to-send)) ;; NodeJS
      ))

  )




