(ns kabel.client
  "tyrus client specific client IO operations."
  (:require [kabel.platform-log :refer [debug info warn error]]
            [kabel.binary :refer [to-binary from-binary]]
            [superv.async :refer [<? <?? go-try -error go-loop-super S >?]]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! put! close! buffer]])
  (:import [javax.websocket Endpoint ClientEndpointConfig WebSocketContainer
            ClientEndpointConfig$Builder
            ClientEndpointConfig$Configurator]
           ;; we need this to signal String and Binary dispatch to tyrus
           ;; this is because of type erasure of the JVM and a lack of being able
           ;; to communicate generics to tyrus
           [io.replikativ.kabel MessageHandlerString MessageHandlerBinary]
           [org.glassfish.tyrus.client ClientManager]
           [java.nio ByteBuffer]))

;; Example taken from https://tyrus.java.net/documentation/1.13.1/index/getting-started.html

;; TODO make header configurable
(def cec
  (let [configurator (proxy [ClientEndpointConfig$Configurator] []
                       (beforeRequest [headers]
                         #_(prn "vanilla headers" headers)
                         (.put headers "Sec-WebSocket-Protocol" (java.util.Arrays/asList (into-array ["wamp.2.json"])))
                         #_(.put headers "Content-Type" (java.util.Arrays/asList (into-array ["application/json"])))
                         #_(prn "new headers" headers)
                         nil)
                       (beforeResponse [handshake-response]
                         (prn "after response" (.getHeaders handshake-response))))
        config-builder (ClientEndpointConfig$Builder/create)]
    (.configurator config-builder configurator)
    (.build config-builder)))



