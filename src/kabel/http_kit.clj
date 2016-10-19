(ns kabel.http-kit
  "http-kit specific IO operations."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kabel.platform-log :refer [debug info warn error]]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [superv.async :refer [<? <?? go-try -error]]
            [superv.lab :refer [go-loop-super]]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! put! close! buffer]]
            [org.httpkit.server :refer :all]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [com.cognitect.transit.impl WriteHandlers$MapWriteHandler]))





(defn create-http-kit-handler!
  "Creates a server handler described by url, e.g. wss://myhost:8443/replikativ/ws.
  Returns a map to run a peer with a platform specific server handler
  under :handler.  read-handlers and write-handlers are atoms
  according to incognito."
  ([S url peer-id]
   (create-http-kit-handler! S url peer-id (atom {}) (atom {})))
  ([S url peer-id read-handlers write-handlers]
   (let [channel-hub (atom {})
         conns (chan)
         handler (fn [request]
                   (let [in-buffer (buffer 1024) ;; standard size
                         in (chan)
                         out (chan)]
                     (async/put! conns [in out])
                     (with-channel request channel
                       (swap! channel-hub assoc channel request)
                       (go-loop-super S [m (<? S out)]
                                      (when m
                                        (if (@channel-hub channel)
                                          (do
                                            (with-open [baos (ByteArrayOutputStream.)]
                                              (let [writer (transit/writer baos :json
                                                                           {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                                (debug {:event :server-sending-message
                                                        :url url :type (:type m)})
                                                (transit/write writer (assoc m :sender peer-id))
                                                (debug {:event :server-sent-transit-message
                                                        :url url}))
                                              (send! channel ^bytes (.toByteArray baos))))
                                          (warn {:event :dropping-msg-because-of-closed-channel
                                                 :url url :message m}))
                                        (recur (<? S out))))
                       (on-close channel (fn [status]
                                           (let [e (ex-info "Connection closed!" {:status status})
                                                 host (:remote-addr request)]
                                             (warn {:event :channel-closed
                                                    :host host :status status})
                                             (put! (-error S) e))
                                           (swap! channel-hub dissoc channel)
                                           (go-try S (while (<! in))) ;; flush
                                           (close! in)))
                       (on-receive channel (fn [data]
                                             (let [blob data
                                                   host (:remote-addr request)]

                                               (try
                                                 (debug {:event :received-byte-message})
                                                 (when (> (count in-buffer) 100)
                                                   (close channel)
                                                   (throw (ex-info
                                                           (str "incoming buffer for " (:remote-addr request)
                                                                " too full:" (count in-buffer))
                                                           {:url url
                                                            :count (count in-buffer)}))) 
                                                 (with-open [bais (ByteArrayInputStream. blob)]
                                                   (let [reader
                                                         (transit/reader bais :json
                                                                         {:handlers {"incognito" (incognito-read-handler read-handlers)}})
                                                         m (transit/read reader)]
                                                     (debug {:event :server-received-transit-blob
                                                             :url url})
                                                     (async/put! in (assoc m :host host))))

                                                 (catch Exception e
                                                   (put! (-error S)
                                                         (ex-info "Cannot receive data." {:data data
                                                                                          :host host
                                                                                          :error e}))
                                                   (close channel)))))))))]
     {:new-conns conns
      :channel-hub channel-hub
      :start-fn (fn start-fn [{:keys [handler] :as volatile}]
                  (when-not (:stop-fn handler)
                    (-> volatile
                        (assoc :stop-fn
                               (run-server handler
                                           {:port (->> url
                                                       (re-seq #":(\d+)")
                                                       first
                                                       second
                                                       read-string)
                                            :max-body (* 512 1024 1024)
                                            :max-ws (* 512 1024 1024)})))))
      :url url
      :handler handler})))






