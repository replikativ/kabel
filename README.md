# kabel

A minimal, modern connection library modelling a bidirectional wire to
pass clojure values between endpoints. Peers in Clojure and
ClojureScript are symmetric and hence allow symmetric cross-platform
implementations. At the moment web-sockets are used and transit is the
serialization format.

## Rational

Instead of implementing a `REST` interface websockets provide several
benefits, even if you do single requests. Most importantly the
distinction between server and client is unnecessary, because both can
push messages to each other, effectively having *one input* and *one
output* channel. Together with edn messages over the wire this
_simplifies_ the semantics significantly. The tradeoff is that `REST` is
standardized and offers better interoperablity for other clients.

Since we work on a crossplatform p2p software for distributed
datatypes with [replikativ](https://github.com/replikativ/replikativ),
we could not to reuse any of the other ClojureScript websocket
libraries. For us all IO happens over the input and output channel
with `core.async`, so we can implement cross-platform functionality in
a very terse and expressive fashion, e.g. in the [pull-hooks for
replikativ](https://github.com/replikativ/replikativ/blob/master/src/replikativ/p2p/hooks.cljc). But
you do not need to write platform neutral symmetric middlewares, so on
the JVM you can of course do IO without `core.async`.

We also extended and build on
[full.async](https://github.com/fullcontact/full.monty/) to catch all
exceptions in an Erlang style monitoring fashion and propagate them
back through a parametrizable error channel.

## Usage

Add this to your project dependencies:

[![Clojars Project](http://clojars.org/io.replikativ/kabel/latest-version.svg)](http://clojars.org/io.replikativ/kabel)

From [pingpong.clj](./examples/pingpong.clj):

~~~ clojure
(ns kabel.examples.pingpong
  (:require [kabel.platform :refer [create-http-kit-handler! start stop]]
            [kabel.peer :refer [client-peer server-peer connect]]
            [kabel.platform-log :refer [info warn error]]
            [kabel.middleware.log :refer [logger]]

            [full.async :refer [go-try go-loop-try> <? <??]]
            [clojure.core.async :refer [go-loop <! >! >!! timeout chan]]))

;; track errors (this happens through full.async recursively through
;; all participating subsystems)
(def err-ch (chan))
(go-loop [e (<! err-ch)]
          (when e
            (warn "ERROR:" e)
            (recur (<! err-ch))))

(def handler (create-http-kit-handler! "ws://127.0.0.1:9090/" err-ch))

(defn pong-middleware [[peer [in out]]]
  (let [new-in (chan)]
    (go-loop [p (<! in)]
      (when p
        (info "received ping, sending pong")
        (>! out {:type :pong})
        (>! new-in p) ;; pass along (or not...)
        (recur (<! in))))
    [peer [new-in out]]))

(def log-atom (atom {}))

(def remote-peer (server-peer handler "REMOTE" err-ch
                              (comp pong-middleware
                                    (partial logger log-atom :server))))

(start remote-peer)
#_(stop remote-peer)



(defn ping [[peer [in out]]]
  (go-loop []
    (<! (timeout 5000))
    (>! out {:type :ping})
    (recur))
  [peer [in out]])

(def local-peer (client-peer "CLIENT" err-ch ping))

(connect local-peer "ws://127.0.0.1:9090/")

(comment
  ;; inspect server side message exchange through logging middleware
  @log-atom

  )


~~~

The client-side works the same in ClojureScript from the browser.

## Middlewares

You can find general middlewares in the corresponding
folder. In general they themselves form a "wire" and can filter,
transform, inject and pass through messages. Useful middlewares still
missing:

- authentication, e.g. two-factor over e-mail
- qos monitoring, latency and throughput measures
- remote debugging, sending full.async exceptions back to the server
- other usefull `ring` middlewares which should be ported?  - ...

## TODO
- factor platform neutral logging
- implement node.js websocket server

## License

Copyright © 2015 Konrad Kühne, Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
