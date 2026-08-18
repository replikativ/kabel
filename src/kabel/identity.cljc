(ns kabel.identity
  "Self-certifying peer identity: a genesis record whose hash is the peer id.

  ## What is here and what is not

  This namespace owns *protocol*: the genesis record, peer-id derivation, which
  keys may sign for an identity, the revocation commitment, and signed identity
  records. It owns **no cryptography** — every primitive comes from
  `org.replikativ.geheimnis`, which had all of it already.

  That was not the original arrangement. kabel.identity carried its own
  Ed25519, byte helpers, SHA-256 and CSPRNG until it turned out geheimnis
  provided each of them, and better in three specific ways: `core/random-bytes`
  is the sanctioned CSPRNG rather than an ad-hoc one, `core/ct-equal?` is
  constant-time where ours explicitly was not, and `hash/sha256` is re-exported
  from hasch so the stack has ONE hash implementation instead of a third.

  The two Ed25519 implementations were checked against each other before the
  switch: same raw 32-byte keys, same DER envelopes, and byte-identical
  signatures that verify across both. So this is a deletion, not a migration —
  no wire format moved.

  ## The identity

      peer-id = SHA-256(\"kabel/peer-id/v2\" ‖ hasch(genesis))

  Hashing a *record* rather than a key is the decision the whole design rests
  on. SSB, did:key and UCAN all hash a key directly and none can undo it: the
  identity IS the key, so the key can never change and a compromise is
  terminal. A genesis names a SET of keys, so rotation is possible later
  without a format change.

  ## Async

  `generate-identity`, `sign` and `verify` return core.async channels, because
  ClojureScript's Ed25519 goes through Web Crypto and is async-only. `peer-id`
  is pure and synchronous on both platforms, because routing decisions need it
  in hand.

  ## Portability

  Byte operations work on byte arrays / `Uint8Array`, never on numbers.
  ClojureScript coerces bit operations to 32-bit signed integers, so a 256-bit
  identifier manipulated as numbers is silently wrong on exactly one platform —
  see `.internal/DHT_DESIGN.md` §5."
  (:require [hasch.core :refer [edn-hash]]
            [org.replikativ.geheimnis.codec :as codec]
            [org.replikativ.geheimnis.core :as gcore]
            [org.replikativ.geheimnis.hash :as ghash]
            [org.replikativ.geheimnis.sign :as gsign]
            #?(:clj [clojure.core.async :as async :refer [chan put! close!]]
               :cljs [clojure.core.async :as async :refer [chan put! close!]])
            #?(:cljs ["@noble/ed25519" :as noble]))
  #?(:clj (:import [java.util UUID])))

;; Web Crypto only reached Ed25519 in Chrome in mid-2025, so an older browser
;; cannot sign at all. geheimnis takes a fallback rather than requiring one, so
;; the ~4 KB is our choice to spend and not every geheimnis user's.
#?(:cljs (gsign/set-fallback! noble))

;; =============================================================================
;; Primitives — delegated
;; =============================================================================
;; Re-exported rather than wrapped, so there is one implementation and callers
;; here read the same as callers anywhere else in the stack.

(def ^:const key-size 32)
(def ^:const signature-size 64)
(def ^:const peer-id-size 32)

(def byte-buf   codec/zeros)
(def buf-length codec/blen)
(def bytes->hex codec/bytes->hex)
(def hex->bytes codec/hex->bytes)
(def utf8-bytes codec/str->bytes)
(def sha256     ghash/sha256)
(def random-bytes gcore/random-bytes)

(defn concat-bufs
  "Concatenate byte buffers."
  [& bufs]
  (codec/concat-bytes (vec bufs)))

(defn sub-buf
  "Bytes of `b` in `[from to)`.

  `codec/sub-bytes` takes a prefix length, so a general slice is a prefix of a
  suffix. Kept because a wire format needs arbitrary ranges."
  [b from to]
  #?(:clj (java.util.Arrays/copyOfRange ^bytes b (int from) (int to))
     :cljs (.slice b from to)))

(defn bufs=
  "Constant-time byte comparison."
  [a b]
  (gcore/ct-equal? a b))

(defn seq->bytes
  "Byte buffer from a sequence of byte values.

  `hasch/edn-hash` yields signed bytes on the JVM and unsigned numbers in
  ClojureScript; masking to `0xff` makes both produce the same buffer, which is
  the point — a signature over these bytes has to verify on the other platform."
  [s]
  (let [v (vec s)
        out (byte-buf (count v))]
    (dotimes [i (count v)]
      (let [b (bit-and (nth v i) 0xff)]
        #?(:clj (aset-byte out i (unchecked-byte b))
           :cljs (aset out i b))))
    out))

