(ns kabel.http-kit
  "http-kit specific IO operations."
  (:require [replikativ.logging :as log]
            [kabel.binary :refer [from-binary to-binary]]
            [superv.async :refer [<? <?? go-try -error go-loop-super]]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! put! close! buffer]]
            [org.httpkit.server :refer :all]
            [cognitect.transit :as transit]))

(defn create-http-kit-handler!
  "Creates a server handler described by url, e.g. wss://myhost:8443/replikativ/ws.
  Returns a map to run a peer with a platform specific server handler
  under :handler.  read-handlers and write-handlers are atoms
  according to incognito.

  Optional hooks (passed as a trailing options map):
    :on-connect    (fn [request] context-or-nil) — invoked once per WS
                   connection; the returned value is stashed per-connection
                   and passed back to :annotate-msg. Useful for one-time
                   auth/validation. Exceptions are caught and treated as
                   nil context.
    :annotate-msg  (fn [msg request ctx] -> msg) — invoked on every inbound
                   message (after deserialisation and the default
                   :kabel/host annotation) so callers can decorate
                   messages with extra fields. Defaults to identity."
  ([S url peer-id]
   (create-http-kit-handler! S url peer-id (atom {}) (atom {}) {}))
  ([S url peer-id read-handlers write-handlers]
   (create-http-kit-handler! S url peer-id read-handlers write-handlers {}))
  ([S url peer-id _read-handlers _write-handlers
    {:keys [on-connect annotate-msg]
     :or {on-connect (constantly nil)
          annotate-msg (fn [msg _req _ctx] msg)}}]
   (let [channel-hub (atom {})
         context-hub (atom {})  ;; per-channel connection context from on-connect
         conns (chan)
         handler (fn [request]
                   (let [in-buffer (buffer 1024) ;; standard size
                         in (chan in-buffer)
                         out (chan)]
                     (async/put! conns [in out])
                     (with-channel request channel
                       (swap! channel-hub assoc channel request)
                       (when-let [ctx (try (on-connect request)
                                           (catch Exception e
                                             (log/warn :on-connect-error {:error (str e)})
                                             nil))]
                         (swap! context-hub assoc channel ctx))
                       ;; Send loop: pumps server→client messages from `out`
                       ;; to the underlying http-kit websocket. Exits on
                       ;;   (a) `out` closed (channel returns nil), or
                       ;;   (b) the WS channel no longer registered in
                       ;;       `channel-hub` (the client's `on-close` ran
                       ;;       before `out` had been closed by upstream).
                       ;; Case (b) used to log per-message and keep
                       ;; looping, leaking a goroutine and spamming the
                       ;; warning until JVM exit. Now we log once and
                       ;; close `out` so upstream `put!`s return false
                       ;; and the broker can unsubscribe.
                       (go-loop-super S [m (<? S out)]
                                      (if m
                                        (if (@channel-hub channel)
                                          (do (log/debug :sending-msg {})
                                              (if (= (:kabel/serialization m) :string)
                                                (send! channel (:kabel/payload m))
                                                (send! channel (to-binary m)))
                                              (recur (<? S out)))
                                          (do (log/warn :dropping-msg-because-of-closed-channel
                                                        {:url url})
                                              (close! out)))
                                        (close channel)))
                       (on-close channel (fn [status]
                                           (let [e (ex-info "Connection closed!" {:status status})
                                                 host (:remote-addr request)]
                                             (log/debug :channel-closed {:host host :status status})
                                             #_(put! (-error S) e))
                                           (swap! channel-hub dissoc channel)
                                           (swap! context-hub dissoc channel)
                                           (go-try S (while (<! in))) ;; flush
                                           (close! in)
                                           ;; Close `out` so the send
                                           ;; loop exits and upstream
                                           ;; broadcasts stop landing on
                                           ;; a dead subscription.
                                           (close! out)))
                       (on-receive channel (fn [data]
                                             (let [host (:remote-addr request)
                                                   ctx (@context-hub channel)]
                                               (try
                                                 (log/debug :received-byte-message {})
                                                 (when (> (count in-buffer) 100)
                                                   (close channel)
                                                   (throw (ex-info
                                                           (str "incoming buffer for " (:remote-addr request)
                                                                " too full:" (count in-buffer))
                                                           {:url url
                                                            :count (count in-buffer)})))
                                                 #_(prn "hk rec" (mapv char data))
                                                 ;; Preserve original behaviour: only binary
                                                 ;; associative messages get :kabel/host by
                                                 ;; default; string messages stay plain.
                                                 ;; annotate-msg can add extra fields (e.g.
                                                 ;; :kabel/principal, or :kabel/host on
                                                 ;; string messages) per-caller policy.
                                                 (let [base (if (string? data)
                                                              {:kabel/serialization :string
                                                               :kabel/payload data}
                                                              (from-binary data))
                                                       with-host (if (and (not (string? data))
                                                                          (associative? base))
                                                                   (assoc base :kabel/host host)
                                                                   base)
                                                       annotated (annotate-msg with-host request ctx)]
                                                   (async/put! in annotated))
                                                 (catch Exception e
                                                   (put! (-error S)
                                                         (ex-info "Cannot receive data." {:data data
                                                                                          :host host
                                                                                          :error e}))
                                                   (close channel)))))))))]
     {:new-conns conns
      :channel-hub channel-hub
      :context-hub context-hub
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
                                            ;; TODO this is only a temporary setting to allow large initial metadata payloads
                                            ;; we want to break them apart with a hitchhiker tree or similar datastructure
                                            :max-body (* 100 1024 1024)
                                            :max-ws (* 100 1024 1024)})))))
      :url url
      :handler handler})))






