# kabel

kabel (German for cable/wire) is a minimal, modern connection library
modelling a bidirectional wire to pass Clojure values between
endpoints. Peers in Clojure and ClojureScript are symmetric and hence
allow symmetric cross-platform implementations. Clojure peers can
connect to Clojure and ClojureScript peers in the same way and vice
versa. kabel uses web-sockets and transit is the serialization format.


## Rationale

Instead of implementing a `REST` interface websockets provide several
benefits, even if you do single requests. Most importantly the
distinction between server and client is unnecessary, because both can
push messages to each other, effectively having *one input* and *one
output* channel. Together with `edn` messages over the wire this
_simplifies_ the semantics significantly. The tradeoff is that `REST` is
standardized and offers better interoperablity for other clients.

Since we work on a crossplatform `p2p` software for confluent
replicated datatypes with
[replikativ](https://github.com/replikativ/replikativ), we could not
reuse any of the other ClojureScript client-side only websocket
libraries, e.g. [sente](https://github.com/ptaoussanis/sente) or
[chord](https://github.com/jarohen/chord). For us _all_ IO happens
over the input and output channel with `core.async`, so we can
implement *cross-platform* functionality in a very terse and
expressive fashion, e.g. in the [pull-hooks for
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
  (:require [kabel.http-kit :refer [create-http-kit-handler! start stop]]
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
        (>! out {:type :pong}) ;; messages need to be maps
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

~~~

The client-side works the same in ClojureScript from the browser.

## Design

![Example pub-sub architecture of replikativ](./peering.png)

There is a pair of channels for each connection, but at the core the
peer has a `pub-sub` architecture. Besides using some middleware
specific shared memory like a database you can more transparently pass
messages to other clients and middlewares through this pub-sub core or
subscribe to specific message types on it. It uses the pub-sub
semantics of `core.async`:

~~~ clojure
(let [[bus-in bus-out] (get-in @peer [:volatile :chans])
      b-chan (chan)]
  (async/sub bus-out :broadcast b-chan)
  (async/put! bus-in {:type :broadcast :hello :everybody})
  (<!! b-chan))
~~~


## Middlewares

You can find general middlewares in the corresponding folder. In
general middlewares themselves form a "wire" and can filter,
transform, inject and pass through messages.

We provide the following middlewares separately: - [Passwordless
authentication (and
authorisation)](https://github.com/replikativ/kabel-auth) based on
email verification or password and inter-peer trust network as p2p
middleware.

Useful middlewares still missing:
- QoS monitoring, latency and throughput measures
- remote debugging, sending full.async exceptions back to the server
- other usefull `ring` middlewares which should be ported?  - ...

## Connectivity

More transport alternatives like long-polling with
SSEs, WebRTC or normal sockets should not be hard to add.


## TODO
- factor platform neutral logging
- implement node.js websocket server

## Changelog

### 0.1.7
    - support node on client-side
    - add an aleph http client (server still missing)
    - add a generic callback middleware

### 0.1.5
    - use lightweight slf4j logging

### 0.1.4
    - expose consistent host ip

### 0.1.3
    - do not initialize http-client on compile time
      fixes aot uberjar compilation

### 0.1.2
    - properly close cljs client connection on initial error
    - add :sender peer-id to outgoing messages
    - add :connection url to incoming messages

## License

Copyright © 2015-2016 Christian Weilbach, 2015 Konrad Kühne

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
