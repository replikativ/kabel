# An optional DHT for kabel — design notes

Status: **design input, not a specification.** Written by Claude at Christian's
request while working on `urd` (consensus) next door. Nothing here is
implemented.

I have marked each claim as one of:

- **VERIFIED** — I read the code in this repo or in `urd-core` and checked it.
- **RECOMMENDED** — a design position I will argue for, with the reason.
- **OPEN** — a real decision I do not think should be made by default.

The reason for the labels: several conclusions in the neighbouring `urd` work
turned out to rest on things that looked checked and were not, and the habit of
separating the two is what caught them.

---

## 0. Scope: what a DHT is for here

Two genuinely different services get called "the DHT", and they have opposite
trust requirements. Keeping them apart is the single most useful structural
decision available.

| | question answered | wrong answer is… |
|---|---|---|
| **Content routing** | who has blob `<hash>`? | self-detecting — the hash does not match, you retry |
| **Peer routing** | how do I reach peer `X`? | **not** self-detecting — you talk to the attacker |

**RECOMMENDED:** build content routing first. It is the half that needs almost
no trust machinery, it is immediately useful to konserve (which is already
content-addressed via `hasch`), and it is the half datopia's own economics note
asks for — *"they should regularly put the chain in a read-only p2p system like
bittorrent for free, assuming that distributional costs will be carried by the
p2p participants."*

Peer routing is where the attacks live. It can come second, and it should carry
the mitigations in §2.

---

## 1. The invariant: discovery is not membership

**RECOMMENDED, and I would hold this one hardest.**

> A DHT answers *how do I reach X*. It must never answer *does X count*.

For any BFT protocol, if DHT contents can influence the validator set, then an
attacker who can populate the DHT can mint validators. That is not a degraded
security property, it is a total break — the quorum arithmetic is computed over
a set the attacker supplies.

Even for permissionless proof-of-work, where anyone may participate, the
authority is accumulated work, not DHT presence. Bitcoin's `addr` gossip finds
peers; the chain decides truth. The two are never the same table.

**Concretely for kabel:**

- Give peer routing its own namespace and its own storage. Do not let it share a
  keyspace with anything a protocol treats as authoritative.
- A consensus layer may consult the DHT only to resolve an **address** for an
  identity it *already* trusts from its own configuration or from on-chain
  state.
- The DHT should never be the source of "who are my peers" for a validator. See
  §2.

**VERIFIED that this is easy to get wrong by accident:** in `urd`'s simulator,
`:peer-ids` is a single value serving as both *who is on the network* and *who
counts for quorum* (`urd-core/src/urd/consensus/sim.cljc`, fixed at `make`).
Nothing broke, because the set never changes. The moment validator-set changes
land, those have to be separated. Starting kabel with them separate is free;
splitting them later is not.

---

## 2. Eclipse is the attack that matters

**RECOMMENDED.**

A DHT changes the cost of eclipsing a node. Without one, an attacker needs
network position. With one, they need **routing table entries** — which is a
much cheaper thing to obtain, because the routing table is designed to accept
new peers from strangers.

For a consensus node, eclipse is partition: cut a validator off and you have
removed it from the quorum without touching the network. For a proof-of-work
node it is worse, because it enables feeding a false chain.

Mitigations, in rough order of value-for-effort:

1. **A configured peer floor.** Validators keep a static, authenticated set of
   peers from configuration and use the DHT only to *augment* reachability. The
   DHT must never be the sole source of connectivity for a node whose vote
   matters. This alone removes most of the risk and costs nothing.
2. **Bucket by network locality, not just by ID.** Bitcoin buckets candidate
   addresses by `/16` netgroup so that one host with many addresses cannot fill
   the table. Whatever the analogue is for your deployment (ASN, subnet), the
   principle is that routing-table diversity must be over something an attacker
   pays for.
