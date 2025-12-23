(ns kabel.middleware.json
  (:require
   [kabel.middleware.handler :refer [handler]]
   #?(:clj [kabel.platform-log :refer [debug]])
   #?(:cljs [kabel.util :refer [on-node?]])
   #?(:clj [superv.async :refer [go-try]])
   #?(:clj [cheshire.core :as json]))
  #?(:cljs (:require-macros [superv.async :refer [go-try]]
                            [kabel.platform-log :refer [debug]])))

(defn json
  "Serializes all incoming and outgoing edn datastructures in string form (with JSON).
  This middleware is for interaction with plan JSON websocket end-points and
  cannot provide the full kabel server-client protocol. "
  ([[S peer [in out]]]
   (handler #(go-try S
                     (let [{:keys [kabel/serialization kabel/payload]} %]
                       (if (= serialization :string)
                         (let [v #?(:clj (json/parse-string payload)
                                    :cljs (js->clj (.parse js/JSON payload)))
                               merged (if (map? v)
                                        (merge v (dissoc % :kabel/serialization
                                                         :kabel/payload))
                                        v)]
                           #_(debug {:event :json-deserialized
                                     :value merged})
                           merged)
                         %)))
            #(go-try S
                     (if (:kabel/serialization %) ;; already serialized
                       %
                       (do
                         #_(debug {:event :json-serialize
                                   :value %})
                         {:kabel/serialization :string
                          :kabel/payload #?(:clj (json/generate-string %)
                                            :cljs (.stringify js/JSON %))})))
            [S peer [in out]])))

(comment
  (require '[superv.async :refer [S <??]])

  (let [in (chan)
        out (chan)
        [S _ [nin nout]] (transit [S nil [in out]])]
    (put! nout "hello")
    (prn (vec (<?? S out))))

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
      (.send channel (.from js/Buffer to-send)) ;; NodeJS
      )))




