# Content addressing, encryption and capabilities — a note for konserve

Status: **design input, not a specification.** Written while designing an
optional overlay/DHT for kabel. Nothing here is implemented.

Claim labels follow `DHT_DESIGN.md`:

- **VERIFIED** — I read the code and checked it. File:line given.
- **RECOMMENDED** — a design position argued for, with the reason.
- **OPEN** — a real decision that should not be made by default.

---

## 0. Why this note, and why now

The kabel overlay design has a layer L3 — *content routing*: "who has the blob
at `<hash>`". It is motivated by konserve being content-addressed and by the
prospect of peers fetching whole databases from each other, privately.

That makes one konserve decision urgent and irreversible:

> **What is the address that goes into the DHT, and who is able to compute it?**

If the address is derivable from the plaintext, then publishing a provider
record tells the network what you are storing. If the address is derivable only
from a secret, then a short shareable token can name *and* unlock a database.
The second is strictly better and is not more work — **but it has to be decided
before encryption ships**, because it changes the storage index, which is the
name of every blob on disk.

This note is deliberately scoped to konserve + hasch + datahike. It does not
depend on any DHT existing.

---

## 1. What we have today — verified inventory

**Storage index is an unkeyed hash of the plaintext key.**
`konserve/src/konserve/impl/defaults.cljc:45-46`:

```clojure
(defn key->store-key [key]
  (str (uuid key) ".ksv"))
```

`uuid` is `hasch.core/uuid` — no secret, no salt. The blob's filename *is* the
hash of the key. VERIFIED.

**Encryption is unauthenticated AES-CBC with a store-wide key.**
`konserve/src/konserve/encryptor.cljc` builds `AESEncryptor` and calls
`geheimnis.aes/encrypt`, which is
`AES/CBC/PKCS5Padding` on the JVM and `goog.crypt.Cbc` + `Pkcs7` in
ClojureScript (`geheimnis/src/geheimnis/aes.cljc:19-34`). There is **no MAC and
no AEAD**. VERIFIED.

**The AES key has no KDF.** `geheimnis/src/geheimnis/aes.cljc:26` derives key
material as `(take 32 (edn-hash key))` — a single hasch pass over an arbitrary
EDN value. No PBKDF2/scrypt/Argon2, no iteration count, no work factor.
VERIFIED.

**Ciphertext is non-deterministic.** `encryptor.cljc:46` computes the per-value
salt as `(edn-hash (uuid))` — a *fresh random* UUID per serialization. The IV is
then derived from `["initial-value" salt key]` (`:25-26`). So encrypting the same
value twice produces different bytes. VERIFIED.

**Metadata is encrypted too.** Both the value and the meta path go through
`((encryptor (:encryptor config)) (compressor serializer))` —
`defaults.cljc:71` and `:75`. Good, and worth preserving. VERIFIED.

**The blob header is cleartext.** 20 bytes: layout version, serializer id,
compressor id, encryptor id, meta size, 12 spare
(`konserve/src/konserve/impl/storage_layout.cljc:11-30`). It has to be — it says
how to decrypt. It leaks only format choices. VERIFIED, and acceptable.

**datahike already has content addressing *and* a merkle audit chain — behind a
flag that is off by default.**

- `datahike/src/datahike/index/persistent_set.cljc:327-332` — `gen-address`
  returns a content-addressed UUID when `crypto-hash?` is set, otherwise
  `(squuid)`, an ordinary sequential UUID.
- `branch-content-uuid` (`:303-320`) and `leaf-content-uuid` (`:322-325`) are the
  merkle node hashes; the branch hash deliberately folds in buffered slot diffs
  so "a tampered slot diff would otherwise be invisible to the merkle audit".
- `walk-pss-address!` (`:342-367`) re-reads a node from konserve, recomputes its
  content UUID and confirms it matches — **an integrity verifier that already
  exists**.
- `datahike/src/datahike/audit.cljc` walks the commit DAG backwards recomputing
  cids, with `:deep? true` cross-checking merkle roots recomputed from storage.