3. **Sticky peers.** Keep connections to peers that have behaved well across
   restarts (Bitcoin's "anchor" connections). An attacker who arrives after you
   have good peers should not be able to displace them.
4. **Disjoint lookup paths** (S/Kademlia). A lookup that follows `d` disjoint
   paths and requires agreement is much harder to poison. Worth doing for peer
   routing; unnecessary for content routing, where the hash checks the answer.

---

## 3. Identity: the first decision, and kabel does not currently have one

**VERIFIED:** kabel's peer `:id` is caller-supplied with no cryptographic
binding (`src/kabel/peer.cljc:84`, `:107` — `id` is a constructor argument).
Authentication is trusted-issuer JWT (`src/kabel/auth/jwt.cljc`), i.e. a
**federated** model: identity is asserted by an issuer you configure.

That matters, because the standard DHT hardening — deriving the node ID from a
public key so that occupying a chosen point in the keyspace costs key grinding
— assumes **self-sovereign** keypairs, which kabel does not have today.

**OPEN — this is the decision I would make first:**

**Option A — peer keypair.** Introduce an Ed25519 identity per peer; node ID is
`hash(pubkey)`. Routing-table poisoning then costs grinding, and node IDs are
self-authenticating. Cost: a new concept in kabel, and key management becomes an
operator concern.

> **CORRECTION (verified later).** An earlier version of this section said
> Option A "composes with `urd`, which already has Ed25519 via `geheimnis`".
> That was wrong on both counts, and is exactly the kind of unchecked claim the
> preamble to this document warns about:
>
> - `geheimnis` has **no** Ed25519. Its namespaces are `aes`, `base64`, `md5`,
>   `rsa` (`geheimnis/src/geheimnis/`).
> - `urd`'s Ed25519 is **BouncyCastle, JVM-only**
>   (`urd/src/urd/crypto.cljc:25-79`), and its ClojureScript branch is a
>   placeholder whose three functions all
>   `(throw (js/Error. "ED25519 not yet implemented for ClojureScript"))`
>   (`:84-101`).
>
> So there was no cross-platform Ed25519 anywhere in the stack.

**RESOLVED — Option A is implemented, `src/kabel/identity.cljc`.** The crypto
came from elsewhere:

- **JVM: no dependency at all.** JDK 15+ ships native Ed25519
  (`KeyPairGenerator`/`Signature` with algorithm `"Ed25519"`); verified working
  on the JDK 25 in use here. Keys are raw 32 bytes on the wire, obtained by
  stripping the fixed X.509 (12-byte) and PKCS#8 (16-byte) prefixes — both
  pinned by `kabel.identity-test/asn1-prefixes-are-constant`.
- **ClojureScript: `@noble/ed25519`**, one small npm dependency, async via
  WebCrypto.
- **Peer id = `SHA-256("kabel/peer-id/v1" ‖ pubkey)`**, with the first 16 bytes
  projected to a UUID so a self-certifying id drops into kabel's existing `:id`
  slot with **no other wire format changing**. Pinned cross-platform by
  `peer-id-known-answer`; SHA-256 itself is pinned against the NIST "abc" vector
  because the JVM and ClojureScript paths are entirely different
  implementations (`MessageDigest` vs `goog.crypt.Sha256`).

**Option B — issuer-scoped IDs.** Node IDs stay opaque, and DHT participation
is gated by the existing JWT layer. Simpler, keeps one identity model, and is
reasonable for a federated deployment. Cost: it does not work for a
permissionless network, because the issuer is a central authority — which is
precisely what the Nakamoto use case is trying not to have.

**RECOMMENDED:** A, if the DHT is meant to serve the permissionless case at
all. B is a coherent choice only if the DHT is scoped to federated deployments
forever. Choosing B and later needing A means changing the wire format of every
node ID.

Whatever you pick, **the node ID derivation is a wire-format decision** — the
hardest kind to change once anything is deployed.

---

## 4. Browser peers cannot be DHT nodes

**RECOMMENDED, and specific to kabel.**

kabel is clj+cljs with browsers as first-class peers, and a browser cannot
accept an inbound WebSocket. So a browser peer cannot occupy a routing-table
slot that anyone is able to dial. A classic flat Kademlia assumes every node is
dialable, and that assumption is false for a large fraction of kabel's
deployments.

This needs to be in the design from the beginning, because the routing table
type ends up encoding it:

- **Dialable nodes** form the DHT proper: they hold buckets, answer lookups, and
  store records.
- **Leaf nodes** (browsers, and anything behind a NAT that has not punched)
  attach to one or more dialable nodes and query **by delegation** — they ask a
  dialable peer to perform the lookup.

libp2p reached the same split (delegated routing) after starting flat, and
retrofitting it is reportedly unpleasant. The `PSyncStrategy` protocol already
gives you a natural place for a leaf node to express "I need this looked up"
without owning a routing table.

---

## 5. Portability trap: XOR distance must be computed on bytes

**VERIFIED, and this codebase's sibling has already paid for it once.**

ClojureScript's bit operations coerce to **32-bit signed** integers. From
`urd-core/src/urd/consensus/rng.cljc`:

> `(bit-and 0xFFFFFFFF 0xFFFFFFFF)` is `-1`, not `4294967295`.

That one cost a day in `urd`, and it is *why* `urd`'s anti-replay window is a
set of sequence numbers rather than the textbook 64-bit bitmap
(`urd-core/src/urd/consensus/auth.cljc:76-90`).

A 160- or 256-bit XOR metric implemented over JS numbers will be **silently
wrong on one platform only** — the worst failure mode available in a portable
codebase, because every JVM test passes.

**RECOMMENDED:**

- Implement distance and bucket-index over byte arrays / typed arrays, never
  over numbers.
- Test the **metric itself** on both platforms, not merely the code that uses
  it. Specifically: XOR distance is a metric (identity, symmetry, triangle
  inequality) and bucket index is `160 - leading_zeros(distance)` — both are
  cheap to property-test and both are exactly where a 32-bit truncation hides.

---

## 6. Bound the value store, not just the routing table

**RECOMMENDED.**

k-buckets are naturally bounded (k per bucket, ~160 buckets). Provider records
and stored values are not, and an unbounded record store is a
memory-exhaustion vector reachable by anyone who can send a `STORE`.

- Records need a **TTL** and **periodic republish** by the original provider.
  Expiry is what makes a record store bounded; republish is what stops it from
  being lossy.
- Both intervals should be **configuration, not constants** — the right values
  depend on churn rate and record count, and a constant chosen now will be
  wrong for some deployment.
- Cap records per key and per storing peer. A single peer announcing itself as
  provider for a million keys should not be able to.

For context, bounding retention was the single largest source of real bugs in
the neighbouring `urd` work — every algorithm needed an explicit retention
policy, and each one that lacked it grew until the process died.

---

## 7. Testing: a deterministic simulator, and do not stub the thing you are testing

**RECOMMENDED.**

Kademlia's interesting bugs live under **churn** — nodes joining and leaving
*during* a lookup, buckets splitting while being read, republish racing expiry.
An integration test against a handful of live peers will not produce them, and
will pass consistently while the design is wrong.

What is worth building before the DHT is finished:

- A **seeded, deterministic simulator**: virtual time, explicit message queue,
  no wall clock and no threads. Every run reproducible from its seed.
- Injectable **churn** (join/leave rates), **partition**, and **eclipse** (an
  attacker who controls a fraction of routing-table entries).
- Properties worth asserting rather than examples: a lookup started from any
  live node converges to the k closest live nodes; a stored record is
  retrievable after `f` fraction of nodes leave; bucket invariants hold after
  arbitrary churn.

**One cautionary finding, from a review of Narwhal I ran next door:** their
leader-election function is compiled out under `cfg(test)` and replaced by a
constant stub, so the exact function whose behaviour matters is **never
exercised by any consensus test**. The DHT equivalent would be stubbing the
distance metric or the node-selection function to make tests deterministic.
Whatever the DHT's core selection function is, the tests must run *it* — make
determinism come from a seeded RNG, never from replacing the algorithm.

---

## 8. Relationship to konserve-sync and urd

**VERIFIED:** `konserve-sync` already implements `PSyncStrategy` with a
timestamp-diff handshake (`konserve-sync/src/konserve_sync/pubsub.cljc:224-290`),
and `urd`'s kabel adapter implements the same protocol with three of its four
methods as deliberate no-ops
(`urd-core/src/urd/consensus/networks/kabel.clj:18-50`).

That is the right shape and worth preserving:

- **Consensus topic** — real-time publish only; the handshake methods stay
  empty. There is nothing to hand a joining peer, because a consensus node's
  history is bounded by retention. (Verified in `urd`: every algorithm's
  durable store is truncated by the same retention that bounds its memory, so
  no node retains full history unless deliberately configured as an archive.)
- **Application/state topic** — this is where the handshake earns its keep, and
  it is konserve-sync's job.
- **DHT** — a third concern, and a good argument for it being its own namespace
  rather than a strategy on an existing topic.

A DHT makes the archive-node story better: content routing lets a node ask *who
has this* rather than requiring every peer to retain everything.

---

## 9. Suggested order

Revised after the reference reviews in `.internal/reference/`. The headline
change: **there is no discovery DHT in the plan any more.** kabel peers are
addressed by WebSocket URL, so peer discovery reduces to distributing URLs —
and roughly 55% of hyperdht is NAT traversal that a URL-addressed transport
does not need (`.internal/reference/hyperswarm.md`). Content routing over
konserve roots survives; peer routing as a DHT does not.

1. **Identity (§3) — DONE.** `src/kabel/identity.cljc`. Ed25519 with
   `peer-id = SHA-256("kabel/peer-id/v1" ‖ pubkey)`, projected to a UUID so
   nothing else in kabel's wire format changes. JDK-native on the JVM (no
   dependency), `@noble/ed25519` in ClojureScript.
