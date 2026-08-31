(ns kabel.ring-ws
  "Server-side WebSocket IO, written against `ring.websocket.protocols` rather
  than any one server.

  This replaces the http-kit-specific handler, for two reasons.

  **It fixes a live bug.** The old code used `org.httpkit.server/with-channel`,
  which http-kit deprecated in v2.4.0 (2020-07-30) with the docstring \"this
  macro has potential race conditions, Ref. #318\". The race is real for kabel:
  `with-channel` hands you a channel and then you attach `on-receive` to it, so
  a client that sends immediately after the upgrade can have its first message
  arrive before the handler exists. A Ring listener has no such window -- the
  listener is supplied AS PART OF the upgrade response, so it is installed
  before any frame can be delivered.

  **It makes the server a choice.** http-kit implements these protocols (2.8.0+,
  contributed by Ring's own maintainer), and so do both Jetty adapters. Callers
  inject `run-server`; nothing here mentions a server. `kabel.http-kit` supplies
  http-kit's, and remains the default.

  The dependency is `org.ring-clojure/ring-websocket-protocols` -- 3.8 KB whose
  only dependency is Clojure at test scope -- not `ring/ring-core`."
  (:require [replikativ.logging :as log]
            [kabel.binary :refer [from-binary to-binary]]
            [superv.async :refer [<? go-try -error go-loop-super]]
            [ring.websocket.protocols :as wsp]
            [clojure.core.async :as async
             :refer [<! chan put! close! buffer]]))

(def default-max-frame-bytes
  "Default WebSocket application-message ceiling. Binary size includes Kabel's
  four-byte serialization prefix; text is counted as UTF-8."
  (* 5 1024 1024))

(def default-out-buffer-items
  "Encoded messages retained by a raw server transport in addition to its one
  completion-awaited socket write."
  16)

(defn- encoded-frame [message max-frame-bytes]
  (let [[data size]
        (if (= (:kabel/serialization message) :string)
          (let [payload (:kabel/payload message)]
            [payload (alength (.getBytes ^String payload
                                         java.nio.charset.StandardCharsets/UTF_8))])
          (let [payload (to-binary message)]
            [(java.nio.ByteBuffer/wrap payload) (alength payload)]))]
    (when (> size max-frame-bytes)
      (throw (ex-info "WebSocket application message exceeds Kabel's limit"
                      {:type :kabel/frame-too-large
                       :direction :out :bytes size
                       :max-bytes max-frame-bytes})))
    data))

(defn- send-async! [socket message max-frame-bytes]
  (let [completion (chan 1)]
    (try
      (let [data (encoded-frame message max-frame-bytes)]
        (if (satisfies? wsp/AsyncSocket socket)
          (wsp/-send-async socket data
                           #(async/offer! completion {:ok true})
                           #(async/offer! completion {:error %}))
          (do
            (wsp/-send socket data)
            (async/offer! completion {:ok true}))))
      (catch Exception e
        (async/offer! completion {:error e})))
    completion))

(defn- port-of
  "The port from a ws:// or wss:// url. Unchanged from the http-kit handler,
  including its tolerance of a url with no explicit port (nil, which the server
  then rejects) -- callers pass a port today and changing that is not this
  namespace's business."
  [url]
  (some-> (re-seq #":(\d+)" url) first second read-string))

(defn create-ws-handler!
  "A Ring WebSocket handler for `url`, e.g. wss://myhost:8443/replikativ/ws.

  Returns the same map the http-kit handler always returned -- `:new-conns`,
  `:channel-hub`, `:context-hub`, `:start-fn`, `:url`, `:handler` -- so peers
  and tests are unaffected.

  `read-handlers`/`write-handlers` are accepted and unused, as before; the wire
  codec is middleware's concern.

  Options:
    :run-server    (fn [handler opts] -> stop-fn), REQUIRED by `:start-fn`.
                   Injecting it is what keeps this namespace server-agnostic.
    :server-opts   merged into the map passed to `run-server`.
    :on-connect    (fn [request] -> context-or-nil), once per connection; the
                   result is stashed and handed to `:annotate-msg`. Exceptions
                   are logged and treated as nil.
    :annotate-msg  (fn [msg request ctx] -> msg), on every inbound message
                   after deserialisation and the default `:kabel/host`
                   annotation. Defaults to identity.
    :max-frame-bytes WebSocket application-message ceiling (default 5 MiB).
    :out-buffer-items encoded messages queued per socket (default 16); one
                   additional write may be in flight.

  The socket doubles as the `channel-hub` key, as the http-kit channel did."
  ([S url peer-id]
   (create-ws-handler! S url peer-id (atom {}) (atom {}) {}))
  ([S url peer-id read-handlers write-handlers]
   (create-ws-handler! S url peer-id read-handlers write-handlers {}))
  ([S url _peer-id _read-handlers _write-handlers
    {:keys [on-connect annotate-msg run-server server-opts max-frame-bytes
            out-buffer-items]
     :or {on-connect (constantly nil)
          annotate-msg (fn [msg _req _ctx] msg)
          max-frame-bytes default-max-frame-bytes
          out-buffer-items default-out-buffer-items
          server-opts {}}}]
   (let [channel-hub (atom {})
         context-hub (atom {})
         conns (chan)
         handler
         (fn [request]
           (let [in-buffer (buffer 1024)
                 in (chan in-buffer)
                 out (chan (buffer out-buffer-items))]
             ;; The third entry is additive: legacy consumers destructuring
             ;; `[in out]` keep working, while `kabel.peer/server-peer` uses
             ;; these facts to seed a connection-local transport context.
             (async/put! conns
                         [in out {:kabel.transport/transport :websocket
                                  :kabel.transport/remote-address
                                  (:remote-addr request)}])
             {:ring.websocket/listener
              (reify wsp/Listener
                (on-open [_ socket]
                  ;; Everything that `with-channel` did AFTER handing back a
                  ;; live channel happens here instead, and the listener is
                  ;; already installed -- so `on-message` cannot run first.
                  (swap! channel-hub assoc socket request)
                  (when-let [ctx (try (on-connect request)
                                      (catch Exception e
                                        (log/warn :on-connect-error {:error (str e)})
                                        nil))]
                    (swap! context-hub assoc socket ctx))
                  ;; Send loop: pumps server->client messages from `out`.
                  ;; Exits on (a) `out` closed, or (b) the socket no longer in
                  ;; `channel-hub` -- the peer's close ran before upstream
                  ;; closed `out`. Case (b) used to log per message and keep
                  ;; looping, leaking a goroutine until JVM exit; it logs once
                  ;; and closes `out` so upstream `put!`s return false and the
                  ;; broker unsubscribes.
                  (go-loop-super S [m (<? S out)]
                                 (if m
                                   (if (@channel-hub socket)
                                     (let [result
                                           (<! (send-async! socket m
                                                            max-frame-bytes))]
                                       (if (= true (:ok result))
                                         (recur (<? S out))
                                         (do
                                           (log/warn :websocket-send-failed
                                                     {:url url
                                                      :error (str (:error result))})
                                           (put! (-error S) (:error result))
                                           (close! out)
                                           (wsp/-close
                                            socket
                                            (if (= :kabel/frame-too-large
                                                   (:type (ex-data
                                                           (:error result))))
                                              1009 1011)
                                            "kabel: send failed"))))
                                     (do (log/warn :dropping-msg-because-of-closed-channel
                                                   {:url url})
                                         (close! out)))
                                   (wsp/-close socket 1000 "kabel: out channel closed"))))

                (on-message [_ socket data]
                  (let [host (:remote-addr request)
                        ctx (@context-hub socket)]
                    (try
                      (log/debug :received-byte-message {})
                      ;; Only binary associative messages get :kabel/host by
                      ;; default; string messages stay plain. annotate-msg can
                      ;; add more per caller policy.
                      ;;
                      ;; Ring hands binary frames as a ByteBuffer where http-kit
                      ;; gave a byte[]; `from-binary` is fed a byte[] either way.
                      (let [text? (string? data)
                            bs (when-not text?
                                 (if (instance? java.nio.ByteBuffer data)
                                   (let [^java.nio.ByteBuffer b data
                                         a (byte-array (.remaining b))]
                                     (.get b a)
                                     a)
                                   data))
                            size (if text?
                                   (alength (.getBytes ^String data
                                                       java.nio.charset.StandardCharsets/UTF_8))
                                   (alength ^bytes bs))
                            _ (when (> size max-frame-bytes)
                                (throw
                                 (ex-info "WebSocket application message exceeds Kabel's limit"
                                          {:type :kabel/frame-too-large
                                           :direction :in :bytes size
                                           :max-bytes max-frame-bytes})))
                            base (if text?
                                   {:kabel/serialization :string :kabel/payload data}
                                   (from-binary bs))
                            with-host (if (and (not text?) (associative? base))
                                        (assoc base :kabel/host host)
                                        base)]
                        (when-not (async/offer!
                                   in (annotate-msg with-host request ctx))
                          (throw (ex-info "Incoming Kabel queue is full"
                                          {:type :kabel/inbound-overloaded
                                           :url url}))))
                      (catch Exception e
                        (put! (-error S)
                              (ex-info "Cannot receive data."
                                       {:data data :host host :error e}))
                        (wsp/-close socket
                                    (if (= :kabel/frame-too-large
                                           (:type (ex-data e)))
                                      1009 1013)
                                    "kabel: receive rejected")))))

                (on-pong [_ _socket _data] nil)

                (on-error [_ _socket e]
                  (log/warn :websocket-error {:url url :error (str e)}))

                (on-close [_ socket code reason]
                  (log/debug :channel-closed {:host (:remote-addr request)
                                              :code code :reason reason})
                  (swap! channel-hub dissoc socket)
                  (swap! context-hub dissoc socket)
                  (go-try S (while (<! in)))      ; flush
                  (close! in)
                  ;; Close `out` so the send loop exits and upstream broadcasts
                  ;; stop landing on a dead subscription.
                  (close! out)))}))]
     {:new-conns conns
      :channel-hub channel-hub
      :context-hub context-hub
      :start-fn (fn start-fn [{:keys [handler] :as volatile}]
                  (when-not (:stop-fn handler)
                    (when-not run-server
                      (throw (ex-info "kabel: no :run-server supplied — pass one, or use kabel.http-kit"
                                      {:url url})))
                    (-> volatile
                        (assoc :stop-fn
                               (run-server handler
                                           (merge {:port (port-of url)
                                                   :max-body max-frame-bytes
                                                   :max-ws max-frame-bytes}
                                                  server-opts))))))
      :url url
      :handler handler})))