(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
  Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-handlers and write-handlers are atoms
  according to incognito."
  ([S url peer-id]
   (client-connect! S url peer-id (atom {}) (atom {})))
  ([S url peer-id read-handlers write-handlers]
   (defonce singleton-http-client (ClientManager/createClient))
   (client-connect! S url peer-id read-handlers write-handlers singleton-http-client))
  ([S url peer-id read-handlers write-handlers http-client]
   (let [in-buffer (buffer 1024) ;; standard size
         in (chan in-buffer)
         out (chan)
         opener (chan)
         websockets (atom #{})
         host (.getHost (java.net.URL. (.replace url "ws" "http")))]
     ;; TODO this is only a temporary setting to allow large initial metadata payloads
     ;; we want to break them apart with a hitchhiker tree or similar datastructure
     (.put (.getProperties http-client) "org.glassfish.tyrus.incomingBufferSize"
           (* 100 1024 1024))
     (try
       (.connectToServer
        http-client
        (proxy [Endpoint] []
          (onOpen [session config]
            (info {:event :websocket-opened :websocket session :url url})
            (go-loop-super S [m (<? S out)] ;; ensure draining out on disconnect
              (if m
                (do
                  (if (@websockets session)
                    (do
                      (debug {:event :client-sending-message
                              :url url})
                      ;; special case: use websocket wire-level string type 
                      (if (= (:kabel/serialization m) :string)
                        @(.sendText (.getAsyncRemote session) (:kabel/payload m))
                        @(.sendBinary (.getAsyncRemote session)
                                      (ByteBuffer/wrap (to-binary m))))
                      #_(prn "cli send" m))
                    (warn {:event :dropping-msg-because-of-closed-channel
                           :url url :message m}))
                  (recur (<? S out)))
                (.close session)))
            (swap! websockets conj session)
            (async/put! opener [in out])
            (close! opener)

            (try
              (.addMessageHandler session
                                  (proxy [MessageHandlerString] []
                                    (onMessage [message]
                                      (try
                                        (when (> (count in-buffer) 100)
                                          (.close session)
                                          (throw (ex-info
                                                  (str "incoming buffer for " url
                                                       " too full:" (count in-buffer))
                                                  {:url url
                                                   :count (count in-buffer)})))
                                        (debug {:event :received-byte-message
                                                :url url
                                                :in-buffer-count (count in-buffer)})
                                        (async/put! in {:kabel/serialization :string
                                                        :kabel/payload message})
                                        (catch Exception e
                                          (let [e (ex-info "Cannot receive data." {:url url
                                                                                   :data message
                                                                                   :error e})]
                                            (error {:event :cannot-receive-message
                                                    :error e})
                                            (put! (-error S) e)
                                            (.close session)))))))
              (.addMessageHandler session
                                  (proxy [MessageHandlerBinary] []
                                    (onMessage [message]
                                      (try
                                        (when (> (count in-buffer) 100)
                                          (.close session)
                                          (throw (ex-info
                                                  (str "incoming buffer for " url
                                                       " too full:" (count in-buffer))
                                                  {:url url
                                                   :count (count in-buffer)})))
                                        (debug {:event :received-byte-message
                                                :url url
                                                :in-buffer-count (count in-buffer)})
                                        (let [m (from-binary (.array message))]
                                          (async/put! in (if (map? m)
                                                           (assoc m :kabel/host host)
                                                           m)))
                                        (catch Exception e
                                          (let [e (ex-info "Cannot receive data." {:url url
                                                                                   :data message
                                                                                   :error e})]
                                            (error {:event :cannot-receive-message
                                                    :error e})
                                            (put! (-error S) e)
                                            (.close session))))
                                      )))
              (catch java.io.IOException e
                (prn e))))
          (onClose [session reason]
            (let [e (ex-info "Connection closed!" {:reason reason})]
              (debug {:event :closing-connection :url url
                      :reason reason})
              (close! in)
              (go-try S (while (<! in))) ;; flush
              (swap! websockets disj session)
              (put! (-error S) e)
              (try (put! opener e) (catch Exception e))
              (close! opener)))
          (onError [session err]
            (let [e (ex-info "Websocket error."
                             {:type :websocket-connection-error
                              :url url
                              :error err})]
              (put! (-error S) e)
              (error {:event :websocket-error :url url :error err})
              (.close session))))
        cec
        (java.net.URI. url))
       (catch Exception e
         (error {:event :client-connect-error :url url :error e})
         (async/put! opener (ex-info "client-connect error"
                                     {:type :websocket-connection-error
                                      :url url
                                      :error e}))
         (close! in)
         (close! opener)))
     opener)))

(comment
  (def client (ClientManager/createClient))

  (.connectToServer
   client
   (proxy [Endpoint] []
     (onOpen [session config]
       (prn "opened")
       (try
         (clojure.pprint/pprint (clojure.reflect/reflect session))
         (.addMessageHandler session
                             (proxy [MessageHandler$Whole] []
                               (onMessage [message]
                                 (prn "Client received:" (from-binary (.array message))))))
         (.sendBinary (.getAsyncRemote session) (ByteBuffer/wrap (to-binary "Foo bar")))
         (catch java.io.IOException e
           (prn e)))))
   cec
   (java.net.URI. "ws://localhost:47291"))

(cli/websocket http-client url
                      :open (fn [ws]
                              (info {:event :websocket-opened :websocket ws :url url})
                              (go-loop-super S
                                             [m (<? S out)] ;; ensure draining out on disconnect
                                             (when m
                                               (if (@websockets ws)
                                                 (do
                                                   (debug {:event :client-sending-message
                                                           :url url})
                                                   (cli/send ws :byte (to-binary m))
                                                   #_(prn "cli send" m))
                                                 (warn {:event :dropping-msg-because-of-closed-channel
                                                        :url url :message m}))
                                               (recur (<? S out))))
                              (swap! websockets conj ws)
                              (async/put! opener [in out])
                              (close! opener))
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
                                        :url url
                                        :in-buffer-count (count in-buffer)})
                                ;; TODO add host
                                #_(prn "cli bytes")
                                (let [m (from-binary data)]
                                  (async/put! in (if (map? m)
                                                   (assoc m :kabel/host host)
                                                   m)))
                                (catch Exception e
                                  (let [e (ex-info "Cannot receive data." {:url url
                                                                           :data data
                                                                           :error e})]
                                    (error {:event :cannot-receive-message
                                            :error e})
                                    (put! (-error S) e)
                                    (.close ws)))))
                      :text (fn [ws ^String data]
                              (error {:event :string-not-supported
                                      :data data})
                              (put! (-error S) (ex-info "String data not supported."
                                                        {:data data})))
                      :close (fn [ws code reason]
                               (let [e (ex-info "Connection closed!" {:code code
                                                                      :reason reason})]
                                 (debug {:event :closing-connection :url url :code code
                                         :reason reason})
                                 (close! in)
                                 (go-try S (while (<! in))) ;; flush
                                 (swap! websockets disj ws)
                                 #_(put! (-error S) e)
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


(defn pong-middleware [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop-super S [i (<? S in)]
      (when i
        (prn "SERVER mirror" i)
        (>? S out i)
        (recur (<? S in))))
    [S peer [new-in new-out]]))


(require '[kabel.http-kit :as http-kit]
         '[kabel.peer :as peer])

(let [sid #uuid "fd0278e4-081c-4925-abb9-ff4210be271b"
      url "ws://localhost:47291"
      handler (http-kit/create-http-kit-handler! S url sid)]
  (def speer (peer/server-peer S handler sid pong-middleware)))


(<?? S (peer/start speer))
(<?? S (peer/stop speer))


  )