2. **Deterministic simulator (§7) — DONE.** `src/kabel/sim.cljc` and
   `src/kabel/sim/rng.cljc`. Virtual clock, seeded xorshift128 that produces
   identical streams on both platforms, explicit event queue with a total
   order, injectable latency/loss/partition/crash/churn. Nodes are pure
   `(state, event, ctx) -> {state, actions}` machines, following partisan's
   `partisan_broadcast_engine` seam, each with its own seeded rng — so the real
   selection code runs rather than a stub.
3. **Leaf/dialable split (§4).** Confirmed as structural rather than a
   preference: partisan's HyParView *enforces* view symmetry by dialing the
   reverse link, so a non-dialable node can never enter an active view
   (`.internal/reference/partisan.md`), and hyperdht instantiates its record
   store only on nodes with a proven public address. HyParView therefore runs
   **among dialable nodes only**, with leaves attached beneath it.
4. **L1 membership — DONE.** `src/kabel/membership.cljc`. Address book, the
   `proven`/`attempts` priority ladder, asymmetric backoff, dial budget,
   duplicate-dial tie-break, and peer exchange — so seeding one address
   assembles the mesh. Group diversity caps (§2.2) and hard ceilings on every
   collection are ours rather than hyperswarm's. Tested in the simulator under
   partition, crash, 30% loss and churn.

   Two things worth recording from building it:

   - **The tie-break's property is *agreement*, not exclusivity.** Both peers
     must reach the same verdict on the same connection — from one side it is
     outbound, from the other inbound. A first attempt at the test asserted
     they must *disagree*, which is the intuitive reading and is wrong; the
     rule then looks broken when it is correct. Given hyperswarm ships two
     subtly different copies of this rule, the property test is worth more
     than the transcription.
   - **A long backoff tail delays partition healing.** With the default ladder
     topping out at 10 minutes, a partition that lasts long enough to exhaust
     the ladder leaves both halves waiting out a 10-minute timer after the
     network is physically healed. That is the right trade against hammering a
     dead peer, but it means the ladder cap is a *healing-latency* parameter as
     much as a politeness one, and it should be configuration rather than a
     constant.
