# Change Log

## Unreleased
 - `kabel.remote/serve` runs each invocation on its own thread on the JVM.
   A served function may block (a database call, a socket) without holding
   one of the go dispatch pool's few threads; before, a handful of blocking
   handlers could deadlock the whole process.
 - `kabel.pubsub/unsubscribe!` is idempotent: a topic whose cancellation is
   already in flight is not asked for again — the caller settles with the
   request in flight — and a topic with no subscription is already done. A
   second unsubscribe used to send a second request the server never
   answers, leaving the caller waiting forever; consumers that release a
   subscription from two places (a connection's shutdown and its owner) no
   longer have to coordinate.
 - **Fix: pub/sub read the decoded frame size from the wrong place.** The CBOR
   codec records `:kabel/encoded-bytes` in the decoded frame's metadata; the
   subscription lifecycle looked for a map key, found nothing, and estimated
   every frame by printing it. Printing is application code: a Datahike index
   head loads children from storage when printed and threw, which killed the
   receive lane, so the batch was never acknowledged and the server retired
   the connection after 30 s. Since 0.3.113 every Datahike client handshake
   over CBOR hung this way. The estimate now reads the metadata, and a failed
   fallback estimate counts as zero instead of taking the lane down. A
   connection retired with handshakes still pending now logs a warning, since
   the subscriber's completion callback will never fire.
 - `kabel.remote/serve` ends quietly when its peer's supervisor aborts instead
   of reporting the abort as an error, returns `:done` next to `:stop!`, and
   answers an invoke that reached the bus without a dialect in the kabel one.
 - **Connection transport boundary.** `client-peer` and `server-peer` accept an
   optional `:transport-middleware` outside serialization, suitable for an
   authenticated Noise upgrade without changing application middleware or
   pub/sub. Every physical connection gets a unique mutable context containing
   initiator/responder role, expected/observed remote address and negotiated
   identity/capability slots; `kabel.transport` preserves it across legacy
   middleware and exposes active connection lifecycle state on the peer.
 - ClojureScript advanced compilation now sees the `superv.async` runtime used
   by `kabel.metrics` macro expansions explicitly, removing undeclared-var
   warnings in downstream optimized builds.
 - **Language-neutral carrier.** `WIRE.md` freezes the uint32-big-endian
   serializer envelope and CBOR profile. The `kabel-protocol` Python alpha
   package implements bounded framing, serializer 14, keyword/set tags, and an
   optional asyncio WebSocket adapter, with a shared JVM/Python known answer.
 - **Remote invocation** (`kabel.remote`). The request/response runtime that
   `is.simm.distributed-scope` built its macros on now lives in kabel:
   `register!`, `serve` with an `:authorize` gate in the `kabel.authorize`
   shape, `invoke`, and a connection middleware that announces the peer. The
   frames are specified in doc/remote-invocation.md; the distributed-scope
   dialect is accepted and answered in kind, so peers upgrade one at a time.
   Results are correlated per request rather than by scanning the bus, a call
   in flight fails with `:kabel.remote/disconnected` when its connection
   closes, `:timeout-ms` bounds a call, and errors travel typed instead of as
   printed strings. Functions receive the principal under `:kabel/principal`
   in their argument map instead of a dynamic binding.
 - **Reconnection** (`kabel.peer/maintain`). A client peer stays connected with
   exponential backoff, reporting `:connecting`, `:connected`, `:disconnected`,
   `:failed` and `:stopped` on its bus as `:kabel.peer/status` and to an
   `:on-status` callback. `peer/status!` publishes such a status for any
   layer, `peer/disconnect!` closes the current connection, and `peer/connect`
   now yields a channel that closes with the connection.
 - **Token refresh and expiry** (`kabel.auth.websocket`). The client's `:token`
   may be a function or an atom, read at every connection so a reconnection
   carries the current token; `refresh-token!` replaces the token on the live
   connection; a JWT with `exp` from such a source is refreshed automatically
   before it expires. The validating side now watches the accepted token's
   `exp` and, by default, closes a connection whose token expired without a
   refresh (`:on-expiry :close`; `:anonymous` and `:ignore` are the
   alternatives). **Behaviour change:** before, an accepted token was never
   re-examined and a connection outlived its expiry indefinitely. `kabel.auth.jwt/claims`
   decodes a token's payload without verifying it, for reading `exp`.
 - **Bounded WebSocket transport.** JVM, JavaScript, http-kit and Jetty paths
   enforce a 5 MiB application-message ceiling; permessage-deflate uses the
   same post-inflation bound. Raw input uses nonblocking admission into a
   1,024-item lane, raw output retains 16 encoded messages, disconnect closes
   both directions, and oversize/overload failures close visibly instead of
   accumulating pending puts.
 - Server writes use Ring `AsyncSocket` completions where available, with one
   write in flight. Jetty provides that contract. Released http-kit 2.8.x does
   not and retains an unbounded internal socket queue, so public deployments
   must use Jetty or the byte-ceiling work in http-kit PR #619 until that lower
   layer ships.
 - **Opt-in transport metrics.** `kabel.metrics/messages` counts logical
   messages by direction and type; `kabel.metrics/wire` counts WebSocket
   application bytes outside the codec; connection/reconnection/disconnection
   and successful pub/sub subscription events share the same dependency-free
   `replikativ.metrics` registry used by the rest of the stack. Labels exclude
   peer ids, URLs, and topics so a deployment cannot accidentally create an
   unbounded series set.
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
