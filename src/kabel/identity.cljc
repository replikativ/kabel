(ns kabel.identity
  "Self-certifying peer identity.

  A peer holds an Ed25519 keypair, and its peer id is derived from the public
  key:

      peer-id = SHA-256(\"kabel/peer-id/v1\" ‖ public-key)

  Two properties follow, and they are the whole point:

  - **Self-certifying.** A peer can prove it owns its id by signing a
    challenge. Nobody can claim an id they do not hold the key for, so
    identity needs no issuer and no registry.
  - **Grinding-priced.** Occupying a chosen point in a routing keyspace costs
    key generation rather than being free, which is what makes routing-table
    poisoning expensive rather than trivial.

  ## Wire format

  Keys are **raw 32 bytes** on the wire, never ASN.1. The JVM's
  `KeyPairGenerator` emits X.509 `SubjectPublicKeyInfo` (44 bytes) and PKCS#8
  (48 bytes); both carry a fixed prefix for Ed25519, so raw keys are the tail
  and re-wrapping is prepending a constant. Those constants are verified by
  `kabel.identity-test/asn1-prefixes-are-constant`.

  This is a wire-format decision and therefore the expensive kind to change —
  see `.internal/DHT_DESIGN.md` §3.

  ## Why the private key is a keypair, not a seed

  Restoring an identity needs the public key too. The JDK offers no
  \"derive public from private seed\" operation, so a stored identity carries
  both halves, as libsodium's 64-byte secret keys do for the same reason.

  ## Async

  `generate-keypair`, `sign` and `verify` return core.async channels. The JVM
  could do all three synchronously, but ClojureScript's Ed25519 goes through
  WebCrypto's `subtle.digest`, which is async-only. One API that is honest on
  both platforms beats two that diverge. `peer-id` is pure and synchronous on
  both, because routing decisions need it in hand.

  ## Portability

  Every byte operation here works on byte arrays / `Uint8Array`, never on
  numbers. ClojureScript coerces bit operations to 32-bit signed integers, so
  a 256-bit identifier manipulated as numbers is silently wrong on exactly one
  platform — see `.internal/DHT_DESIGN.md` §5."
  (:require [hasch.core :refer [edn-hash]]
            #?(:clj [clojure.core.async :as async :refer [chan put! close!]]
               :cljs [clojure.core.async :as async :refer [chan put! close!]])
            #?(:cljs ["@noble/ed25519" :as ed]))
  #?(:clj (:import [java.security KeyPairGenerator Signature KeyFactory
                    MessageDigest SecureRandom]
                   [java.security.spec X509EncodedKeySpec PKCS8EncodedKeySpec]
                   [java.util Arrays UUID])
     :cljs (:import [goog.crypt Sha256])))

;; =============================================================================
;; Byte primitives
;; =============================================================================
;; Deliberately explicit rather than clever: these are the operations a wire
;; format is built from, and every one of them is a place where a platform
;; difference could hide.

(def ^:const key-size 32)
(def ^:const signature-size 64)
(def ^:const peer-id-size 32)

(defn byte-buf
  "Allocate a mutable byte buffer of length `n`."
  [n]
  #?(:clj (byte-array n)
     :cljs (js/Uint8Array. n)))

(defn buf-length [b]
  #?(:clj (alength ^bytes b)
     :cljs (.-length b)))

(defn sub-buf
  "Bytes of `b` in `[from to)`."
  [b from to]
  #?(:clj (Arrays/copyOfRange ^bytes b (int from) (int to))
     :cljs (.slice b from to)))

(defn concat-bufs
  "Concatenate byte buffers into a new one."
  [& bufs]
  (let [total (reduce + 0 (map buf-length bufs))
        out (byte-buf total)]
    (loop [offset 0
           [b & more] bufs]
      (if b
        (let [n (buf-length b)]
          #?(:clj (System/arraycopy ^bytes b 0 ^bytes out offset n)
             :cljs (.set out b offset))
          (recur (+ offset n) more))
        out))))

(defn bufs=
  "Structural equality of two byte buffers.

  Not constant-time, and deliberately not used for anything secret — signature
  comparison happens inside the crypto library."
  [a b]
  (and (= (buf-length a) (buf-length b))
       (every? true? (map = (seq a) (seq b)))))

(defn seq->bytes
  "Byte buffer from a sequence of byte values.

  `hasch/edn-hash` yields signed bytes on the JVM and unsigned numbers in
  ClojureScript; masking to `0xff` before writing makes both produce the same
  buffer, which is the whole point — a signature computed over these bytes has
  to verify on the other platform."
  [s]
  (let [v (vec s)
        out (byte-buf (count v))]
    (dotimes [i (count v)]
      (let [b (bit-and (nth v i) 0xff)]
        #?(:clj (aset-byte out i (unchecked-byte b))
           :cljs (aset out i b))))
    out))

(defn utf8-bytes [^String s]
  #?(:clj (.getBytes s "UTF-8")
     :cljs (.encode (js/TextEncoder.) s)))

(defn sha256
  "SHA-256 of a byte buffer, synchronously, on both platforms."
  [b]
  #?(:clj (.digest (MessageDigest/getInstance "SHA-256") ^bytes b)
     :cljs (let [d (Sha256.)]
             (.update d b)
             (js/Uint8Array.from (.digest d)))))