5. **L2 dissemination — DONE.** `src/kabel/interval_set.cljc`,
   `src/kabel/dissemination.cljc`, composed with L1 in
   `src/kabel/overlay.cljc`. Interval-set seen tracking
   (`{origin, epoch, monotonic-seq}`) with no TTL and no GC, hop TTL, no-echo
   forwarding, interest filtering with relay nodes, authorisation at every
   hop, and anti-entropy repair driven by the same interval sets. No scoring,
   no PX, no lazy gossip.

   The headline test is the one replikativ never had: twelve nodes seeded with
   a single address assemble a mesh, and a publish from any of them arrives at
   all twelve. A node isolated during a publish recovers it purely by digest
   exchange, with no republish.

   Three things worth recording:

   - **Interval sets make the seen state genuinely bounded**, not merely
     bounded-in-practice. A node that has received an origin's whole stream
     holds one range for it, so memory is `O(gaps)` rather than
     `O(messages)` or gossipsub's `arrival-rate × TTL`. A test asserts one
     range after 5 000 messages, and another asserts the sets do not
     fragment under sustained publishing.
   - **Authorisation must not mark a message seen.** Recording a refused
     message would suppress a later authorised copy of the same id as a
     duplicate — a refusal that silently poisons the message id. The check
     therefore runs *before* the duplicate test, and a test asserts the
     refused id is still unseen.
   - **Relay nodes are what keep a topic's overlay connected.** With interest
     filtering alone, an uninterested peer sitting between two interested ones
     cuts the topic in half. Relays are the dialable backbone of §4 in another
     guise.