- `datahike/src/datahike/config.cljc:34` — `*default-crypto-hash?*` is **`false`**.
- hitchhiker-tree does the same under `hash-nodes?`, which datahike *does* set:
  `datahike/src-hitchhiker-tree/datahike/index/hitchhiker_tree.cljc:226` passes
  `true`; the backend default is also `true`
  (`hitchhiker-tree/src/hitchhiker/tree/bootstrap/konserve.cljc:104-107`).

All VERIFIED. This is much better news than I expected: the hard part —
content-addressed nodes with a verifiable merkle structure — is built. It is
simply not the default.

**The cost of turning it on is documented and real.** `datahike/doc/gc.md:142`
and `:269` state that GC address recycling requires `:crypto-hash? false`;
`src/datahike/online_gc.cljc:214-215, 246-247` implements exactly that fallback.
So **p2p-shareable stores give up freelist recycling and fall back to deletion
mode GC.** VERIFIED. This is a genuine trade-off, not a bug, and it should be
stated in whatever we write for users.

---

## 2. The four problems, in priority order

### P1 — The storage index is computable by anyone who guesses the key

This is the one that matters for p2p. `store-key = hasch(key)` with no secret
means:

- A DHT provider record for a datahike node **names the node's content hash**,
  because for `:crypto-hash? true` stores the konserve key *is* the content hash.
  Publishing "I provide `<hash>`" therefore tells the network what you hold.
- Anyone who has ever seen a database can test whether you are storing it
  (confirmation-of-file), and can enumerate providers of it.
- A recipient holding only a decryption key still **cannot locate** anything,
  because locating requires knowing the addresses.

Encryption as currently designed protects values and metadata. It does not
protect the *key space*, and the key space is precisely what a DHT publishes.

### P2 — Unauthenticated CBC in a path that will fetch from untrusted peers

CBC without a MAC is malleable: a peer serving you a block can flip plaintext
bits by flipping ciphertext bits in the preceding block, and decryption will
succeed. Today konserve is a local store and the threat model is a disk; the
moment blocks arrive from strangers, it isn't.

The mitigating fact is P2's saving grace: under `:crypto-hash? true` datahike
*can* detect this, because `walk-pss-address!` recomputes the content UUID after
decryption. But that is a datahike-level audit pass, not something konserve does
on every read, and konserve users who are not datahike get nothing.

### P3 — `edn-hash` is not a KDF

`(take 32 (edn-hash key))` is a single hash. If a human ever types the key —
and "share a database with a password" is exactly the feature being
contemplated — it is brute-forceable at hashing speed. A random 256-bit key is
fine; a passphrase is not.

### P4 — Random per-value salt forecloses ciphertext addressing and dedup

Because the salt is a fresh random UUID per write, the same value written twice
yields different ciphertext. Consequences: no ciphertext-level content
addressing, no dedup between two peers holding the same block, and re-writing an
unchanged node changes its bytes (bad for rsync-style transfer and for any
"do I already have this ciphertext" check).

---

## 3. Proposal — a konserve capability

**RECOMMENDED.** Revised against `.internal/reference/tahoe-lafs.md`, which read
Tahoe's actual construction. The result is *simpler* than the three-tier chain I
first sketched here, and the reason is the finding worth internalising:

> **Interpose the secret once, at key derivation, and every identifier
> downstream becomes opaque for free.**

Tahoe does not carry a separate index key. The storage index is a one-way tagged
hash *of the content key*, so anyone with the key can compute the index, and
nobody else can. VERIFIED in `uri.py:62-167` and `hashutil.py:102-108`; the
attack it defeats is named in Tahoe's own `docs/convergence-secret.rst:28-46`
(confirmation-of-file, and its sibling "Learn-the-Remaining-Information").

### Immutable blobs (index nodes — the common case)

```
  K  = H_tagged("konserve/blob/key/v1" ‖ S_conv ‖ codec-id ‖ plaintext)[:32]
  SI = H_tagged("konserve/blob/si/v1"  ‖ K)[:16]          ; storage index
  Hc = H_tagged("konserve/blob/ct/v1"  ‖ header ‖ ciphertext)
```

- `read-cap   = kv1:B:<K>:<Hc>:<size>`   — locate, decrypt, verify
- `verify-cap = kv1:BV:<SI>:<Hc>:<size>` — locate and verify, **cannot decrypt**

