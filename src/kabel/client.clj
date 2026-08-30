(ns kabel.client
  "tyrus client specific client IO operations."
  (:require [replikativ.logging :as log]
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
           [org.replikativ.kabel MessageHandlerString MessageHandlerBinary
            PerMessageDeflateExtension]
           [org.glassfish.tyrus.client ClientManager]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

;; Example taken from https://tyrus.java.net/documentation/1.13.1/index/getting-started.html

(def ^:dynamic *compression?*
  "Whether to offer RFC 7692 `permessage-deflate` on outgoing connections.

  On by default. kabel's traffic is a stream of structurally similar messages
  over a long-lived connection, which is the shape the extension's context
  takeover exists for -- message N is compressed against messages 1..N-1.

  Tyrus ships no implementation of the extension, so this is
  `PerMessageDeflateExtension`, ours. The peer decides: a server that does not
  accept the offer leaves the connection uncompressed, and nothing else
  changes. http-kit accepts it from 2.9.0 (http-kit/http-kit#617); browsers
  offer it to us in the other direction automatically.

  Bind to false when payloads are already compressed, or when per-connection
  memory matters more than bandwidth -- context takeover keeps a deflate and an
  inflate window alive for the life of each connection."
  true)

(def default-max-frame-bytes
  "Default WebSocket application-message ceiling, before compression and after
  decompression. The four-byte Kabel binary codec prefix is included."
  (* 5 1024 1024))

(def default-out-buffer-items
  "Encoded messages retained by the raw client transport in addition to its
  single completion-awaited socket write."
  16)

(def ^:dynamic *max-frame-bytes* default-max-frame-bytes)
(def ^:dynamic *out-buffer-items* default-out-buffer-items)

(defn- text-bytes [^String value]
  (alength (.getBytes value StandardCharsets/UTF_8)))

(defn- check-frame-size! [url direction size max-frame-bytes]
  (when (> size max-frame-bytes)
    (throw (ex-info "WebSocket application message exceeds Kabel's limit"
                    {:type :kabel/frame-too-large
                     :url url :direction direction
                     :bytes size :max-bytes max-frame-bytes}))))

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

(defn- client-endpoint-config
  "Build the endpoint config for one connection.

  Built per connect, not once at namespace load: `cec` is a `def`, so it
  captured `*compression?*` at load time and binding the var around
  `client-connect!` -- exactly what its docstring tells you to do -- had no
  effect whatsoever."
  [max-frame-bytes]
  (let [b (ClientEndpointConfig$Builder/create)]
    (.configurator b (.getConfigurator ^ClientEndpointConfig cec))
    (when *compression?*
      (.extensions b (PerMessageDeflateExtension/offer max-frame-bytes)))
    (.build b)))

(def ^:dynamic *max-buffer-size* 10000)

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
   (let [max-frame-bytes *max-frame-bytes*
         out-buffer-items *out-buffer-items*
         in-buffer (buffer 1024) ;; standard size
         in (chan in-buffer)
         out (chan (buffer out-buffer-items))
         opener (chan)
         websockets (atom #{})
         host (.getHost (java.net.URL. (.replace url "ws" "http")))]
     (.put (.getProperties http-client) "org.glassfish.tyrus.incomingBufferSize"
           max-frame-bytes)
     (try
       (.connectToServer
        http-client
        (proxy [Endpoint] []
          (onOpen [session config]
            (log/info :websocket-opened {:url url})
            (.setMaxBinaryMessageBufferSize session max-frame-bytes)
            (.setMaxTextMessageBufferSize session max-frame-bytes)
            (go-loop-super S [m (<? S out)] ;; ensure draining out on disconnect
                           (if m
                             (do
                               (if (@websockets session)
                                 (try
                                   (log/debug :client-sending-message {:url url})
                                   ;; Awaiting the Tyrus future is deliberate:
                                   ;; only one socket write may be outstanding.
                                   (if (= (:kabel/serialization m) :string)
                                     (let [payload (:kabel/payload m)]
                                       (check-frame-size! url :out
                                                          (text-bytes payload)
                                                          max-frame-bytes)
                                       @(.sendText (.getAsyncRemote session) payload))
                                     (let [payload (to-binary m)]
                                       (check-frame-size! url :out
                                                          (alength payload)
                                                          max-frame-bytes)
                                       @(.sendBinary (.getAsyncRemote session)
                                                     (ByteBuffer/wrap payload))))
                                   (catch Exception e
                                     (log/error :cannot-send-message
                                                {:url url :error (pr-str e)})
                                     (put! (-error S) e)
                                     (close! out)
                                     (.close session)))
                                 (log/warn :dropping-msg-because-of-closed-channel {:url url :message m}))
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
                                        (check-frame-size! url :in
                                                           (text-bytes message)
                                                           max-frame-bytes)
                                        (when-not
                                         (async/offer!
                                          in {:kabel/serialization :string
                                              :kabel/payload message})
                                          (throw (ex-info "Incoming Kabel queue is full"
                                                          {:type :kabel/inbound-overloaded
                                                           :url url})))
                                        (catch Exception e
                                          (let [e (ex-info "Cannot receive data." {:url url
                                                                                   :data message
                                                                                   :error e})]
                                            (log/error :cannot-receive-message {:error e})
                                            (put! (-error S) e)
                                            (.close session)))))))
              (.addMessageHandler session
                                  (proxy [MessageHandlerBinary] []
                                    (onMessage [message]
                                      (try
                                        (let [^ByteBuffer source (.duplicate
                                                                  ^ByteBuffer message)
                                              size (.remaining source)
                                              bytes (byte-array size)]
                                          (check-frame-size! url :in size
                                                             max-frame-bytes)
                                          (.get source bytes)
                                          (let [m (from-binary bytes)
                                                admitted
                                                (async/offer!
                                                 in (if (map? m)
                                                      (assoc m :kabel/host host)
                                                      m))]
                                            (when-not admitted
                                              (throw
                                               (ex-info "Incoming Kabel queue is full"
                                                        {:type :kabel/inbound-overloaded
                                                         :url url})))))
                                        (catch Exception e
                                          (let [e (ex-info "Cannot receive data." {:url url
                                                                                   :data message
                                                                                   :error e})]
                                            (log/error :cannot-receive-message {:error e})
                                            (put! (-error S) e)
                                            (.close session)))))))
              (catch java.io.IOException e
                (log/error :unexpected-ioexception {:error (pr-str e)})
                (put! (-error S) e))))
          (onClose [session reason]
            (let [e (ex-info "Connection closed!" {:reason reason})]
              (log/debug :closing-connection {:url url :reason (pr-str reason)})
              (close! in)
              (close! out)
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
              (log/error :websocket-error {:url url :error (pr-str err)})
              (.close session))))
        (client-endpoint-config max-frame-bytes)
        (java.net.URI. url))
       (catch Exception e
         (log/error :client-connect-error {:url url :error (pr-str e)})
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
  (<?? S (peer/stop speer)))
