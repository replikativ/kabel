(ns kabel.jetty
  "Jetty 12 specific IO operations.

  The implementation is `kabel.ring-ws`; this supplies Jetty's server and
  translates the option names. Use it when you want what http-kit does not
  offer: TLS termination in-process, HTTP/2, connection caps, idle timeouts,
  request-rate limiting, or metrics.

  `ring-jetty9-adapter` is a PROVIDED dependency -- kabel does not declare it,
  because http-kit remains the default and a peer that never touches Jetty
  should not carry it. Requiring this namespace without it on the classpath
  fails at load, which is the intent. Add:

      info.sunng/ring-jetty9-adapter {:mvn/version \"0.40.2\"}

  That adapter rather than `ring/ring-jetty-adapter`: the official one routes
  through Jetty's ee9 legacy servlet environment and hardcodes HTTP/1.1, while
  this tracks Jetty 12 core and exposes HTTP/2 and HTTP/3 as options.

  Jetty also ships permessage-deflate ON by default, negotiated with no
  configuration -- which http-kit does not (http-kit/http-kit#617)."
  (:require [kabel.ring-ws :as ring-ws]
            [ring.adapter.jetty9 :as jetty]))

(defn jetty-run-server
  "Jetty in the `(fn [handler opts] -> stop-fn)` shape `create-ws-handler!`
  expects.

  Three translations, each of which a naive pass-through gets wrong:

  - `:join? false`, or `run-jetty` blocks the calling thread forever.
  - http-kit's `:max-ws` becomes Jetty's `:ws-max-binary-message-size` and
    `:ws-max-text-message-size`; `:max-body` has no Jetty equivalent here and
    is dropped rather than passed through to be ignored silently.
  - the returned stop takes VARARGS, because kabel calls
    `(stop-fn :timeout 1000)`. http-kit's stop is `(fn [& {:keys [timeout]}])`
    and swallows that; a fixed-arity stop throws ArityException.

  Anything in `:server-opts` that is not one of ours is passed through, so
  Jetty's own options -- `:ssl?`, `:h2?`, `:max-idle-time`, `:thread-pool` --
  work by naming them."
  [handler {:keys [port max-ws] :as opts}]
  (let [server (jetty/run-jetty
                handler
                (merge {:port port
                        :join? false
                        :ws-max-binary-message-size (or max-ws (* 100 1024 1024))
                        :ws-max-text-message-size (or max-ws (* 100 1024 1024))}
                       (dissoc opts :port :max-ws :max-body)))]
    (fn stop [& _opts] (jetty/stop-server server))))

(defn create-jetty-handler!
  "As `kabel.ring-ws/create-ws-handler!`, with Jetty as the server.

  Same signature and same return map as `kabel.http-kit/create-http-kit-handler!`
  -- `:new-conns`, `:channel-hub`, `:context-hub`, `:start-fn`, `:url`,
  `:handler` -- so swapping servers is a one-line change at the call site.

  Options are `create-ws-handler!`'s; `:server-opts` reaches Jetty directly."
  ([S url peer-id]
   (create-jetty-handler! S url peer-id (atom {}) (atom {}) {}))
  ([S url peer-id read-handlers write-handlers]
   (create-jetty-handler! S url peer-id read-handlers write-handlers {}))
  ([S url peer-id read-handlers write-handlers opts]
   (ring-ws/create-ws-handler! S url peer-id read-handlers write-handlers
                               (assoc opts :run-server jetty-run-server))))