`S_conv` is a per-store random convergence secret. It scopes dedup to holders of
the same secret; Tahoe treats *global* dedup as a bug rather than a feature, and
turning convergence off is simply "use a random key"
(`upload.py:1787-1796`). Putting `codec-id` inside the key hash is theirs too —
it stops two incompatible encodings colliding on one address.

**Deterministic AEAD with nonce 0.** Because `K` is derived from the plaintext,
it is already content-unique, so a fixed nonce cannot be reused across distinct
plaintexts. This is cleaner than the `HMAC(K_conv, content-hash)` salt I
proposed and needs no SIV mode. It fixes P4: identical plaintext ⇒ identical
ciphertext ⇒ blocks are content-addressable and dedup works.

**Integrity is checked entirely *before* decryption**, anchored in the cap
(`share.py:286-349`, `node.py:259-268`). For us the whole block/share/UEB hash
ladder collapses to the single `Hc`, because we are not erasure coding. A
verify-cap holder can therefore check, repair and re-serve blocks it cannot
read — which is exactly what makes untrusted providers safe.

### Mutable roots (the datahike root — the hard case)

```
  write-cap  = kv1:R:<sk>                  ; Ed25519 secret key
  read-cap   = kv1:RR:<readkey>:<pk>       ; readkey = H(sk)
  verify-cap = kv1:RV:<SI>:<pk>            ; SI      = H(readkey)
```