6. **Transport binding — DONE.** `src/kabel/overlay/runtime.cljc`, plus
   `src/kabel/store/{protocol,memory}.cljc`. A middleware/driver split: the
   middleware is per-connection (handshake, registration, frame funnelling,
   passthrough), the driver is per-peer (event loop, timers, dialing).
   Integration-tested against real http-kit peers over a real WebSocket.

   Four things this turned up that only real transport could:

   - **kabel connections are anonymous.** `client-connect!` takes a `peer-id`
     and never sends it; `kabel.ring-ws/create-ws-handler!` names its copy
     `_peer-id`. So the overlay must run its own identity handshake — which
     turns out to be a feature: a dial is issued to an *address* but addressed
     to a *peer id*, and the pending frame is released only once a peer proves
     it holds that id's key. An impostor at the dialled address registers under
     its own id and the dial times out. §2's eclipse defence falls out of
     self-certifying ids rather than being bolted on.
   - **Middleware installation is asymmetric.** `kabel.peer/connect` reads
     `:middleware` from the peer atom per connection, so swapping it after
     construction works for clients. `server-peer` closes over its `middleware`
     *argument* in the accept loop, so swapping the atom silently does nothing
     and every inbound connection runs the middleware the peer was built with.
     `runtime/deferred-middleware` exists for this; the failure mode is a
     server that accepts connections and quietly ignores its own protocol.
   - **The wire form must be codec-agnostic.** With no serialization
     middleware, `kabel.binary/to-binary` falls back to `pr-str` /
     `edn/read-string`, and a byte array renders as `#object[[B …]`, which EDN
     cannot read back. A signed identity record sent raw is therefore silently
     unreadable on exactly the *default* codec. `kabel.identity/record->wire`
     hex-encodes the byte fields; a test asserts the raw form fails and the
     wire form survives.
   - **A closing socket has to reach the state machine, and that *is* the
     reconnect.** kabel has no reconnect of its own. A dropped transport now
     raises a `:disconnected` event, membership drops the connection, and its
     ordinary dial policy redials — so reconnect is not a separate mechanism
     that could disagree with the dial policy, it is the dial policy. The
     failure mode without it is quiet and permanent: a peer believed to be
     connected is not a dial candidate, so a state machine that never hears
     about the close never redials either. A dropped connection is charged a
     backoff only if it never reached `:proven-ms` — a flapping peer is
     throttled, a proven peer that restarts is redialled promptly.
   - **The address book needed addresses.** Modelling a dial as a message let
     the simulator skip the one thing a real dial requires. `[:connect]` is now
     its own action carrying an address and a first frame, and a peer that
     dials announces its own addresses — otherwise an inbound peer is known but
     unreachable, and the mesh degenerates into a star around whoever dialled
     first.

7. **Content routing and transfer — FIRST CUT.** `src/kabel/content.cljc`,
   plus `src/kabel/store/konserve.cljc` behind the `:konserve` alias for
   durable state. The BitTorrent shape rather than the DHT shape: announce
   holdings to neighbours, query on a miss, fetch the value, **verify it
   against its `hasch` key**. Provider records carry a TTL, are republished on
   a maintenance timer, and are capped per key, per provider and overall (§6).

   What makes untrusted providers safe is the hash, not a signature: a block is
   accepted only when `(hasch/uuid value)` equals the key it was requested
   under, which is precisely konserve's own addressing under datahike's
   `:crypto-hash? true`. A liar costs one round trip. Announcements are
   credited only to the peer that sent them, which closes the obvious
   amplification ("peer X has everything") without signing every record.

   **Honest limits, all deliberate:**

   - **Reach is two hops.** Announce to direct peers; a query is answered from
     a neighbour's own holdings *and* what it has been told. Beyond that this
     returns nothing. That is the point at which a Kademlia lookup would
     replace the query — without changing the transfer protocol.
   - **No chunking.** A value moves whole, so this is right for roots, commits
     and index nodes and wrong for a large blob. Piece selection, rarest-first
     and endgame mode belong to a chunked transfer.
   - **No subtree fetch.** One value per round trip, which is the wrong shape
     for walking a `persistent-sorted-set` DAG over a network. See below.

   The bug worth recording, because no counter would have shown it: a fetch for
   content nobody holds used to sit in the want list **forever**, holding one of
   `:max-outstanding` slots. After enough such fetches a peer could never fetch
   anything again — silent, permanent, and only visible by asserting that the
   slot is reusable afterwards, which is now a test.

