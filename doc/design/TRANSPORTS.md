# Transports: SSE, TCP, HTTP/3 — and the two things that have to be fixed first

Everything below that is stated as a number was measured in this repo, not
estimated. The probes are reproducible; where a claim is an argument rather
than a measurement, it says so.

---

## 1. The short answer

**Yes, the stack lifts onto other transports, and it is already proven.** Real
`kabel.pubsub` — both `PSyncStrategy` paths — runs over SSE + POST:

```
APPLIED: [[:handshake {:k :from-handshake}] [:publish {:hello :over-sse}]]
```

That took ~90 lines of transport code and **zero** changes to pub/sub, the
codec middleware, or anything above. The seam is `[in out]`: two core.async
channels of messages. Nothing above the transport knows what a socket is.

**But two things must be fixed first**, and neither is about SSE:

1. **There is no backpressure.** One client that stops reading cost the server
   **1364 MB of heap in 8 seconds**, with `send!` returning `true` on all
   20 000 calls. This is true of kabel's **WebSocket** transport *today*
   (1337 MB, same zero falses). SSE would inherit it, not cause it.
2. **There is no negotiation.** kabel's own source says so:
   `binary/table.cljc` — *"Removing the need is what capability negotiation
   would do."* For SSE this stops being a nicety, because a text transport
   *cannot* carry the binary codecs and has no way to say so.

---

## 2. What kabel actually demands of a transport

Less than you would think:

| requirement | where it comes from |
|---|---|
| a duplex pair of message channels `[in out]` | `kabel.peer/connect` |
| discrete messages, not a byte stream | codec middleware |
| close detection | overlay maps it to `:disconnected` |
| ordering *within* a connection | the pub/sub handshake only |
| binary payloads | codec middleware emits **byte arrays** |

Ordering is worth stating precisely, because it is the requirement people
assume is total and is not. Dissemination does **not** need ordering: it dedups
by `{origin, epoch, seq}` interval sets and repairs gaps explicitly. Only the
pub/sub handshake needs it, and it is *stop-and-wait per batch* — send a batch,
send `batch-complete`, block for the ack. So there is at most one message in
flight upstream at a time.

That matters enormously for SSE, and lands the right way up:

> The direction that needs ordering (server→client bulk handshake items) is
> exactly the direction SSE delivers ordered over one TCP stream. The direction
> that is unordered (client→server POSTs) carries only a stop-and-wait ack.

---

## 3. SSE, measured

### It already works on http-kit, unmodified

No fix needed for the basic case. A probe against `as-channel` + `send!` with
`close-after-send? = false`:

- `200`, `Content-Type: text/event-stream`, `Transfer-Encoding: chunked`
- events flushed at **+319 / +519 / +720 ms** against a 200 ms send cadence —
  no buffering, no Nagle stall
- UTF-8 survives, including the astral plane (`𝄞`, a surrogate pair)

What http-kit lacks is *ergonomics*: no SSE helper, no event framing, no
`Last-Event-ID` handling. That is a small library, not a server change.

### The encoding cost is entirely about binary

| message | transit bytes | SSE framing | base64 + SSE |
|---|---|---|---|
| small gossip | 180 | +4.4% | +37.8% |
| 40-datom tx | 1 882 | +0.4% | +33.9% |
| have-digest (20 peers) | 1 412 | +0.6% | +34.0% |
| 64 KiB konserve blob | 65 536 | n/a | **+33.3%** |

SSE framing itself is nearly free. **base64 is the whole cost**, and it is only
needed because kabel's codec layer emits byte arrays. `transit-json` is valid
UTF-8, so it converts losslessly at **0% overhead** — which is what the working
prototype uses.

### CBOR over SSE: yes, base64 — and you should do it anyway

The naive reading is "SSE forces you off CBOR onto a text codec". Measured, that
is wrong. CBOR is roughly **half** the size of transit-json, and base64 only
costs 1.33×, so the compact-binary codec still wins over the wire:

