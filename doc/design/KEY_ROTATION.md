# Key rotation and revocation — a wire-format proposal

Status: **proposal, nothing implemented.** Written after reviewing did:plc,
TUF, UCAN and SSB meta-feeds (reports in `.internal/reference/`).

Labels as in `DHT_DESIGN.md`: **VERIFIED** (read in source), **RECOMMENDED**
(a position, with the reason), **OPEN** (a real decision).

---

## Status: the format landed, the mechanism did not

**IMPLEMENTED** (`src/kabel/identity.cljc`): genesis records, `peer-id =
H(genesis)`, the 32-byte authority id with the UUID demoted to routing, the
cold/hot key split, the revocation commitment, and `genesis-authorises?`
replacing `owns-id?`. Publishes carry the origin's genesis, so verification
stays self-certifying with no lookup.

**NOT IMPLEMENTED** (§3, §4): rotation records, thresholds above 1, forward
validation, priority nullification, equivocation proofs. A genesis with one
rotation key and threshold 1 is the "kill and recreate" design; adding real
rotation later is a rotation, not a format change.

**One deviation from §7 worth recording.** That section proposed distributing
genesis at the handshake and looking it up per message. Implementation went the
other way: the genesis rides in the publish credentials (~250 bytes of hex).
The reason is that a lookup would have broken a property worth more than the
bytes — a publish from a peer five hops away, whom the verifier has never met,
is still checkable with no fetch and no cache. Omitting the genesis when the
receiver is known to have it stays available as a pure optimisation.

---

## 0. Why this is urgent and why it is cheap right now

Today a compromised key is unrecoverable and an identity is permanently bound
to one keypair. That is a *format* problem, not a feature gap, and the four
reviews agree it is the one thing that cannot be fixed after deployment.

SSB is the worked example of paying late. One Ed25519 key carried five roles
with zero indirection; the retrofit cost a new feed format, a new binary
encoding, a migration spec, and a **permanent** dual-identity regime where the
classic key still anchors the follow graph indefinitely, with the network-id
change deferred until "a majority of the network supports metafeeds."

Nothing is deployed here. The change is free today.

---

## 1. The finding all four reviews share

> **`peer-id = SHA-256(tag ‖ pubkey)` is the mistake.**

- **SSB**: precisely their fatal choice (`ssb-meta-feeds.md`).
- **did:plc**: `did = base32(sha256(dag-cbor(genesisOp)))[:24]` — the identity
  hashes a *genesis record naming a set of keys*, and that record self-verifies
  with zero external state.
- **UCAN**: under `did:key` the DID *is* the key; on compromise the attacker
  mints unboundedly and can revoke, irreversibly.
- **TUF**: authority is a *role* with a key set and a threshold; no single key
  is ever the identity.

**RECOMMENDED: the identity is the hash of a genesis record.**

---

## 2. Proposed genesis record

```clojure
{:kabel/version           "kabel/identity/v2"
 :kabel/rotation-keys     [pk0 pk1 pk2]   ; priority-ordered, COLD
 :kabel/rotation-threshold 2              ; how many must sign a rotation
 :kabel/operational-keys  [opk0]          ; HOT — signs gossip and handshakes
 :kabel/revocation-commit  H(secret)      ; §6
 :kabel/seq               0}

peer-id-bytes = SHA-256("kabel/peer-id/v2" ‖ canonical(genesis))
```

**Rotation keys are cold, operational keys are hot.** This is TUF's separation,
and its rationale is worth stating exactly: **roles are separated by required
online-ness, not by meaning.** A hot key must be present to sign every publish,
so it is the one that gets stolen; a cold key signs only rotations, so it can
live offline. Compromising the hot key must not be able to change who you are.

**Full-width peer ids for authority.** Both the TUF and SSB reviews flag that
our 128-bit UUID projection (`identity.cljc:172-188`) is "fine for routing, too
weak as a keyid inside a signed authority document."

**RECOMMENDED:** keep the 32-byte `peer-id-bytes` as the authority anchor and
demote the UUID to a *routing* identifier. Everything that appears inside a
signed record uses the full width.

---

## 3. Rotation

A rotation record chains to its predecessor by content hash:

```clojure
{:kabel/version    "kabel/identity/v2"
 :kabel/peer-id    <32 bytes>            ; the genesis hash, never changes
 :kabel/prev       H(previous record)
 :kabel/seq        n                     ; exactly prev+1
 :kabel/rotation-keys      [...]
 :kabel/rotation-threshold t
 :kabel/operational-keys   [...]
 :kabel/signatures [...]}
```

**RECOMMENDED, from TUF's root rotation** (`trusted_metadata_set.py:187-197`),
which is the only reviewed design built for peers offline across *multiple*
rotations:

1. Record `n+1` must independently satisfy the **old** record's threshold **and
   its own**, over the same canonical bytes. Signing with only the new keys is
   not enough — that is what stops a stolen key set from walking away with the
   identity.
2. `seq` must be exactly `prev + 1`. No gaps.
3. **Validation runs forward from genesis, even though retrieval walks
   backward.** The chain is retrieved by content address from newest to oldest;
   if it were *validated* in that direction an attacker could hand you a v9
   chaining to a fabricated v5.
4. **Intermediate records may be expired; only the final one's freshness
   matters.** VERIFIED in TUF (`:168-171, 229-231`) and it is exactly what makes
   multi-year offline catch-up work. Expired ≠ worthless.

TUF's 15-case rotation table (`test_updater_key_rotations.py:85-153`) is a
behavioural specification; **port it verbatim** rather than deriving our own
cases.

---

## 4. Conflict resolution: never latest-wins