8. **Public-key authenticated pub/sub — DONE.** Every publish is signed by its
   origin and verified **at every hop** before it reaches the state machine.
   The message carries the origin's public key, so verification is
   self-certifying: because a peer id *is* the hash of its public key, a
   message that has travelled five hops from a peer we have never met is still
   checkable with no directory and no key lookup. Costs ~192 bytes of hex per
   publish.

   The signature covers `origin`, `epoch` and `seq` as well as `topic` and
   `payload`, so a relay cannot lift it onto another message or replay it at a
   different position — which for a database root is precisely a rollback.
   Canonical bytes come from `hasch/edn-hash` rather than `pr-str`: map key
   iteration order differs between platforms, so a `pr-str` signature would
   verify on the JVM and fail in the browser for any payload containing a map.

   Signing and verification live in the **runtime**, not the state machine —
   they are async on ClojureScript (WebCrypto), and a state machine that had to
   await them would stop being one. Everything reaching the machine is
   therefore already authentic, the same arrangement as TLS terminating below
   an application.

   **Authentication is not authorisation, and the distinction matters most
   here.** A signature answers *who said this*. Whether that key may set the
   root of *that* database is `:authorize-fn`'s question, evaluated at every
   hop. Keeping them separate is §1's invariant again: the overlay establishes
   identity, and never decides who counts.

9. **Batched subtree fetch — DONE, and measured.** `:content/want-tree` in
   `src/kabel/content.cljc`. The requester names a root and what it already
   holds; the **provider** walks its own store breadth-first and streams the
   nodes. One request plus a stream, instead of two round trips per node.

   Measured in the simulator at 50 ms RTT, pipeline depth 16:

   | nodes | per-key | subtree | speedup |
   |---:|---:|---:|---:|
   | 85 | 500 ms | 50 ms | 10× |
   | 781 | 4 800 ms | 100 ms | 48× |
   | 4 681 | 29 200 ms | 100 ms | **292×** |

   Per-key grows linearly; subtree is **flat at 1–2 round trips regardless of
   size**. The 4 681-node figure extrapolates to ~62 s for 10 000 nodes, which
   confirms the earlier projection — and corrects it: it is *two* round trips
   per node (a provider lookup, then a block request), not one.

   **The simulator models latency, not bandwidth.** So the flat 100 ms is the
   latency floor; real transfer adds the bytes. Subtree fetch converts a
   round-trip-bound problem into a bandwidth-bound one — for a 10 000-node
   index at ~4 KB a node, roughly 40 MB, or ~3 s on 100 Mbit/s. That is the
   honest claim, not "under a second".

   Design points worth keeping:

   - **Breadth-first, not depth-first.** A parent always precedes its children
     so the receiver verifies incrementally; it mirrors datahike's bulk BFS
     warmup against S3; and truncating a BFS leaves a *frontier* that is a
     clean set of subtree roots to resume from, whereas truncating a DFS
     leaves a path.
   - **The diff is free.** `have` prunes whole subtrees, and because
     persistent-sorted-set shares structure, two roots differ by O(changed)
     nodes. That is konserve-sync's timestamp diff in its untrusted-peer form:
     timestamps are unverifiable claims, content addresses are verifiable.
   - **Residency bound.** `:max-tree-nodes` caps the walk, because
     `Branch.java:715-731` documents that a cold-tree probe can restore 100% of
     blobs at low fanout — "a resident set of the WHOLE tree". A stranger's
     one-line request must not materialise an entire index; it gets a truncated
     walk plus a frontier to resume from.
   - **`:addresses-fn` is injected**, exactly as konserve-sync's walkers inject
     it, so kabel needs no dependency on persistent-sorted-set, konserve or
     datahike, and an object-storing consumer can pass its own projector.
   - **Every node is verified** before it is kept. A batch is precisely where
     accepting on faith would be cheapest and worst.