The signed root record covers
`seqnum ‖ salt ‖ ciphertext-hash ‖ codec-id ‖ prev-record-hash`. The
**ciphertext hash is mandatory** — Tahoe made it optional (#491) and an uploader
could make one cap resolve to two different files.

### Store token

```
  kv1:S:<network-id>:<readkey>:<pk>[:<S_conv>]
```

The `network-id` discriminator is there because its absence is one of Tahoe's
stated regrets — caps from one grid are silently meaningless on another.

### Storage index in konserve terms

```clojure
;; instead of (defn key->store-key [key] (str (uuid key) ".ksv"))
(defn key->store-key [store-secret key]
  (str (tagged-hash "konserve/si/v1" store-secret (uuid key)) ".ksv"))
```

where `store-secret` is *implied by the read-cap*, so it costs zero cap bytes.
This is the single change that fixes P1. Today's oracle is wide open precisely
because `(uuid key)` is an unkeyed hash over a structured, guessable datahike
key space.

---

## 4. What would have to change in konserve

1. `key->store-key` takes a store secret; no capability ⇒ today's `(uuid key)`,
   so unencrypted stores are unchanged. **This is a storage-layout change**:
   blob names change, so it needs a layout version bump, and there is no
   in-place migration other than rewrite.
2. `AESEncryptor` → an AEAD encryptor, new `encryptor->byte` id (2), keeping `1`
   readable for existing stores. `byte->encryptor` already gives us the version
   seam (`encryptor.cljc:74-80`). **The cleartext header goes into the AAD** —
   otherwise the format bytes are attacker-malleable.
3. Salt derivation moves from random to *nothing*: derive `K` from the
   plaintext, use nonce 0 (§3).
4. **Argon2id** replacing `edn-hash` as the KDF for human-supplied secrets
   (geheimnis `aes.cljc:16,25`). **OPEN:** whether it lives in geheimnis or
   konserve; the ClojureScript story is the constraint.
5. A `konserve.cap` namespace, and a `-verify-blob` protocol method so a
   provider can check a blob without the read key.
6. An **unencrypted signed verify-trailer**, so a provider can GC without the
   read key. Without this, an archive node cannot safely expire anything.
7. **Cross-platform AEAD is the implementation risk.** JVM has GCM in
   `javax.crypto`. In the browser the credible path is WebCrypto `SubtleCrypto`,
   which is **async-only** — `goog.crypt` gives us CBC and no AEAD. konserve's
   cljs path is already async, so this is probably fine, but it should be
   checked before committing. **OPEN.**

---

## 5. Interaction with datahike

- p2p sharing requires `:crypto-hash? true`. That is a supported mode with a
  working audit chain, so this is a documentation and defaults question, not new
  machinery.
- It costs GC address recycling (`doc/gc.md:142`). State the trade-off; do not
  hide it.
- `walk-pss-address!` and `datahike.audit/verify-chain` are the natural
  verify-cap-tier check. If we add `H(ciphertext)` to parent pointers, the same
  walk verifies *without decrypting* — which is what an archive/provider node
  needs.
- **RECOMMENDED, and this is the sharpening that makes L3 tractable:** the DHT
  holds provider records for **database roots**, not for every node. A million
  index nodes must not each be a DHT lookup — that is the wall IPFS hit and
  answered with bitswap/graphsync. Resolve the root through the DHT, then
  transfer the DAG over a direct connection to a peer you found. Thousands of
  records, not billions.

---

## 6. Open decisions

1. **Does konserve own capabilities, or does a layer above it?** Putting
   `K_idx` inside konserve is invasive; putting it above means every caller
   derives store-keys itself, and konserve stops being the thing that names
   blobs. I lean towards konserve owning it, precisely because the blob name is
   konserve's concern — but it is a real question.
2. **Per-store cap or per-key cap?** Tahoe caps name a file. A konserve cap
   naming a whole store is simpler and matches "share a database". Sharing a
   *subtree* would want finer granularity. Probably start store-scoped.
3. **Mutability and rollback — now mostly answered, and the answer is "not from
   Tahoe".** VERIFIED: Tahoe's `best_recoverable_version()` is max-seqnum over
   *observed* versions, and the MODE_READ stop condition's own comment is
   `"Good enough."` (`servermap.py:1091-1120`). Rollback needs only ≥k colluding
   servers — or one server that ran out of disk. **A quorum heuristic buys us
   nothing when there may be a single provider.** RECOMMENDED instead, and
   neither costs cap bytes:
   - **local monotonicity pinning** — persist the highest seqnum ever accepted
     for a root and refuse anything lower, so rollback is detectable by the
     victim rather than by a quorum;
   - **hash-chaining** root records via `prev-record-hash` (§3), so a served
     history is either continuous or visibly forked.

   Note also `storage/mutable.py:455-457` asserts `op == b"eq"` where the spec
   describes write-if-newer test vectors — the enforcement is not where the
   design says it is.
4. **KDF choice and where it lives** (see §4.4).
5. **A traversal / deep-verify cap tier.** Tahoe designed one
   (`mutable-DSA.txt:118-128`) and never implemented it. It is the single most
   relevant *unbuilt* idea for us: a cap that lets a peer walk and verify a whole
   DAG without reading any of it — i.e. exactly what an archive node wants.
   OPEN whether we need it in v1.

---

## 7. What not to do

- Do not ship encryption with the storage index left as `(uuid key)` and plan to
  fix it later. Blob names are the hardest thing to migrate, and the whole
  privacy story rests on this one line.
- Do not use plain AES-GCM with a derived deterministic nonce without the SIV
  construction. It looks equivalent and is not.
- Do not make the DHT address the *plaintext* content hash for convenience. It
  is the same mistake as (1) with a longer blast radius, because it is then a
  wire format on the network as well as on disk.
- Do not do per-block DHT lookups (§5).
- Do not make integrity optional anywhere in a cap. Tahoe's #491 (optional
  ciphertext hash tree) let an uploader produce one cap that resolved to two
  different files, and #1654 let an attacker "blindly flip bits in roughly
  2/3rds of the file". An "unverified" code point is not a performance option,
  it is a hole.
- Do not let knowledge of the storage index confer authority. Tahoe's #1528:
  knowing an SI was enough to *delete* shares. Under our design the SI is the
  DHT key, so it is public by construction — read/write authority must come from
  the cap, never from the address.
- Do not expect opaque storage indices to hide the DAG *shape*. Sizes, fan-out
  and access patterns still leak; the claim is confidentiality of content and
  unlinkability of names, not traffic analysis resistance.
- **Do not adopt from Tahoe:** erasure coding, the block/share hash trees and
  UEB (one ciphertext hash replaces all of it), write-enablers, leases,
  RSA-per-slot, or SDMF's per-child IV inflation.