| message | CBOR | transit-json | CBOR+base64 (SSE) | transit-json (SSE) | winner |
|---|---|---|---|---|---|
| small gossip | 135 | 177 | 188 | 185 | transit-json, by 2% — a wash |
| 40-datom tx | 856 | 1 679 | **1 152** | 1 687 | **CBOR, 32% smaller** |
| have-digest | 342 | 713 | **464** | 721 | **CBOR, 36% smaller** |

Only for tiny messages does the text codec win, and then barely.

### Compression very nearly erases the base64 penalty

base64 wastes 2 bits in every 8, and a compressor gets them back:

| | raw | gzipped |
|---|---|---|
| CBOR | 856 | 178 |
| CBOR + base64 | 1 144 (+33.6%) | 217 (**+21.9%**) |
| **random 64 KiB blob** | 65 536 | — |
| **blob + base64** | 87 384 (**+33.3%**) | 66 224 (**+1.0%**) |

For high-entropy data — precisely the konserve-blob case — base64 costs **1%**
once compressed, not a third.

**The catch, verified directly:** `text/event-stream` is in Jetty's default
`GzipHandler` excluded MIME types (confirmed by instantiating it). Jetty's own
comment explains why — compressing an event stream needs `syncFlush`, which
costs performance. http-kit has no SSE compression at all.

**So compress per message in kabel's codec middleware, not at the HTTP layer.**
That sidesteps Jetty's exclusion, avoids `syncFlush`, works identically on
http-kit, and lands in a layer kabel already owns.

### Compression belongs in kabel, not in the server — and context takeover is the whole point

The open permessage-deflate PR for http-kit raises the obvious question: should
it be generalised to SSE? Measured on 200 realistic gossip messages (same
shape, incrementing seq — the repetition a shared window exploits):

| | bytes | of raw |
|---|---|---|
| raw CBOR | 31 534 | 100% |
| deflate, **no** context takeover | 29 972 | **95.0%** — useless |
| deflate, **with** context takeover | 3 906 | **12.4%** |
| whole-stream gzip (upper bound) | 1 795 | 5.7% |
| with context takeover, **then base64 for SSE** | 5 620 | **17.8%** |

**Context takeover is worth 7.7×**, and per-message deflate without it does
essentially nothing at our message sizes. Note the last row: compressed CBOR
base64'd over SSE is **5.6× smaller than uncompressed traffic**. Next to
compression, the base64 question stops mattering at all.

**So put DEFLATE in kabel's codec middleware, not in the server.** Then one
implementation covers WebSocket, SSE and TCP alike; it sidesteps Jetty's
`text/event-stream` gzip exclusion and http-kit's absence of SSE compression;
and it lands in a layer we own and can test on both platforms. It is also
exactly what the capability hello is for — `:kabel/features #{:deflate}`.

Two cautions, both real:

- **Security.** Context takeover across a stream carrying *mutually
  distrusting* publishers is the BREACH/CRIME class: compression ratio leaks
  across origins. A relay is precisely that stream. Either keep a context per
  origin, or run no-context-takeover on relay links and accept the 7.7×.
  `MODERATION.md`'s threat model applies here and should be extended to say so.
- **Memory.** Measured ~**74 KB per connection** for a live Deflater+Inflater
  pair. 200 connections ≈ 15 MB; 10 000 ≈ 740 MB. RFC 7692's
  `*_max_window_bits` is the knob, and the http-kit PR already parses it.

**Interaction to watch:** if kabel compresses per message, http-kit's
permessage-deflate would compress it *again* — wasted CPU on incompressible
input. Negotiation has to turn one of them off, which is another reason the two
prerequisites are one piece of work.

### Is CBOR → deflate → base64 nice to consume from JS/Python?

Tested against a real wire dump produced by this repo, not argued.

**Python — the whole client-side decoder:**