**VERIFIED as a flaw in what we have:** `identity.cljc:334-340` documents
"a receiver keeps only the highest [seq] it has seen". It is not implemented
anywhere yet — which is lucky, because two reviews independently say the rule is
wrong. An attacker holding the key simply increments forever and always wins.

**RECOMMENDED — three rules, all clock-free and order-independent:**

1. **Higher-priority rotation key nullifies a suffix, unboundedly.** did:plc's
   fork-choice minus its 72-hour window: authority is read from the **fork
   point's** key list, and only a strictly-higher-priority signer may nullify.
   The window is dropped deliberately — see §5.
2. **Revocation is absorbing.** Any tip revoked ⇒ revoked, permanently. Taken
   from SSB's fusion-identity rule; a lattice state, not a race.
3. **Equal-authority forks are evidence, not a tie to break.** Two validly
   signed records at the same `seq` from the same key set are a
   **non-repudiable equivocation proof**. Retain both, gossip both, treat the
   identity as compromised. did:plc resolves this with a Postgres row lock; we
   have no serialiser and should not pretend otherwise.

---

## 5. Why no time window

did:plc has a 72-hour recovery window, and it is the mechanism I would most
strongly avoid copying:

- It is measured from an **unsigned, server-assigned** timestamp
  (`routes.ts:209`) — there is no signed counterpart, so it cannot exist
  without a directory.
- **A 72-hour DoS converts an availability attack into permanent identity
  theft.** Suppress the victim's recovery operation for three days and the
  attacker's takeover is final. The spec concedes the DoS but not this
  implication.
- Our peers are offline for long periods by design. A window they sleep through
  is a window that does not protect them.

Unbounded higher-key rollback is convergent, order-independent, needs no clock,
and is strictly better for long-offline peers.

---

## 6. Revocation without a directory

Every reviewed system fails here, and each fails differently:

| system | mechanism | what happens to a peer that never sees it |
|---|---|---|
| UCAN | content-addressed blocklist, delivery explicitly out of scope | fails **open**, by design |
| TUF | absence from the new root, pull not push | trusts the compromised key until expiry, then fails closed |
| did:plc | directory-served operation log | n/a — requires the directory |
| SSB | none at all | never learns |

**RECOMMENDED — the pre-image tombstone.** SSB proposed this in a 2021 meeting
note, never specified it, and never shipped it. Per that review it is **the only
primitive in the whole corpus that survives compromise without a directory**:

- Genesis commits to `H(secret)`.
- Revealing `secret` revokes the identity, absorbingly.
- **An attacker holding every signing key still cannot produce the pre-image.**

It composes with §2 at the cost of one field, and it is the only answer here
that does not assume somebody is online at the right moment.

**RECOMMENDED: revocations flood, they are not pulled.** They are small,
absorbing, and idempotent — exactly the payload our dissemination layer is
already best at.

---

## 7. Making it affordable

**VERIFIED cost** (measured in the UCAN review): Ed25519 verify is 0.60 ms on
JDK 25, and a naive depth-3 chain per hop is ~2.4 ms per message per hop.
Untenable for gossip. Rotation also turns `verify-gossip`
(`overlay/runtime.cljc:181-199`) from a self-contained check into a key-set
lookup.

**RECOMMENDED — separate distribution from checking:**

- **Distribute** identity records at the connection handshake, where we already
  exchange them. Verify the chain **once**, cache the resulting key set.
- **Check** per hop against the cached set: a map lookup, no signature
  verification of the chain on the hot path.

This is UCAN's memoisation observation generalised, and it is what makes both
rotation and delegation affordable at gossip rates.

---

## 8. Authorisation, and a bug to fix first

**VERIFIED bug.** `:authorize-fn` has two incompatible signatures under one
name:

- `kabel.pubsub` — `(fn [principal topic])` (`pubsub.cljc:309, 363`)
- `kabel.dissemination` — `(fn [topic origin])` (`dissemination.cljc:250-255`)

A consumer passing one predicate to both binds `principal` to a topic. It fails
silently, in whichever direction the predicate happens to lean.

**RECOMMENDED:** one map argument — `(fn [{:keys [principal topic payload]}])`.
It cannot be misordered, and it gives dissemination the payload it needs to
answer *which* database's root is being set rather than merely which topic.
`kabel.pubsub` is released, so this is a public API change and **OPEN** for
Christian to approve.

On delegation more broadly: **adopt the model, not UCAN the format.** Its chain
check is only three rules and its root trust is structural (`iss == sub ==
resource`) — the self-certifying property we already have. But attenuation is
barely specified (the word appears once in the delegation spec's abstract and
zero times in the invocation spec), so the command-prefix rule would be ours to
write; and its ±60 s clock-drift buffer fails **open**, which is meaningless for
suspended laptops.

---

## 9. Open decisions

1. **Threshold size.** `1-of-1` is simple and means compromise is fatal; `2-of-3`
   survives one stolen key but needs three key custodies for a solo operator.
   TUF's whole argument is that thresholds beat windows because they do not
   depend on anyone being online. **OPEN**, and it is a deployment question as
   much as a design one.
2. **Retention of the rotation chain — and this collides with GC.** TUF assumes
   an archive holding every historical root forever; the review calls that
   "kabel's worst dependency". Chaining rotation records into the content DAG
   solves retrieval, but datahike's optional GC can erase history — the same
   tension already noted for fork detection. If the chain is collected, a
   long-offline peer cannot validate forward from genesis. **OPEN:** archive
   nodes, a compacted checkpoint, or an accepted bound on how long a peer may
   be away.
3. **Whether operational keys need their own sequence** separate from rotation
   records, so a hot-key roll does not require cold keys.
4. **Migration.** `v1` records exist only in tests, so v2 could simply replace
   them. **RECOMMENDED:** replace, and never ship a v1 reader.
