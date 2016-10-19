(ns kabel.client
  "http.async.client specific client IO operations."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [kabel.platform-log :refer [debug info warn error]]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [superv.async :refer [<? <?? go-try -error]]
            [superv.lab :refer [go-loop-super]]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! put! close! buffer]]
            [http.async.client :as cli]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [com.cognitect.transit.impl WriteHandlers$MapWriteHandler]))


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
  Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-handlers and write-handlers are atoms
  according to incognito."
  ([S url peer-id]
   (client-connect! S url peer-id (atom {}) (atom {})))
  ([S url peer-id read-handlers write-handlers]
   (defonce singleton-http-client (cli/create-client))
   (client-connect! S url peer-id read-handlers write-handlers singleton-http-client))
  ([S url peer-id read-handlers write-handlers http-client]
   (let [in-buffer (buffer 1024) ;; standard size
         in (chan)
         out (chan)
         opener (chan)
         websockets (atom #{})
         host (.getHost (java.net.URL. (.replace url "ws" "http")))]
     (try
       (cli/websocket http-client url
                      :open (fn [ws]
                              (info {:event :websocket-opened :websocket ws :url url})
                              (go-loop-super S
                                             [m (<? S out)] ;; ensure draining out on disconnect
                                             (when m
                                               (if (@websockets ws)
                                                 (do
                                                   (debug {:event :client-sending-message
                                                           :url url :type (:type m)})
                                                   (with-open [baos (ByteArrayOutputStream.)]
                                                     (let [writer (transit/writer baos :json
                                                                                  {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                                       (transit/write writer (assoc m :sender peer-id) ))
                                                     (cli/send ws :byte (.toByteArray baos))))
                                                 (warn {:event :dropping-msg-because-of-closed-channel
                                                        :url url :message m}))
                                               (recur (<? S out))))
                              (swap! websockets conj ws)
                              (async/put! opener [in out])
                              (close! opener)
                              )
                      :byte (fn [ws ^bytes data]
                              (try
                                (when (> (count in-buffer) 100)
                                  (.close ws)
                                  (throw (ex-info
                                          (str "incoming buffer for " url
                                               " too full:" (count in-buffer))
                                          {:url url
                                           :count (count in-buffer)})))
                                (debug {:event :received-byte-message
                                        :in-buffer-count (count in-buffer)})
                                (with-open [bais (ByteArrayInputStream. data)]
                                  (let [reader
                                        (transit/reader bais :json
                                                        {:handlers {"incognito" (incognito-read-handler read-handlers)}})
                                        m (transit/read reader)]
                                    (debug {:event :received-transit-blob :url url :type (:type m)})
                                    (async/put! in (assoc m :host host))))
                                (catch Exception e
                                  (let [e (ex-info "Cannot receive data." {:url url
                                                                           :data data
                                                                           :error e})]
                                    (error {:event :cannot-receive-message
                                            :error e})
                                    (put! (-error S) e)
                                    (.close ws)))))
                      :close (fn [ws code reason]
                               (let [e (ex-info "Connection closed!" {:code code
                                                                      :reason reason})]
                                 (warn {:event :closing-connection :url url :code code
                                        :reason reason})
                                 (close! in)
                                 (go-try S (while (<! in))) ;; flush
                                 (swap! websockets disj ws)
                                 (put! (-error S) e)
                                 (try (put! opener e) (catch Exception e))
                                 (close! opener)))
                      :error (fn [ws err]
                               (let [e (ex-info "Websocket error."
                                                {:type :websocket-connection-error
                                                 :url url
                                                 :error err})]
                                 (put! (-error S) e)
                                 (error {:event :websocket-error :url url :error err})
                                 (.close ws))))
       (catch Exception e
         (error {:event :client-connect-error :url url :error e})
         (async/put! opener (ex-info "client-connect error"
                                     {:type :websocket-connection-error
                                      :url url
                                      :error e}))
         (close! in)
         (close! opener)))
     opener)))