;; =============================================================================
;; Genesis record — the wire-format decision
;; =============================================================================
;; A peer id is the hash of a **genesis record**, not of a key.
;;
;; Hashing a key directly is the mistake SSB, did:key and UCAN all made, and
;; the one they cannot undo: the identity IS the key, so the key can never
;; change and a compromise is terminal. did:plc gets this right —
;; `did = hash(genesisOp)`, where the genesis names a *set* of keys and
;; self-verifies with no external state.
;;
;; The genesis has room for rotation from the start even though rotation is not
;; implemented yet. A genesis with one rotation key and threshold 1 is the
;; "kill and recreate" design; adding real rotation later is then just a
;; rotation, not a format change.
;;
;; One field CANNOT be deferred: `:kabel/revocation-commit`. A commitment added
;; after the fact is worthless, because an attacker who already holds the key
;; would add *their own* — a commitment only means something if it predates the
;; compromise.

(def ^:const genesis-version "kabel/identity/v2")
(def ^:const peer-id-tag "kabel/peer-id/v2")

(defn make-genesis
  "Build a genesis record.

  Every field is a string, number or vector of strings, so the record survives
  every codec kabel offers — including the `pr-str`/`edn` fallback, which
  cannot round-trip a byte array.

  - `rotation-keys`     — priority-ordered COLD keys, index 0 highest
  - `rotation-threshold`— how many must sign a rotation (1 = the simple case)
  - `operational-keys`  — HOT keys; these sign gossip and handshakes
  - `revocation-commit` — `H(secret)`; revealing `secret` revokes, absorbingly

  Cold and hot are separated because, as TUF puts it, roles differ by required
  *online-ness*: the key that must be present to sign every publish is the one
  that gets stolen, so it must not be the key that decides who you are."
  [{:keys [rotation-keys rotation-threshold operational-keys revocation-commit]}]
  {:kabel/version genesis-version
   :kabel/rotation-keys (vec rotation-keys)
   :kabel/rotation-threshold (or rotation-threshold 1)
   :kabel/operational-keys (vec operational-keys)
   :kabel/revocation-commit revocation-commit})

(defn genesis?
  [g]
  (and (map? g)
       (= genesis-version (:kabel/version g))
       (vector? (:kabel/operational-keys g))
       (seq (:kabel/operational-keys g))
       (vector? (:kabel/rotation-keys g))
       (seq (:kabel/rotation-keys g))
       (integer? (:kabel/rotation-threshold g))
       (pos? (:kabel/rotation-threshold g))
       (<= (:kabel/rotation-threshold g) (count (:kabel/rotation-keys g)))
       (string? (:kabel/revocation-commit g))))

(defn peer-id-bytes
  "Full 32-byte peer id: the authority anchor.

  Canonicalised with `hasch/edn-hash` rather than `pr-str`, because map key
  iteration order differs between platforms and an id that depends on it would
  differ between a JVM peer and a browser peer.

  This width is what belongs inside a signed record. `peer-id` below is a
  128-bit projection for *routing* only — both the TUF and SSB reviews flagged
  a truncated id as too weak to carry authority."
  [genesis]
  (when-not (genesis? genesis)
    (throw (ex-info "not a valid genesis record"
                    {:type :kabel.identity/bad-genesis})))
  (sha256 (concat-bufs (utf8-bytes peer-id-tag)
                       (seq->bytes (edn-hash genesis)))))

(defn peer-id
  "Routing id for a genesis record: the first 16 bytes of `peer-id-bytes`,
  rendered as a UUID so a self-certifying identity drops straight into kabel's
  existing `:id` slot.

  A hash projection, not an RFC 4122 UUID — no version or variant bits are
  stamped, because overwriting six bits to satisfy a spec nothing here reads
  would only shrink the identifier."
  [genesis]
  (let [h (bytes->hex (sub-buf (peer-id-bytes genesis) 0 16))
        s (str (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16) "-"
               (subs h 16 20) "-" (subs h 20 32))]
    #?(:clj (UUID/fromString s)
       :cljs (uuid s))))

