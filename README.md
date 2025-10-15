# kabel [![CircleCI](https://circleci.com/gh/replikativ/kabel.svg?style=svg)](https://circleci.com/gh/replikativ/kabel)

kabel (German for cable/wire) is a minimal, modern connection library modelling
a bidirectional wire to pass Clojure values between peers. Peers in Clojure and
ClojureScript are symmetric and hence allow symmetric cross-platform
implementations. Clojure peers can connect to Clojure and ClojureScript peers in
the same way and vice versa. kabel can use any bidirectional messaging channel,
currently it supports web-sockets. It ships multiple serialization middlewares
including [transit](https://github.com/cognitect/transit-format) and
[fressian](https://github.com/clojure/data.fressian) for efficient serialization.
It works on different JavaScript runtimes, currently tested are the Browser,
node.js and React-Native.


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

We also extended and build
on [superv.async](https://github.com/replikativ/superv.async/) to catch all
exceptions in an Erlang style monitoring fashion and propagate them back through
a parametrizable error channel. We are thinking about ways to refactor kabel, so
that it can be decoupled from this error handling without losing the error
handling guarantees.

## Usage

Add this to your project dependencies:

[![Clojars Project](http://clojars.org/io.replikativ/kabel/latest-version.svg)](http://clojars.org/io.replikativ/kabel)

### Build

The project uses deps.edn and tools.build. To compile the Java helper classes:

```clojure
clj -T:build compile-java
```

Run the example:

```clojure
clj -M:pingpong
```

### Example

From the `examples` folder (there is also a cljs client there):

~~~ clojure
(ns kabel.examples.pingpong
  (:require [kabel.client :as cli]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [clojure.core.async :refer [chan]]
            ;; you can use below transit if you prefer
            [kabel.middleware.transit :refer [transit]]
            [hasch.core :refer [uuid]]))


;; this url is needed for the server to open the proper
;; socket and for the client to know where to connect to
(def url "ws://localhost:47291")


;; server peer code
(defn pong-middleware [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    ;; we just mirror the messages back
    (go-loop-try S [i (<? S in)]
      (when i
        (>? S out i)
        (recur (<? S in))))
    ;; Note that we pass through the supervisor, peer and new channels
    [S peer [new-in new-out]]))

;; this is useful to track messages, so each peer should have a unique id
(def server-id #uuid "05a06e85-e7ca-4213-9fe5-04ae511e50a0")

(def server (peer/server-peer S (http-kit/create-http-kit-handler! S url server-id) server-id
                              ;; here you can plug in your (composition of) middleware(s)
                              pong-middleware
                              ;; we chose no serialization (pr-str/read-string by default)
                              identity
                              ;; we could also pick the transit middleware
                              #_transit))

;; we need to start the peer to open the socket
(<?? S (peer/start server))


(def client-id #uuid "c14c628b-b151-4967-ae0a-7c83e5622d0f")

;; client
(def client (peer/client-peer S client-id
                              ;; Here we have a simple middleware to trigger some roundtrips
                              ;; from the client
                              (fn [[S peer [in out]]]
                                (let [new-in (chan)
                                      new-out (chan)]
                                  (go-try S
                                          (put? S out "ping")
                                          (println "1. client incoming message:" (<? S in))
                                          (put? S out "ping2")
                                          (println "2. client incoming message:" (<? S in)))
                                  [S peer [new-in new-out]]))
                              ;; we need to pick the same middleware for serialization
                              ;; (no auto-negotiation yet)
                              identity
                              #_transit))


;; let's connect the client to the server
(<?? S (peer/connect S client url))


(comment
  ;; and stop the server
  (<?? S (peer/stop speer))
  )
~~~

The client-side works the same in ClojureScript from the browser.

## Applications

- [replikativ](https://github.com/replikativ/replikativ)
- [polo-collector](https://github.com/replikativ/polo-collector)

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

### Serialization

The following serialization middlewares are provided:
- **Transit** - Efficient binary serialization (JSON or MessagePack encoding)
- **Fressian** - Clojure-optimized binary format
- **JSON** - Plain JSON for interoperability with non-Clojure systems
- **Default** - EDN with pr-str/read-string (no middleware required)

If you do not use any serialization middleware, the default
`pr-str <-> read-string` mechanism is combined with a simple
binary header to track different serialization types over the wire including raw
binary data, transit-json, transit-msgpack, and fressian.


### External

We provide the following middlewares separately: - [Passwordless
authentication (and
authorisation)](https://github.com/replikativ/kabel-auth) based on
email verification or password and inter-peer trust network as p2p
middleware.

Useful middlewares still missing:
- QoS monitoring, latency and throughput measures
- remote debugging,
  sending [superv.async](https://github.com/replikativ/superv.async) exceptions
  back to the server
- other usefull `ring` middlewares which can be wrapped?

## Connectivity

More transport alternatives like long-polling with SSEs, WebRTC, NFC, ... or
normal sockets should not be hard to add.


## TODO
- implement configuration as circling of config handshake before serialization
  to allow adaptable configurations between peers, e.g. transit+msgpack on JVM,
  transit + json with javascript peers etc. The configuration message circles
  through all middlewares until a consensus/fix-point is reached and then
  middlewares start their lifecycle.
- factor platform neutral logging
- implement node.js websocket server
- implement more of wamp client protocol (and maybe router)
- investigate https://github.com/touch/pubsure-core

## Contributors
- Konrad Kühne
- Sang-Kyu Park
- Brian Marco
- Christian Weilbach

## License

Copyright © 2015-2025 Christian Weilbach, 2015 Konrad Kühne

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
