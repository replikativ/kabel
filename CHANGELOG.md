# Change Log

## Unreleased
 - **Server IO is no longer tied to http-kit.** `kabel.ring-ws` is written
   against `ring.websocket.protocols`; callers inject `run-server`.
   `kabel.http-kit` and the new `kabel.jetty` supply theirs and return the same
   map, so switching adapters is one line. http-kit remains the default and
   kabel's only declared server dependency; `ring-jetty9-adapter` is provided.
 - This fixes a live race: the old handler used http-kit's `with-channel`,
   deprecated in 2.4.0 as having "potential race conditions" (http-kit#318).
   It hands back a live channel and *then* you attach `on-receive`, so a client
   that sends immediately after the upgrade could have its first message arrive
   before a handler existed. A Ring listener is supplied as part of the upgrade
   response, so no such window exists.
 - Reach for `kabel.jetty` for in-process TLS, HTTP/2, connection caps, idle
   timeouts or metrics. It also negotiates permessage-deflate out of the box,
   which http-kit does not yet (http-kit#617).
 - `org.replikativ.kabel.PerMessageDeflateExtension` offers RFC 7692
   permessage-deflate from kabel's Tyrus JVM client, tested against an
   independent server implementation. That cross-implementation testing found a
   real §7.2.3.6 bug — an empty fragment must be encoded `0x00`, and getting it
   wrong desynchronises the stream — present in *both* implementations.
 - **CBOR wire format** (`kabel.middleware.cbor`, serialization id 14) backed by
   [boring](https://github.com/replikativ/boring). Unlike fressian it runs on
   ClojureScript from the same implementation, and unlike every other option
   here the bytes are an IETF standard that a non-Clojure peer can read with
   its own library.
 - stringref (CBOR tags 25/256) defaults to **off** on this wire. It is a
   schmorp extension most CBOR libraries do not implement, and it buys 0.3% on
   a deflated socket — deflate already finds the repetition stringref encodes.
 - `kabel.middleware.dual` for migrating a live fressian deployment in two
   deploys rather than one flag day: read both formats first, switch writers
   second.
 - `decoding-for` now throws on an unknown serialization id instead of
   returning nil. Every serialization middleware guards its in-branch on a
   match, so a nil fell through all of them and the RAW payload map reached
   application code as though it were a decoded value — silent corruption, and
   exactly what a peer hits when it meets a codec added after it was built.

## 0.2.2
 - move to timbre for logging
 - only readable log messages

## 0.2.1
 - JSON serialization
 - experimental WAMP client
 - update examples
 - bump deps

## 0.2.0
 - use tyrus java web-client
   + fixes stochastic reordering issues with http.async.client
   + android support, should work with clojure on android
 - decouple serialization from IO and provide transit
 - have a baseline serialization to always allow communication
 - minimize dependency footprint

### 0.1.9
    - factor start stop
    - support superv.async

### 0.1.8
    - small bugfixes

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