10. **Immutability and durability — DONE.**

    **`:immutable?` decides what may leave the peer.** konserve already records
    it (`konserve/core.cljc:353`, `:421`), and it is the honest signal: an
    immutable content-addressed value is safe to hand a stranger and safe for
    them to verify; a mutable pointer is neither — its key is not its hash, so
    verification would fail, and its value changes under anyone who cached it.
    A mutable value is therefore held locally but never announced, never
    served, never named in a `:content/found`, and reported as *frontier* by a
    tree walk rather than streamed. Fetched blocks derive the flag from having
    verified; seeded ones take the caller's assertion.

    **Verified content is handed to durable storage** via a `:persist` action,
    so the bounded working set is a cache rather than the system of record. The
    simulator counts the action (the protocol's responsibility); the runtime
    writes it through to a `PPeerStore` (the runtime's).

    **`warm!` makes a restarted peer a provider again.** Identity and the
    address book already survived a restart; the content set did not, so a
    restarted peer silently stopped providing everything it still held on disk.
    Warming is deliberately explicit and caller-driven: the working set is
    bounded, and which of a large store's keys are worth holding resident is
    the caller's decision. Tested end to end against a real konserve store.

11. **Chunking — DONE, and it needed no protocol.** `src/kabel/chunk.cljc`.

    A chunked value is a **manifest node naming piece nodes**, and both are
    ordinary content-addressed blocks — so `kabel.content` transfers them with
    what it already has. The manifest lists its pieces under `:addresses`, which
    is exactly what the tree walk follows, so `:content/want-tree` moves a
    20 KB value and all twenty of its pieces in one exchange while the content
    layer has no idea the value is chunked. This is BitTorrent v2's and
    UnixFS's arrangement: a large file is a DAG whose leaves are chunks, not a
    second transfer protocol alongside the first.

    Falling out of content addressing: **deduplication** (identical pieces
    collapse to one block while still appearing at every offset) and
    **resumability** (`missing-pieces` against what you hold, since the small
    manifest arrives first).

    `assemble` refuses every way of being wrong rather than producing plausible
    garbage: a missing piece, a piece that does not hash to its address, and —
    the one a per-piece hash cannot catch — a total length disagreeing with the
    manifest, which is what a truncated or padded transfer looks like when
    every individual piece verified.

    **Not done: rarest-first, endgame mode, per-piece choking.** Those are
    BitTorrent's answers to fetching from *many* peers at once; they need a
    swarm, and the pieces being ordinary blocks means they can be added in
    `kabel.content` later without touching this representation.

    Two test-quality notes worth keeping. `(payload 256)` was byte-identical to
    the first piece of `(payload 1000)`, so two "tampered piece" tests were
    substituting a piece for itself and passing for that reason — hence
    `other-bytes`. And the round-trip tests only proved each platform agreed
    with *itself*; a piece address is `hasch/uuid` over a JVM `byte[]` on one
    side and a `Uint8Array` on the other, so `addresses-agree-across-platforms`
    pins the actual values. It passes on both, which turns "hasch presumably
    agrees" into a checked fact.

12. **Topic ranges — DONE.** `src/kabel/topics.cljc`. A boolean relay flag is
    all-or-nothing; measured on a 40-node mesh, relaying everything costs **4×**
    relaying nothing at 10% subscribers, and neither extreme is what a
    deployment wants. A range is a vector prefix (`[]` = everything), so a relay
    can carry `[:db "alice"]` and nothing else. Peer exchange gossips each
    peer's ranges and dialling prefers peers whose ranges cover what we want —
    without that, membership picks peers blind to what they carry, which is the
    blindness that forces a discovery layer at scale.

    Ranges say what you **relay**; subscriptions say what you **deliver**.
    Conflating them would hand a relay every message under a prefix it merely
    agreed to forward.

13. **Verifiable roots — DONE.** `src/kabel/roots.cljc`. A signed root proves
    who said it, never that it is current; a signature is valid forever, so an
    old signed root is a valid signed root. Monotone pinning, hash chaining, and
    equivocation-as-evidence — all local, none needing a clock, a quorum or a
    serialiser. This is AT Protocol's inductive verification, whose saving is
    **storage, not crypto**: their relay went from 16 TB to ~21 GB by holding
    one hash per producer. The cost, which they paid: cheap verification makes
    archival somebody else's job, so a `:gap` is reported rather than papered
    over.

14. **Sealed content — DONE.** `src/kabel/sealed.cljc`, and it needed no
    protocol. A sealed block is an ordinary content-addressed block whose value
    is `{:children [...] :ciphertext ...}`: verification is untouched, the tree
    walk streams it because children are in the clear, and the address is
    unguessable without the key. Tahoe's verify-cap tier, including the
    traversal half Tahoe designed and never shipped. It leaks the **shape** of
    the DAG, by construction.

15. **Crypto consolidated into geheimnis — DONE.** `kabel.identity` carried its
    own Ed25519, byte helpers, SHA-256 and CSPRNG; geheimnis had all of them.
    214 lines deleted, and the two signature implementations were verified
    byte-identical first, so no wire format moved. geheimnis's are better in
    three ways: a sanctioned CSPRNG, a constant-time comparison, and `sha256`
    re-exported from hasch so the stack has one hash implementation rather than
    a third. Contributed back: an injectable `@noble/ed25519` fallback for
    runtimes whose Web Crypto lacks the curve (Chrome only shipped it mid-2025).

## Still open

**Moderation and abuse** — researched (`.internal/reference/moderation.md`), a
position formed, nothing built. The position: identity costs one key
generation, so bans are structurally unenforceable and the only controls that
can work are the **grant side** (`:authorize` at every hop, which already
carries more than any equivalent hook in Mastodon, Synapse or atproto) and the
**receiver side** (labels you subscribe to). Open work, smallest first:

- the foreclosed-capabilities list, written into the docs rather than
  discovered — no suspension, no ban-evasion resistance, no deletion, no global
  view, no in-protocol appeals;
- labels as signed content on `[:labels <labeler> <subject>]`, which needs
  almost no new protocol — the same finding as chunking;
- the enforcement seam: only a labeler the *client* privileges may turn a label
  into a takedown, copying atproto's `;redact`;
- rate limiting keyed on the **connection**, not the identity — Synapse's
  `rc_federation` shape (sleep → queue → reject). Token buckets on a free
  identity are theatre;
- a relay narrowing its `:carries` when its operator goes quiet — Mastodon's
  dormant-admin idea, expressible directly in ranges.

**Deferred with named thresholds**, none of them yet reached:

- **key rotation mechanism** — the format landed (§3 of `KEY_ROTATION.md`); the
  chain, thresholds and forward validation did not;
- **RBSR** (range-based set reconciliation) for reconciling unordered
  content-addressed sets — ranked *above* Kademlia, because interval sets only
  work where senders number their own messages;
- **Kademlia and per-topic meshes** — they arrive together, at the point where
  relay-everything stops being affordable;
- **Plumtree** — a degree-fold bandwidth win within a carried topic; needs a
  traffic profile we do not have;
- **peer scoring** — deprioritised deliberately. A formal analysis (IEEE S&P
  2024) synthesised an attack on Eth2.0's gossipsub parameters where
  misbehaving peers never forward yet keep positive scores; the mechanism is
  not the security property, the parameters are. The labeler model gives
  receivers a policy hook without that fragility.

**Landing** — the code exists and nothing runs on it. README and an example;
a geheimnis release so `:overlay` can point at Maven rather than a local root;
konserve onto the modern geheimnis tree; and **recovering replikativ on the
overlay**, which is the actual proof and the goal this began with.

## 10. Things I am not confident about

Stated plainly rather than glossed:

- **Whether kabel should own a DHT at all**, versus depending on an existing
  one. I have not surveyed the Clojure/JS options, and "write a Kademlia" is a
  larger commitment than it looks — the paper is short and the operational
  reality is not.
- **The right k, α and bucket-refresh parameters** for this deployment. These
  are empirical and depend on churn; the Kademlia defaults (k=20, α=3) are a
  starting point, not an answer.
- **NAT traversal.** I have said browsers cannot be DHT nodes, which is
  certain. I have *not* thought through hole-punching for non-browser peers
  behind NAT, and that materially affects how many dialable nodes a real
  network has.
- **Whether content routing needs a DHT at all initially.** For a small
  federated deployment, asking every connected peer "do you have this" may be
  sufficient and much simpler. The DHT earns its complexity at a scale worth
  confirming you are at.