(defn genesis-authorises?
  "May `public-key` sign on behalf of `claimed-id`?

  Both halves are required, and this is the check that replaces `owns-id?`:
  the genesis must hash to the claimed id, **and** the key must be one of its
  operational keys. Either alone lets somebody sign under another's name."
  [genesis public-key claimed-id]
  (boolean
   (try
     (and (genesis? genesis)
          (= claimed-id (peer-id genesis))
          (contains? (set (:kabel/operational-keys genesis))
                     (bytes->hex public-key)))
     (catch #?(:clj Exception :cljs js/Error) _ false))))

;; =============================================================================
;; Revocation commitment
;; =============================================================================

(def ^:const revocation-tag "kabel/revocation/v1")

(defn revocation-commitment
  "`H(secret)` — published in genesis, so revealing `secret` later revokes.

  The only primitive in the reviewed corpus that survives a full key
  compromise without a directory: an attacker holding **every** signing key
  still cannot produce the pre-image."
  [secret]
  (bytes->hex (sha256 (concat-bufs (utf8-bytes revocation-tag) secret))))

(defn revokes?
  "Does `secret` open the commitment in `genesis`?

  Revocation is an **absorbing** state, not a race: once this holds, no later
  record can un-revoke. A highest-sequence-wins rule loses to an attacker who
  simply increments forever, which is why it is not used here."
  [genesis secret]
  (boolean
   (try
     (= (:kabel/revocation-commit genesis) (revocation-commitment secret))
     (catch #?(:clj Exception :cljs js/Error) _ false))))

;; =============================================================================
;; Keys and signatures — geheimnis
;; =============================================================================

(defn generate-keypair
  "A fresh Ed25519 keypair. Channel of `{:public <32 bytes> :private <32 bytes>}`."
  []
  (gsign/generate-keypair))

(defn sign
  "Sign `message` with a raw 32-byte private key. Channel of a 64-byte signature."
  [private-key message]
  (gsign/sign private-key message))

(defn verify
  "Verify `signature` over `message` against a raw 32-byte public key.

  Channel of `true`/`false`. Garbage yields `false` rather than an exception: a
  peer must not be able to make us throw by sending us nonsense."
  [public-key message signature]
  (let [ch (chan 1)]
    (async/go
      (put! ch (boolean
                (try
                  (let [r (async/<! (gsign/verify public-key message signature))]
                    (and (not (instance? #?(:clj Exception :cljs js/Error) r)) r))
                  (catch #?(:clj Exception :cljs js/Error) _ false))))
      (close! ch))
    ch))

(defn generate-identity
  "Generate a complete identity: genesis, keys, and a revocation secret.

  Returns a channel yielding

      {:genesis            <record whose hash is the peer id>
       :peer-id            <UUID, for routing>
       :peer-id-bytes      <32 bytes, the authority anchor>
       :operational        {:public … :private …}   ; HOT — signs everything
       :rotation           {:public … :private …}   ; COLD — signs rotations
       :revocation-secret  <32 bytes>}              ; KEEP OFFLINE

  Two keypairs, not one, because the hot key is the one that gets stolen — it
  must be present to sign every publish — and it must not be the key that
  decides who you are.

  **`:revocation-secret` must be stored somewhere the operational key is not.**
  It is the only thing that still works after a total key compromise, and it is
  worthless if it sits next to what was compromised."
  []
  (let [ch (chan 1)]
    (async/go
      (try
        (let [op (async/<! (generate-keypair))
              rot (async/<! (generate-keypair))]
          (if (or (instance? #?(:clj Exception :cljs js/Error) op)
                  (instance? #?(:clj Exception :cljs js/Error) rot))
            (put! ch (ex-info "key generation failed"
                              {:type :kabel.identity/keygen-failed}))
            (let [secret (random-bytes 32)
                  genesis (make-genesis
                           {:rotation-keys [(bytes->hex (:public rot))]
                            :rotation-threshold 1
                            :operational-keys [(bytes->hex (:public op))]
                            :revocation-commit (revocation-commitment secret)})]
              (put! ch {:genesis genesis
                        :peer-id (peer-id genesis)
                        :peer-id-bytes (peer-id-bytes genesis)
                        :operational op
                        :rotation rot
                        :revocation-secret secret}))))
        (catch #?(:clj Exception :cljs js/Error) e (put! ch e)))
      (close! ch))
    ch))

;; =============================================================================
;; Identity records
;; =============================================================================
;; An identity record is what a peer gossips to announce itself. It is signed
;; by the key it names, so it can travel over any transport, be relayed by
;; anyone, and still be checked by the recipient.
;;
;; This is the invariant from .internal/DHT_DESIGN.md §1, in the form it takes
;; once identity rides on pub/sub: **announcement is not authority**. A record
;; says "this key claims these addresses". It never says who counts.

(defn record-bytes
  "Canonical bytes signed by an identity record.

  Covers the **genesis** as well as the signing key, so an announcement is
  bound to the identity it claims and cannot be lifted onto another one.
  Canonicalised with `hasch/edn-hash` for the same reason `peer-id-bytes` is:
  map key order is not stable across platforms, and a signature over
  `pr-str` output would verify on the JVM and fail in a browser."
  [genesis public-key addresses seq-no]
  (seq->bytes
   (edn-hash ["kabel/identity/record/v2"
              genesis
              (bytes->hex public-key)
              seq-no
              (vec (sort addresses))])))

(defn sign-record
  "Build a signed identity record announcing `addresses` for an identity.

  `seq-no` must increase for each record a peer publishes; a receiver keeps
  only the highest it has seen, so a replayed older record cannot displace a
  newer one.

  Returns a channel yielding the record map."
  [{:keys [genesis operational]} addresses seq-no]
  (let [{:keys [public private]} operational
        ch (chan 1)]
    (async/go
      (let [sig (async/<! (sign private (record-bytes genesis public addresses seq-no)))]
        (if (instance? #?(:clj Exception :cljs js/Error) sig)
          (put! ch sig)
          (put! ch {:kabel/genesis genesis
                    :kabel/public-key public
                    :kabel/peer-id (peer-id genesis)
                    :kabel/addresses (vec (sort addresses))
                    :kabel/seq-no seq-no
                    :kabel/signature sig}))
        (close! ch)))
    ch))

(defn record->wire
  "Identity record with its byte fields hex-encoded.

  kabel supports EDN, JSON, transit, CBOR and fressian on the wire, and only
  some of those carry byte arrays. With no serialization middleware kabel falls
  back to `pr-str` / `edn/read-string` (`kabel.binary/to-binary`), and a byte
  array renders as `#object[[B …]`, which EDN cannot read back — so a record
  sent raw is silently unreadable on exactly the default codec.

  The wire form therefore contains nothing but strings, numbers, keywords,
  vectors and a UUID, and survives every codec kabel offers."
  [record]
  (-> record
      (update :kabel/public-key bytes->hex)
      (update :kabel/signature bytes->hex)))

(defn wire->record
  "Inverse of `record->wire`. Returns nil if the encoding is not what we expect,
  so a malformed record from a stranger is a failed verification rather than an
  exception."
  [wire]
  (try
    (when (and (map? wire)
               (string? (:kabel/public-key wire))
               (string? (:kabel/signature wire)))
      (-> wire
          (update :kabel/public-key hex->bytes)
          (update :kabel/signature hex->bytes)))
    (catch #?(:clj Exception :cljs js/Error) _ nil)))

(defn verify-record
  "Check an identity record.

  Three things must hold, and dropping any one of them lets somebody sign
  under another peer's name:

  1. the signature is valid;
  2. the genesis hashes to the claimed peer id;
  3. the signing key is one of that genesis's **operational** keys.

  Returns a channel yielding `true` or `false` — never an exception, because
  the record arrives from a stranger and a malformed one must fail
  verification rather than the connection."
  [{gen :kabel/genesis pk :kabel/public-key claimed :kabel/peer-id
    addresses :kabel/addresses seq-no :kabel/seq-no sig :kabel/signature
    :as _record}]
  (let [ch (chan 1)]
    (async/go
      (put! ch (boolean
                (and (some? pk)
                     (some? sig)
                     (= key-size (buf-length pk))
                     (= signature-size (buf-length sig))
                     (genesis-authorises? gen pk claimed)
                     (async/<! (verify pk
                                       (record-bytes gen pk addresses seq-no)
                                       sig)))))
      (close! ch))
    ch))