```python
dec = zlib.decompressobj(-15)              # -15 = raw deflate, no zlib header
def decode(frame_b64):
    return cbor2.loads(dec.decompress(base64.b64decode(frame_b64)))
```

Four lines, stdlib plus `cbor2`. Decoded all 8 frames, exact match.

**Node — built-in `zlib`, no dependencies:**

```js
const inflate = zlib.createInflateRaw();   // one stream = context takeover
inflate.write(Buffer.from(frame, 'base64'));
inflate.flush(zlib.constants.Z_SYNC_FLUSH, …);
```

Also worked first try. In a browser the equivalents are
`DecompressionStream('deflate-raw')` or `pako`.

**Why it composes this cleanly:** we keep the `00 00 FF FF` sync tail on every
frame. permessage-deflate *strips* it (RFC 7692 §7.2.1) and the peer re-appends
it; over SSE there is no such convention, so keeping it makes each frame
self-delimiting — write, flush, get exactly one message. **That is a deliberate
divergence from the WebSocket PR and worth preserving.**

**The stringref trap we already avoided.** boring's default `:clojure` profile
enables stringref (tags 25/256), and my first probe — calling `boring/encode`
directly — produced frames starting `d9 0100`, i.e. tag 256. Many JS CBOR
libraries do not implement stringref. `kabel.middleware.cbor` already passes
`{:stringref false}`, so the real wire is plain core CBOR (`a7 …`, a 7-pair
map) that any conformant decoder reads. Its docstring gives the reason and the
measurement: stringref off is *smaller* after deflate (-0.3%), because
back-references compress worse than the strings they replace.

**The one sharp edge: context takeover is stateful.** Message *N* cannot be
decoded without 1..*N*-1, so the inflater is bound to the connection. Because
SSE streams are cut constantly (§4b), **a client must reset its inflater on
every reconnect** — otherwise it gets errors or, worse, silently wrong bytes.
One line, but it has to be documented or it will bite someone.

**And it is opt-in.** Compression is negotiated (`:kabel/features #{:deflate}`),
so a client that does not ask for it gets CBOR + base64 and needs three lines in
any language. A naive consumer stays first-class; that is the property that
makes this reasonable to put on a public wire.

### But large blobs should not be on SSE at all

A konserve block is content-addressed (`hasch/uuid`), immutable, and
independently verifiable. That is the ideal plain-HTTP object: `GET
/block/<address>` with `Cache-Control: immutable`, CDN-cacheable, range
requestable, no base64 and no streaming. **Split the planes** — SSE for the
gossip/control plane, ordinary HTTP GET for content. `kabel.content` already
separates them, so this is a deployment choice rather than new design.

### What SSE cannot do

- **No upstream channel.** Ever. You need POST, which means a second
  connection, no ordering guarantee across messages, and per-message HTTP
  overhead. Fine for kabel because the handshake is stop-and-wait.
- **Text only.** Not a tuning knob — a spec constraint.
- **http-kit is HTTP/1.x only** (`enum HttpVersion { HTTP_1_0, HTTP_1_1 }`), so
  a browser's ~6-connections-per-origin limit applies and each peer link burns
  one. HTTP/2 multiplexing is the standard fix, and reaching it means **Jetty**,
  not http-kit.

### What SSE gives us that WebSocket does not

Ordinary HTTP: proxies, CDNs, corporate middleboxes, and HTTP caching semantics
all understand it. Auth is just headers. And it degrades to something
debuggable — `curl` shows you the stream.

---

## 4. The finding worth the most: reconnection is already solved

SSE's weakest point is what happens across a reconnect. Its answer is
`Last-Event-ID`: one scalar resume point, which assumes a total order per
stream and cannot express a hole.

kabel's dissemination is strictly stronger, and it is already built. Measured:

```
delivered before reconnect:            5
delivered after a replay of 3..7:      8      ; only the 3 new ones
duplicates suppressed:                 2
seen:                             [[0 7]]     ; still one contiguous range

after a real gap:                 [[0 7] [12 14]]
repair query:   [{:origin :pub, :epoch 0, :lo 8, :hi 11}]
```

A server that resumes conservatively and **replays overlapping events costs
nothing** — duplicates are dropped by the interval set. A server that resumes
too far ahead leaves a hole, and the repair query names *exactly the hole*
rather than the history. And when the hole is older than the message store,
`beyond-horizon` escalates to a differential state sync.

> SSE's hardest problem is a problem kabel solved better before SSE was on the
> table. That is the strongest single argument for doing this.

---

## 4b. Infrastructure will kill the stream — that is the normal case

This is the part that changes the design rather than decorating it. Surveying
vendor documentation for load balancers, CDNs and serverless platforms, the
failures split into three mechanically different classes, and they are
routinely conflated:

| class | beatable by heartbeat? | examples |
|---|---|---|
| **idle timeout** | yes — send a `:` comment | ALB 60 s, NLB 350 s, Azure LB 4 min, CloudFront 30 s *inter-packet* |
| **total-duration cap** | **no** | GCP ALB backend timeout 30 s, Cloud Run 300 s, Vercel 300 s, Netlify **60 s, not configurable**, ALB `client_keep_alive` 3600 s |
| **buffering** | no — defeats SSE outright | API Gateway REST default, Netlify's non-streaming path, Azure Front Door |

Two consequences worth stating plainly.

**GCP's is the nastiest**, because it reads like an idle timeout and is not:
the external ALB's backend service timeout is *"the maximum amount of time
allowed between the load balancer sending the first byte of a request and the
backend returning the last byte of the HTTP response"*. A perfectly
heartbeated stream is truncated on schedule at 30 s. WebSockets are explicitly
carved out of this; **SSE is not**, because it is an ordinary HTTP response.
Azure Front Door reportedly does not support SSE at all, and caps origin
response timeout at 240 s regardless.

**So reconnection is not an edge case — on several platforms it is guaranteed
and periodic.** On Netlify you reconnect every 60 seconds, forever, and no
configuration avoids it.

That is exactly why §4 is the argument for doing this. If streams are going to
be cut constantly, then the quality of your resume story *is* the quality of
your transport — and `Last-Event-ID` (one scalar, cannot express a hole) is
markedly weaker than an interval set that dedups overlap for free, names the
gap exactly, and escalates to a state sync when the gap outruns the store.

The corollary for us: **a `retry:` hint plus fast, cheap reconnect matters more
than long-lived connections.** Do not tune for a stream that stays up; tune for
one that is cut every minute and resumes correctly.

---

## 4c. What SSE buys over WebSocket + deflate — honestly

The uncomfortable measurement first. SSE has no uplink, so client→server is an
HTTP POST per message. Measured on the wire, for a 21-byte body (a deflated
gossip message with context takeover):

```
POST request bytes on the wire: 266 for a 21 byte message   (overhead: 245)
WebSocket client->server frame:   6 bytes of overhead
=> POST costs 41x more than a WS frame, per message
```

**So for the symmetric overlay — peers gossiping both directions — SSE is the
wrong transport, and not marginally.** It is right for the read-mostly
subscriber: a server that publishes, a client that subscribes, upstream
confined to a stop-and-wait handshake ack.

What SSE genuinely buys, narrowed to claims that survive scrutiny:

