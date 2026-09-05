# Kabel remote invocation — draft-00

Status: implemented by `kabel.remote` on the JVM and in ClojureScript. This
document is the language-neutral description of the frames, so that a client
in another language can invoke functions on a Kabel peer, or serve them, without
reading the Clojure implementation.

## 1. Purpose

A peer serves named functions. A connected peer invokes one by name with an
argument map and receives one result, or one error, on the connection the
request travelled on. The protocol is request and response over an ordered,
bidirectional Kabel connection. It carries no streaming and no cancellation;
both are layered above it by the application when needed.

## 2. Frames

Every frame is a map with a `:type` key. Frames of other types are not part of
this protocol and pass through untouched, so the middleware composes with
pub/sub, authentication, and application traffic on one connection.

| Frame | Direction | Fields |
|---|---|---|
| `:kabel.remote/register` | both, once, at connection start | `:scope` the sender's peer id |
| `:kabel.remote/invoke` | requester to server | `:scope` the target peer id, `:request-scope` the requester's peer id, `:fn-name`, `:arg-map`, `:request-id` |
| `:kabel.remote/result` | server to requester | `:scope` the requester's peer id, `:request-id`, and exactly one of `:result` or `:error` |

Field types:

- `:scope`, `:request-scope`: a peer id. Kabel peer ids are UUIDs.
- `:fn-name`: a namespaced symbol or a string. A symbol travels as a CBOR
  symbol on the CBOR wire; a client in a language without symbols SHOULD send
  the string form `"namespace/name"` and a server MUST accept both spellings as
  the same name.
- `:arg-map`: a map with keyword keys. Values are anything the wire codec
  encodes.
- `:request-id`: a value unique per requester and connection, correlated
  verbatim in the result. The reference implementation sends a UUID.
- `:result`: any encodable value, including `nil`.
- `:error`: a map `{:message string, :type keyword?, :fn-name, :data string?}`.
  `:data` is the printed representation of the server's exception data, for
  diagnostics; it is never interpreted.

## 3. Sequence

1. On connection, each side sends `:kabel.remote/register` with its own peer
   id. A side that has not received the other's registration MUST NOT send an
   invoke on that connection; a requester waits for it.
2. The requester sends `:kabel.remote/invoke`. `:scope` names the peer that
   should run the function. A peer that receives an invoke whose `:scope` is
   not its own id MUST answer with an error; it MUST NOT forward it.
3. The server answers with exactly one `:kabel.remote/result` for every invoke
   it received, on the connection the invoke arrived on, whether or not the
   function ran.

Invocations are concurrent: a server MAY run several at once and MAY answer
them out of order. The requester correlates by `:request-id`. A served
function must not block the thread it is invoked on: in the reference
implementation it runs inside a core.async go block, and blocking work is
offloaded to a thread whose channel the function returns.

## 4. Errors

A result carries `:error` when the function did not produce a value. The
`:type` values the reference implementation uses:

| `:type` | Meaning |
|---|---|
| `:kabel.remote/unknown-function` | no function is registered under `:fn-name` |
| `:kabel.remote/authentication-required` | the authorization gate denied the call and the connection has no principal |
| `:kabel.remote/not-authorized` | the gate denied the call for the connection's principal |
| `:kabel.remote/not-serving` | the peer runs the middleware but is not serving functions |
| `:kabel.remote/wrong-peer` | the invoke's `:scope` is not the receiving peer's id |
| an application keyword | the function threw an exception whose data carried this `:type` |
| absent | the function threw an exception without a typed data map |

The requester side reports two more without any frame:
`:kabel.remote/disconnected` when the connection closed before the result
arrived, and `:kabel.remote/timeout` when the caller's own deadline passed. A
disconnected call may or may not have run; a caller that retries a
non-idempotent function needs its own idempotency key in the argument map.

## 5. Authentication and authorization

The protocol carries no credentials. When the connection is authenticated
(`kabel.auth.websocket`), the server's auth middleware stamps the principal on
every inbound frame, and the serving side consults its authorization gate with
the principal, the function name and the argument map before the function
runs. The function then receives the principal under `:kabel/principal` in its
argument map. A frame's own `:kabel/principal` key, if a requester sends one,
is overwritten by the server's auth middleware and never trusted.

## 6. Distributed-scope dialect

Before this document the same frames travelled under the types
`:is.simm.distributed-scope/register-scope`, `:is.simm.distributed-scope/invoke`
and `:is.simm.distributed-scope/invoke-result`, with the same fields, and
`:error` as the printed exception string. A peer that receives a registration
in that dialect answers, and registers itself, in that dialect on that
connection. New implementations MUST send the `:kabel.remote/*` types and
SHOULD accept the old ones.

## 7. Transport binding

The frames are ordinary Kabel messages and travel in whatever codec the
connection negotiated. For a client outside Clojure the CBOR wire (serializer
id 14) is the intended binding; symbols and keywords are CBOR tagged values as
documented for that wire. Frame size is bounded by Kabel's message limit.

## 8. Remote macros

`kabel.remote.macro/defn-go-remote` turns every `go-remote` body in its function
into a named function registered with `kabel.remote/register!`. The original
call site becomes `kabel.remote/invoke`, addressed by remote id, with an
argument map containing exactly the variables listed in the capture vector.
The returned core.async channel yields the remote body's value, including
`nil`, or its failure.

Every free variable used by a remote body must appear in its capture vector.
Macro expansion fails and names variables that are missing; a declared but
unused variable is logged at debug level. This explicit boundary prevents a
lexical value from appearing to be available on another peer when it was never
sent there.

Both macros support Clojure and ClojureScript. ClojureScript consumers require
`go-remote` from `kabel.remote.macro` and require the `defn-go-remote` macro
through `:require-macros`, as they do for `superv.async`. Free-variable analysis
uses the consumer build's `cljs.analyzer`, resolved during macro expansion, so
Kabel does not impose a ClojureScript compiler version.

`kabel.remote.missionary` provides the corresponding `sp-remote` and
`defn-sp-remote` forms, plus `task->chan` and `chan->task` bridges. Missionary is
an optional backend and is not a Kabel runtime dependency; applications that
require this namespace must add `missionary/missionary` to their own classpath.
Kabel declares it only in the test alias so this integration can be tested.
