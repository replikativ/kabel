(ns kabel.http-kit
  "http-kit specific IO operations.

  The implementation moved to `kabel.ring-ws`, which is written against
  `ring.websocket.protocols` and takes the server as an argument. This
  namespace supplies http-kit's, and http-kit remains kabel's default: 207 KB
  with no transitive runtime dependencies, virtual-threaded by default on
  JDK 21+, and native-image tested on nine platform combinations.

  Keeping this name means the sixteen call sites across kabel and its consumers
  did not have to change."
  (:require [kabel.ring-ws :as ring-ws]
            [org.httpkit.server :as http-kit]))

(defn create-http-kit-handler!
  "As `kabel.ring-ws/create-ws-handler!`, with http-kit as the server.

  Creates a server handler described by url, e.g. wss://myhost:8443/replikativ/ws.
  Returns a map to run a peer with a platform specific server handler
  under :handler. read-handlers and write-handlers are atoms according to
  incognito.

  Optional hooks (passed as a trailing options map):
    :on-connect    (fn [request] context-or-nil) — invoked once per WS
                   connection; the returned value is stashed per-connection
                   and passed back to :annotate-msg. Useful for one-time
                   auth/validation. Exceptions are caught and treated as
                   nil context.
    :annotate-msg  (fn [msg request ctx] -> msg) — invoked on every inbound
                   message (after deserialisation and the default
                   :kabel/host annotation) so callers can decorate
                   messages with extra fields. Defaults to identity.
    :server-opts   merged into http-kit's `run-server` options."
  ([S url peer-id]
   (create-http-kit-handler! S url peer-id (atom {}) (atom {}) {}))
  ([S url peer-id read-handlers write-handlers]
   (create-http-kit-handler! S url peer-id read-handlers write-handlers {}))
  ([S url peer-id read-handlers write-handlers opts]
   (ring-ws/create-ws-handler! S url peer-id read-handlers write-handlers
                               (assoc opts :run-server http-kit/run-server))))