**1. Per-request control over intermediaries.** This is the real one. Because
it is ordinary HTTP, you can direct proxy behaviour *per route*:
`X-Accel-Buffering: no` for the nginx family, `Cache-Control: no-transform`
(RFC 9111 §5.2.2.6 binds intermediaries "regardless of whether it implements a
cache"), and ordinary auth headers. A WebSocket is an opaque tunnel — you get
whatever the middlebox decides to do, with no per-request lever at all.

**2. A CDN can act as a dissemination relay.** Fastly does *request collapsing*
on SSE: "no matter how many clients you are streaming to, your origin should
see only one request." That is structurally a kabel relay, implemented by
somebody else's edge network, and **WebSocket cannot do it at any price**. For
a topic with many subscribers this is the strongest argument on the list. The
caveat is Fastly's own: segment the stream with a TTL, or you hold origin
connections forever.

**3. No protocol extension needed.** SSE rides HTTP/2 and HTTP/3 natively;
WebSocket needs RFC 8441 Extended CONNECT (h2) or RFC 9220 (h3), with patchier
support.

And the costs that are not negotiable: base64 (§4a), no uplink (above), the
6-connection limit per origin on HTTP/1.1 — **WONTFIX in both Chromium and
Gecko**, against 255 (Chrome) / 200 (Firefox) for WebSocket, a 40× asymmetry
written into browser source with a comment explaining that long-lived
connections are different.

One API note: native `EventSource` cannot set request headers, cannot POST, and
has no backoff control. Everyone serious uses `fetch` + `ReadableStream` + a
standalone `text/event-stream` parser instead — `eventsource-parser` has ~43.8M
weekly downloads against `eventsource`'s 44.9M, which tells you the industry
kept the grammar and discarded the API. kabel should do the same.

## 4d. The mobile power claim is folklore

Worth stating plainly because it is widely believed and it is **not true**, and
the belief has a traceable origin: WHATWG HTML §9.2.1 says SSE "can result in
significant savings in battery life on portable devices" — and §9.2.8 explains
the mechanism as *connectionless push*, where the device hands the connection
to a **carrier-operated push proxy** and sleeps. That mechanism was never
implemented by anyone. The spec's editor: "I haven't heard of any
implementations."

The only head-to-head measurement (Estep 2013, Monsoon hardware power monitor,
4 handsets) has **the sign flip by device** — SSE 31% worse on one, 37% better
on another — and converges to 2% at 10 hours. Browser was confounded with
protocol (Firefox for WS, Opera for SSE). It is not evidence of a protocol
effect.

What actually dominates is the **keepalive interval**, by ~16× within a single
protocol, because of the radio state machine. Huang et al. (MobiSys 2012),
measured on commercial LTE: RRC tail timer 11.576 s, tail power 1060 mW, and

> "if only one packet is transferred, the energy usage considering both
> promotion and tail energy for LTE, 3G and WiFi is **12.76 J**, 7.38 J and
> 0.04 J."

Now price the protocol difference. An SSE comment is 3–7 bytes; a server→client
WebSocket ping is 2 bytes. At their measured LTE downlink coefficient
(51.97 mW/Mbps) four bytes is ~1.7 µJ against 12.76 J to wake the radio —
about **7 500 000 : 1**. The framing difference is unmeasurable.

**And the one real asymmetry runs against SSE**: the spec recommends a comment
"every 15 seconds or so", which is *shorter than the 11.576 s RRC tail* — i.e.
it pins the radio in the connected state. WebSocket has no such recommendation,
and Estep measured a WS connection surviving 21 h 35 m with zero packets sent.

Two mobile benefits that *are* real, and neither is about power per se:

- **HTTP/2 multiplexing**: N SSE streams share one connection, one keepalive,
  one NAT binding, one radio wake — where N WebSockets need N connections
  without RFC 8441. Structurally sound, but unmeasured.
- **HTTP/3 connection migration** (RFC 9000 §9) survives a WiFi→cellular
  handoff that kills any TCP-based stream. Protocol-neutral: WebTransport and
  WebSocket-over-h3 get it too.

For a backgrounded mobile client, neither transport survives Android Doze or
iOS suspension, and the correct answer is FCM/APNs. That is the *measured*
version of what the SSE spec was gesturing at.

## 5. The transport fix (prerequisite #1)

### The measurement

One non-reading client, 64 KiB events, 8 seconds:

| transport | heap growth | `send!` returned false |
|---|---|---|
| SSE | **1364 MB** | 0 / 20 000 |
| WebSocket | **1337 MB** | 0 / 20 000 |

`org.httpkit.server.HttpServer:502` — `toWrites` is an unbounded
`LinkedList<ByteBuffer>`. There is no signal, no bound, and `open?` stays
`true` throughout. One socket is a denial of service.

This directly contradicts what the overlay promises. `MODERATION.md` §5 and
`kabel.ratelimit` describe Synapse's *sleep → queue → reject* discipline for
**inbound** traffic. Outbound has nothing. A relay cannot honour "do not flood
peers unexpectedly" when its own send path is unbounded.

### The fix, in layers

1. **kabel** — use `ring.websocket.protocols/AsyncSocket/-send-async` instead
   of `-send` (`ring_ws.clj:107`) and gate on the succeed callback: a credit
   window per connection, closing the connection when credit is exhausted,
   exactly as replikativ dropped connections on backpressure.
2. **Jetty already supports this.** `ring-jetty9-adapter` implements
   `AsyncSocket`. http-kit implements only `Socket` (`server.clj:535`), so on
   http-kit the callback cannot mean anything.
3. **http-kit (upstream)** — implement `AsyncSocket`, bound `toWrites`, and
   report the queue depth. This is the fix worth contributing, and it is
   independent of SSE.

Until (3) lands, **Jetty is the server for any deployment that faces hostile or
merely slow peers.** That is a real answer to "which server", and it is not the
one the default suggests.

---

## 6. Capability negotiation (prerequisite #2)

### The chicken-and-egg, and the way out

You cannot negotiate a codec in a frame that needs the codec being negotiated.
kabel already has the escape and does not use it: `to-binary` falls back to
`pr-str` (frame id 2) when `:kabel/serialization` is unset, and **every** codec
middleware guards its in-branch and passes unknown serializations through. So
frame id 2 is a universal channel, readable by every kabel peer ever built.

Measured: a full capability hello round-trips in **118 bytes**.

```clojure
{:type :kabel/hello
 :kabel/protocol  1
 :kabel/codecs    [:cbor :fressian :transit-json]  ; preference order
 :kabel/binary?   true        ; a FACT about the transport, not a preference
 :kabel/max-frame 1048576
 :kabel/features  #{:overlay/v1 :pubsub/v1}}
```

Both ends send it unconditionally on connect — the overlay's identity hello
already works exactly this way, so the pattern is established rather than new.

### Why SSE forces the issue

`:kabel/binary? false` is not a preference, it is what the transport *is*. A
text transport must land on a text codec or pay 33%. Negotiation is the only
path by which a transport's constraint can reach the codec layer, which today
has no way to learn it.

### What it retires

`kabel.middleware.dual`'s three-step deployment dance — *"There is no way to
skip step 1."* With negotiation, a peer that understands CBOR says so, and a
peer that does not is never sent it. `dual` remains useful as a mechanism (read
both, write one); it stops being a coordination problem.

### Where it sits

Below the codec middleware, above the transport — the one layer kabel did not
have. It composes into the serialization slot, so `kabel.peer` is untouched:

```clojure
(peer/client-peer S id middleware (comp transit #(negotiate opts %)))
```

### The agreement is parametric, not a side channel

The result is emitted **inband** as `{:type :kabel/negotiated :caps …}` and
travels up the ordinary in-channel, so every middleware above sees it. A
middleware that cares keeps per-connection state, updates it as the message
passes, and lets its out-branch consult it — the codec layer learns which codec
to write, a compression middleware learns whether `:deflate` was agreed, a
chunker learns `:max-frame`.

This works because kabel's middlewares already ignore what they do not
recognise: `kabel.pubsub` dispatches unknown types to `:unrelated` and passes
them through, codec middlewares guard on their own serialization, and the
overlay passes non-overlay frames on. **So a capability added later cannot
break a middleware that does not care about it** — which is the property that
makes this safe to extend. Tested end-to-end through a real pub/sub stack.

### Status: implemented

`kabel.negotiate`, with agreement as a pure function (`agree`) so most of it
tests without channels. Deployable without a flag day: a peer that sends no
hello yields `nil` capabilities after `:timeout-ms`, which is exactly today's
behaviour.

---

## 7. The other transports

### Raw TCP

Straightforward — it is the one transport that gives backpressure *for free*,
because a blocking socket write is backpressure. Saves the WebSocket frame
header (2–14 bytes) and the HTTP upgrade. But:

- **no browser**, which removes the reason kabel is `.cljc` at all;
- no proxy/CDN traversal, and TLS is yours to run.

Worth it for **server-to-server relay links** where both ends are ours and
throughput matters — a datahike/konserve replication backbone. Not worth it as
a general transport. The framing saving is noise next to a 40-datom transaction.

### HTTP/3 / WebTransport

The interesting one, and not reachable yet from this stack. QUIC removes
TCP-level head-of-line blocking, which is the failure mode that actually bites
a multiplexed overlay: one stalled stream stalling all the others. WebTransport
is bidirectional and binary, so it has none of SSE's constraints.

But it needs a server that speaks HTTP/3 (Jetty has a module; http-kit is
HTTP/1.x by enum) and a client story that is browser-only in practice.
**Revisit when Jetty's HTTP/3 is boring**, not before.

### The comparison

| | duplex | ordered | binary | browser | backpressure | proxies |
|---|---|---|---|---|---|---|
| WebSocket | yes | yes | yes | yes | server-dependent | mostly |
| SSE + POST | half | down only | **no** | yes | **none** | yes |
| raw TCP | yes | yes | yes | **no** | **free** | no |
| WebTransport | yes | per stream | yes | yes | yes | emerging |

---

## 8. Decision

**SSE: deferred, not rejected.** The two measurements that settle it are the
41× uplink tax (§4c), which rules it out for the symmetric overlay, and the
fact that its one great advantage — CDN request collapsing turning an edge
network into a dissemination relay — only pays off with many browser
subscribers per topic, which no deployment has yet. Revisit when one does.

Everything worth having from the investigation was **transport-independent**,
which is the real result:

| item | status |
|---|---|
| **Capability negotiation** | **landed** — `kabel.negotiate` |
| **Per-message DEFLATE with context takeover** (7.7×) | designed, not built; belongs in codec middleware, not the server |
| **Outbound backpressure** | **outstanding, and a live vulnerability** |
| SSE transport | deferred |
| TCP | only for server-to-server relay links, if a measurement asks |
| HTTP/3 | deferred; trigger is Jetty HTTP/3 leaving experimental |
| `client-connect!` dispatching on URL scheme | small, still worth doing |

### The one thing that should not wait

§5 is not an SSE finding. One non-reading client costs **1364 MB of heap in 8
seconds** against kabel's **WebSocket** transport as shipped, with `send!`
returning true on all 20 000 calls. A relay cannot honour "do not flood peers
unexpectedly" with an unbounded send queue, and `kabel.ratelimit` bounds only
the inbound direction.

The fix is `AsyncSocket/-send-async` plus a credit window, which works on Jetty
today and needs an upstream http-kit change to mean anything there.

### On the permessage-deflate PR

Land it for WebSocket as it stands. Do **not** widen it to SSE. What is worth
lifting out of it is the hardening — stream ceilings, restart bounds, codec
lifecycle — because those are properties of the DEFLATE engine, not of
WebSocket, and a kabel-level implementation would otherwise rediscover them.

The honest framing: SSE is not worth building right now, and asking whether it
was surfaced an unbounded send queue in the transport we already ship, a
7.7× compression win we were not taking, and the negotiation layer that has
been missing since the beginning.