(defn bytes->hex [b]
  (apply str (map #(let [v (bit-and #?(:clj % :cljs %) 0xff)]
                     (str (when (< v 16) "0")
                          #?(:clj (Integer/toHexString v)
                             :cljs (.toString v 16))))
                  (seq b))))

(defn hex->bytes [^String s]
  (let [n (quot (count s) 2)
        out (byte-buf n)]
    (dotimes [i n]
      (let [v #?(:clj (Integer/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16)
                 :cljs (js/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16))]
        #?(:clj (aset-byte out i (unchecked-byte v))
           :cljs (aset out i v))))
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
;; Keys and signatures
;; =============================================================================

#?(:clj
   (def ^:private spki-prefix
     ;; X.509 SubjectPublicKeyInfo header for Ed25519. Verified against a
     ;; freshly generated key in kabel.identity-test.
     (byte-array (map unchecked-byte
                      [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))))

#?(:clj
   (def ^:private pkcs8-prefix
     ;; PKCS#8 PrivateKeyInfo header for Ed25519. Likewise verified.
     (byte-array (map unchecked-byte
                      [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70
                       0x04 0x22 0x04 0x20]))))

#?(:clj
   (defn- ->public-key [raw]
     (.generatePublic (KeyFactory/getInstance "Ed25519")
                      (X509EncodedKeySpec. (concat-bufs spki-prefix raw)))))

#?(:clj
   (defn- ->private-key [raw]
     (.generatePrivate (KeyFactory/getInstance "Ed25519")
                       (PKCS8EncodedKeySpec. (concat-bufs pkcs8-prefix raw)))))

(defn- deliver-chan
  "One-shot channel carrying `v` (or an exception), then closed."
  [v]
  (let [ch (chan 1)]
    (when (some? v) (put! ch v))
    (close! ch)
    ch))

(defn- error-chan [e]
  (let [ch (chan 1)]
    (put! ch e)
    (close! ch)
    ch))

(defn generate-keypair
  "Generate a fresh Ed25519 identity.

  Returns a channel yielding `{:public <32 bytes> :private <32 bytes>}`, or an
  exception."
  []
  (try
    #?(:clj
       (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
             pub (.getEncoded (.getPublic kp))
             priv (.getEncoded (.getPrivate kp))]
         (deliver-chan {:public (sub-buf pub 12 44)
                        :private (sub-buf priv 16 48)}))
       :cljs
       (let [ch (chan 1)
             ;; Draw the secret from WebCrypto rather than from the library's
             ;; own helper: @noble/ed25519 renamed `randomPrivateKey` to
             ;; `randomSecretKey` across a minor version, and a key generator
             ;; that silently becomes `undefined` on a dependency bump is not
             ;; a thing to leave to a name. `crypto.getRandomValues` is
             ;; specified in browsers and global in Node 19+.
             sk (js/Uint8Array. key-size)
             _ (.getRandomValues js/crypto sk)]
         (-> (.getPublicKeyAsync ed sk)
             (.then (fn [pk]
                      (put! ch {:public pk :private sk})
                      (close! ch)))
             (.catch (fn [e] (put! ch e) (close! ch))))
         ch))
    (catch #?(:clj Exception :cljs js/Error) e
      (error-chan e))))

(defn sign
  "Sign `message` (a byte buffer) with a raw 32-byte private key.

  Returns a channel yielding a 64-byte signature, or an exception."
  [private-key message]
  (try
    #?(:clj
       (let [s (Signature/getInstance "Ed25519")]
         (.initSign s (->private-key private-key))
         (.update s ^bytes message)
         (deliver-chan (.sign s)))
       :cljs
       (let [ch (chan 1)]
         (-> (.signAsync ed message private-key)
             (.then (fn [sig] (put! ch sig) (close! ch)))
             (.catch (fn [e] (put! ch e) (close! ch))))
         ch))
    (catch #?(:clj Exception :cljs js/Error) e
      (error-chan e))))

(defn verify
  "Verify `signature` over `message` against a raw 32-byte public key.

  Returns a channel yielding `true` or `false`. A malformed key or signature
  yields `false` rather than an exception: a peer must not be able to make us
  throw by sending us garbage."
  [public-key message signature]
  (try
    #?(:clj
       (let [v (Signature/getInstance "Ed25519")]
         (.initVerify v (->public-key public-key))
         (.update v ^bytes message)
         (deliver-chan (boolean (.verify v ^bytes signature))))
       :cljs
       (let [ch (chan 1)]
         (-> (.verifyAsync ed signature message public-key)
             (.then (fn [ok] (put! ch (boolean ok)) (close! ch)))
             (.catch (fn [_] (put! ch false) (close! ch))))
         ch))
    (catch #?(:clj Exception :cljs js/Error) _
      (deliver-chan false))))

(defn random-bytes
  "`n` cryptographically random bytes."
  [n]
  #?(:clj (let [b (byte-array n)] (.nextBytes (SecureRandom.) b) b)
     :cljs (let [b (js/Uint8Array. n)] (.getRandomValues js/crypto b) b)))

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
