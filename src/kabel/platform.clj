(ns kabel.platform
  "Platform specific io operations."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kabel.platform-log :refer [debug info warn error]]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [full.async :refer [<? <?? go-try -error *super*]]
            [full.lab :refer [go-loop-super with-super]]
            [clojure.core.async :as async
             :refer [>! timeout chan alt! put! close!]]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [com.cognitect.transit.impl WriteHandlers$MapWriteHandler]))


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
  Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-handlers and write-handlers are atoms
  according to incognito."
  ([url err-ch peer-id]
   (client-connect! url err-ch peer-id (atom {}) (atom {})))
  ([url err-ch peer-id read-handlers write-handlers]
   (defonce singleton-http-client (cli/create-client))
   (client-connect! url err-ch peer-id read-handlers write-handlers singleton-http-client))
  ([url err-ch peer-id read-handlers write-handlers http-client]
   (let [in (chan)
         out (chan)
         opener (chan)
         host (.getHost (java.net.URL. (.replace url "ws" "http")))
         super *super*]
     (try
       (cli/websocket http-client url
                      :open (fn [ws]
                              (info "ws-opened" ws)
                              (go-loop-super [m (<? out)]
                                             (when m
                                               (debug "client sending msg to:" url (:type m))
                                               (with-open [baos (ByteArrayOutputStream.)]
                                                 (let [writer (transit/writer baos :json
                                                                              {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                                   (transit/write writer (assoc m :sender peer-id) ))
                                                 (cli/send ws :byte (.toByteArray baos)))
                                               (recur (<? out))))
                              (async/put! opener [in out])
                              (close! opener))
                      :byte (fn [ws ^bytes data]
                              (with-super super
                                (try
                                  (debug "received byte message")
                                  (with-open [bais (ByteArrayInputStream. data)]
                                    (let [reader
                                          (transit/reader bais :json
                                                          {:handlers {"incognito" (incognito-read-handler read-handlers)}})
                                          m (transit/read reader)]
                                      (debug "client received transit blob from:" url (:type m))
                                      (async/put! in (assoc m :host host))))
                                  (catch Exception e
                                    (put! (-error *super*)
                                          (ex-info "Cannot receive data." {:url url
                                                                           :data data
                                                                           :error e}))
                                    (close! in)))))
                      :close (fn [ws code reason]
                               (with-super super
                                 (let [e (ex-info "Connection closed!" {:code code
                                                                        :reason reason})]
                                   (error "closing" url "with" code reason)
                                   (close! in)
                                   (put! (-error *super*) e)
                                   (try (put! opener e) (catch Exception e))
                                   (close! opener))))
                      :error (fn [ws err]
                               (with-super super
                                 (let [e (ex-info "ws-error"
                                                  {:type :websocket-connection-error
                                                   :url url
                                                   :error err})]
                                   (put! (-error *super*) e)
                                   (error "ws-error" url err)
                                   (async/put! opener e)
                                   (close! opener)))))
       (catch Exception e
         (error "client-connect error:" url e)
         (async/put! opener (ex-info "client-connect error"
                                     {:type :websocket-connection-error
                                      :url url
                                      :error e}))
         (close! in)
         (close! opener)))
     opener)))


(defn create-http-kit-handler!
  "Creates a server handler described by url, e.g. wss://myhost:8443/replikativ/ws.
  Returns a map to run a peer with a platform specific server handler
  under :handler.  read-handlers and write-handlers are atoms
  according to incognito."
  ([url peer-id]
   (create-http-kit-handler! url peer-id (atom {}) (atom {})))
  ([url peer-id read-handlers write-handlers]
   (let [channel-hub (atom {})
         conns (chan)
         super *super*
         handler (fn [request]
                   (let [in (chan)
                         out (chan)]
                     (async/put! conns [in out])
                     (with-channel request channel
                       (swap! channel-hub assoc channel request)
                       (with-super super
                         (go-loop-super [m (<? out)]
                                        (when m
                                          (if (@channel-hub channel)
                                            (do
                                              (with-open [baos (ByteArrayOutputStream.)]
                                                (let [writer (transit/writer baos :json
                                                                             {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                                  (debug "server sending msg:" url (:type m))
                                                  (transit/write writer (assoc m :sender peer-id))
                                                  (debug "server sent transit msg"))
                                                (send! channel ^bytes (.toByteArray baos))))
                                            (warn "dropping msg because of closed channel: " url (pr-str m)))
                                          (recur (<? out)))))
                       (on-close channel (fn [status]
                                           (with-super super
                                             (let [e (ex-info "Connection closed!" {:status status})
                                                   host (:remote-addr request)]
                                               (warn "channel closed:" host "status: " status)
                                               (put! (-error *super*) e))
                                             (swap! channel-hub dissoc channel)
                                             (close! in))))
                       (on-receive channel (fn [data]
                                             (with-super super
                                               (let [blob data
                                                     host (:remote-addr request)]
                                                 (debug "received byte message")
                                                 (try
                                                   (with-open [bais (ByteArrayInputStream. blob)]
                                                     (let [reader
                                                           (transit/reader bais :json
                                                                           {:handlers {"incognito" (incognito-read-handler read-handlers)}})
                                                           m (transit/read reader)]
                                                       (debug "server received transit blob from:"
                                                              url (apply str (take 100 (str m))))
                                                       (async/put! in (assoc m :host host))))

                                                   (catch Exception e
                                                     (put! (-error *super*)
                                                           (ex-info "Cannot receive data." {:data data
                                                                                            :host host
                                                                                            :error e}))
                                                     (close! in))))))))))]
     {:new-conns conns
      :channel-hub channel-hub
      :url url
      :handler handler})))




(defn start [peer]
  (when-let [handler (-> @peer :volatile :handler)]
    (println "starting" (:id @peer))
    (swap! peer assoc-in [:volatile :server]
           (run-server handler {:port (->> (-> @peer :volatile :url)
                                           (re-seq #":(\d+)")
                                           first
                                           second
                                           read-string)
                                :max-body (* 512 1024 1024)}))
    true))


(defn stop [peer]
  (when-let [stop-fn (get-in @peer [:volatile :server])]
    (stop-fn :timeout 100))
  (<?? (timeout 200))
  (when-let [hub (get-in @peer [:volatile :channel-hub])]
    (reset! hub {}))
  (when-let [in (-> @peer :volatile :chans first)]
    (close! in))
  true)


(comment
  (defrecord Foo [a b])

  {:handlers {java.util.Map irecord-write-handler}}

  (def out (ByteArrayOutputStream. 4096))
  (def writer (transit/writer out :json {:handlers
                                         ;; add ground type for records
                                         {java.util.Map
                                          (proxy [WriteHandlers$MapWriteHandler] []
                                            (tag [o] (if (isa? (type o) clojure.lang.IRecord)
                                                       "irecord"
                                                       (proxy-super tag o)))
                                            (rep [o] (if (isa? (type o)  clojure.lang.IRecord)
                                                       (assoc (into {} o) :_gnd$tl (pr-str (type o)))
                                                       (proxy-super rep o))))}}))
  (transit/write writer "foo")
  (transit/write writer {:a [1 2]})
  (transit/write writer (Foo. 3 4))


  (require '[clojure.reflect :refer [reflect]]
           '[clojure.pprint :refer [pprint]])

  (import '[com.cognitect.transit.impl WriteHandlers$MapWriteHandler])

  (import '[com.cognitect.transit.impl ReadHandlers$MapReadHandler])

  (pprint (reflect (proxy [WriteHandlers$MapWriteHandler] []
                     (tag [o] (if (isa? clojure.lang.IRecord (type o))
                                "irecord"
                                (proxy-super tag o))))))

  (.tag (proxy [WriteHandlers$MapWriteHandler] []
          (tag [o] (if (isa? clojure.lang.IRecord (type o))
                     "irecord"
                     (proxy-super tag o)))
          (rep [o] (if (isa? clojure.lang.IRecord (type o))
                     (assoc (into {} o) :_gnd$tl (pr-str (type o)))
                     (proxy-super rep o))))
        {:a :b})


  (isa? (type (proxy [com.cognitect.transit.MapReadHandler] []
                (tag [o] (if (isa? clojure.lang.IRecord o)
                           "irecord"
                           (proxy-super tag o)))))
        com.cognitect.transit.MapReadHandler)


  ;; Take a peek at the JSON
  (.toString out)
  ;; => "{\"~#'\":\"foo\"} [\"^ \",\"~:a\",[1,2]]"


  ;; Read data from a stream
  (def in (ByteArrayInputStream. (.toByteArray out)))
  (def reader (transit/reader in :json {:handlers {"irecord"
                                                   (transit/read-handler (fn [rep]
                                                                           (try
                                                                             (if (Class/forName (:_gnd$tl rep))
                                                                               ((let [[_ pre t] (re-find #"(.+)\.([^.]+)" (:_gnd$tl rep))]
                                                                                  (resolve (symbol (str pre "/map->" t)))) (dissoc rep :_gnd$tl)))
                                                                             (catch Exception e
                                                                               (debug "Cannot deserialize record" (:_gnd$tl rep) e)
                                                                               rep))))}}))
  (prn (transit/read reader)) ;; => "foo"
  (prn (transit/read reader)) ;; => {:a [1 2]}

  (let [writer (transit/writer baos :json)]
    (.write baos (byte-array 1 (byte 1)))
    (transit/write writer m))
  )
