(ns kabel.identity-test
  "Tests for self-certifying peer identity.

  The sync half of this namespace is where the value is: peer-id derivation
  and the byte primitives are pure, run identically on both platforms, and are
  exactly the places where a ClojureScript 32-bit coercion would hide
  (`.internal/DHT_DESIGN.md` §5). `peer-id-known-answer` is the wire-format
  lock — if it ever changes, every deployed node id changes with it."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [kabel.identity :as id]
            #?(:clj [clojure.core.async :refer [<!!]]
               :cljs [clojure.core.async :refer [go <!]])
            #?(:cljs [cljs.test :refer [async]])))

;; =============================================================================
;; Byte primitives — pure, shared across platforms
;; =============================================================================

(deftest byte-primitives
  (testing "concat and sub are inverses"
    (let [a (id/hex->bytes "00ff10")
          b (id/hex->bytes "abcdef")
          c (id/concat-bufs a b)]
      (is (= 6 (id/buf-length c)))
      (is (id/bufs= a (id/sub-buf c 0 3)))
      (is (id/bufs= b (id/sub-buf c 3 6)))))

  (testing "hex round-trips, including the high bytes that sign-extend"
    ;; 0x80..0xff are negative as JVM bytes and positive in a Uint8Array. If
    ;; bytes->hex leaked that difference, the two platforms would disagree on
    ;; every id whose digest contains a high byte — which is almost all of them.
    (doseq [h ["00" "7f" "80" "ff" "00ff7f80" "deadbeefcafe"]]
      (is (= h (id/bytes->hex (id/hex->bytes h))) (str "round-trip " h))))

  (testing "empty concat"
    (is (= 0 (id/buf-length (id/concat-bufs (id/byte-buf 0))))))

  (testing "bufs= distinguishes length and content"
    (is (id/bufs= (id/hex->bytes "0102") (id/hex->bytes "0102")))
    (is (not (id/bufs= (id/hex->bytes "0102") (id/hex->bytes "0103"))))
    (is (not (id/bufs= (id/hex->bytes "0102") (id/hex->bytes "010203"))))))

(deftest sha256-known-answer
  (testing "SHA-256 agrees with the published vector on both platforms"
    ;; NIST vector for "abc". This is here because kabel.identity uses
    ;; MessageDigest on the JVM and goog.crypt.Sha256 in ClojureScript — two
    ;; entirely different implementations that must not disagree.
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (id/bytes->hex (id/sha256 (id/utf8-bytes "abc")))))))

;; =============================================================================
;; Peer id derivation — the wire format
;; =============================================================================

(def test-public-key
  "A fixed, arbitrary 32-byte public key. Not a real key and never used to
  sign; it exists so genesis construction has a stable input."
  (id/hex->bytes "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))

(def test-genesis
  "A fixed genesis record, so peer-id derivation has a stable input."
  (id/make-genesis
   {:rotation-keys ["aa" "bb"]
    :rotation-threshold 1
    :operational-keys [(id/bytes->hex test-public-key)]
    :revocation-commit "cc"}))

(deftest peer-id-known-answer
  (testing "peer-id derivation is pinned"
    ;; If this fails, the wire format changed and every node id in every
    ;; deployment changed with it. That is the intended alarm, not a nuisance.
    ;; Both platforms must produce these exact values.
    (is (= 32 (id/buf-length (id/peer-id-bytes test-genesis))))
    (is (= "8596a5e34c5daa4b03e73c0e2e39153eaae97b7e81b5c06b3fff4c63a8e32302" (id/bytes->hex (id/peer-id-bytes test-genesis))))
    (is (= #uuid "8596a5e3-4c5d-aa4b-03e7-3c0e2e39153e" (id/peer-id test-genesis)))))

(deftest genesis-is-the-identity
  (testing "the id is the hash of a RECORD, not of a key"
    ;; SSB, did:key and UCAN all hash a key directly, and all three are stuck
    ;; with it: the identity IS the key, so the key can never change. Hashing a
    ;; record leaves room for rotation without a format change.
    (let [other (id/make-genesis
                 {:rotation-keys ["aa" "bb"]
                  :rotation-threshold 1
                  :operational-keys [(id/bytes->hex test-public-key)]
                  ;; same operational key, different commitment
                  :revocation-commit "dd"})]
      (is (not= (id/peer-id test-genesis) (id/peer-id other))
          "the id ignored part of the genesis")))

  (testing "derivation is deterministic"
    (is (= (id/peer-id test-genesis) (id/peer-id test-genesis))))

  (testing "an invalid genesis is refused rather than silently hashed"
    (is (thrown? #?(:clj Exception :cljs js/Error) (id/peer-id-bytes {})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (id/peer-id-bytes (assoc test-genesis :kabel/operational-keys []))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 ;; threshold above the number of keys is unsatisfiable
                 (id/peer-id-bytes (assoc test-genesis :kabel/rotation-threshold 9)))))

  (testing "the routing id is the first 16 bytes of the authority id"
    (is (= (id/bytes->hex (id/sub-buf (id/peer-id-bytes test-genesis) 0 16))
           (str/replace (str (id/peer-id test-genesis)) "-" "")))))

(deftest genesis-authorises-only-operational-keys
  (testing "an operational key may sign for the identity"
    (is (id/genesis-authorises? test-genesis test-public-key
                                (id/peer-id test-genesis))))

  (testing "a key that is not in the genesis may not"
    (is (not (id/genesis-authorises? test-genesis
                                     (id/hex->bytes (apply str (repeat 32 "11")))
                                     (id/peer-id test-genesis)))))

  (testing "a rotation key is COLD and may not sign operationally"
    ;; The separation only means something if it is enforced. TUF's point is
    ;; that roles differ by required online-ness: the key present to sign every
    ;; publish is the one that gets stolen, so it must not be the key that
    ;; decides who you are.
    (let [g (id/make-genesis {:rotation-keys [(id/bytes->hex test-public-key)]
                              :rotation-threshold 1
                              :operational-keys ["ab"]
                              :revocation-commit "cc"})]
      (is (not (id/genesis-authorises? g test-public-key (id/peer-id g))))))

  (testing "a genesis that does not hash to the claimed id is refused"
    (is (not (id/genesis-authorises? test-genesis test-public-key
                                     #uuid "00000000-0000-0000-0000-000000000000"))))

  (testing "garbage is refused rather than thrown"
    (is (not (id/genesis-authorises? nil test-public-key (id/peer-id test-genesis))))
    (is (not (id/genesis-authorises? {} test-public-key (id/peer-id test-genesis))))))

(deftest revocation-commitment-survives-total-compromise
  ;; The only primitive in the reviewed corpus that works after every signing
  ;; key is stolen: an attacker holding all of them still cannot produce the
  ;; pre-image. It MUST be in genesis — a commitment added later would be the
  ;; attacker's, not yours.
  (let [secret (id/random-bytes 32)
        g (id/make-genesis {:rotation-keys ["aa"]
                            :rotation-threshold 1
                            :operational-keys ["bb"]
                            :revocation-commit (id/revocation-commitment secret)})]
    (testing "the secret opens its own commitment"
      (is (id/revokes? g secret)))

    (testing "nothing else does"
      (is (not (id/revokes? g (id/random-bytes 32))))
      (is (not (id/revokes? g (id/byte-buf 32))))
      (is (not (id/revokes? g nil))))

    (testing "the commitment does not leak the secret"
      (is (not= (id/bytes->hex secret) (:kabel/revocation-commit g))))))

;; =============================================================================
;; ASN.1 constants — JVM only, because they exist only on the JVM
;; =============================================================================

;; =============================================================================
;; Signing — async, so written once per platform
;; =============================================================================

#?(:clj
   (deftest sign-verify-roundtrip
     (let [{:keys [public private] :as kp} (<!! (id/generate-keypair))
           msg (id/utf8-bytes "hello kabel")]
       (testing "generated keys have the wire sizes"
         (is (= id/key-size (id/buf-length public)))
         (is (= id/key-size (id/buf-length private))))

       (let [sig (<!! (id/sign private msg))]
         (testing "signature verifies"
           (is (= id/signature-size (id/buf-length sig)))
           (is (true? (<!! (id/verify public msg sig)))))

         (testing "a tampered message does not verify"
           (is (false? (<!! (id/verify public (id/utf8-bytes "hello kabe!") sig)))))

         (testing "another key does not verify"
           (let [{other :public} (<!! (id/generate-keypair))]
             (is (false? (<!! (id/verify other msg sig))))))

         (testing "garbage yields false, never an exception"
           ;; A peer must not be able to kill a go-block by sending nonsense.
           (is (false? (<!! (id/verify public msg (id/hex->bytes "00")))))
           (is (false? (<!! (id/verify (id/hex->bytes "00") msg sig))))))

       (testing "two keypairs differ"
         (is (not (id/bufs= (:public kp) (:public (<!! (id/generate-keypair))))))))))

#?(:clj
   (deftest identity-record-roundtrip
     (let [kp (<!! (id/generate-identity))
           rec (<!! (id/sign-record kp ["ws://b.example/" "ws://a.example/"] 1))]
       (testing "a well-formed record verifies"
         (is (true? (<!! (id/verify-record rec)))))

       (testing "the record carries the derived peer id"
         (is (= (id/peer-id (:genesis kp)) (:kabel/peer-id rec))))

       (testing "and the genesis, so a stranger can verify without a lookup"
         (is (id/genesis? (:kabel/genesis rec))))

       (testing "addresses are canonically ordered"
         (is (= ["ws://a.example/" "ws://b.example/"] (:kabel/addresses rec))))

       (testing "tampering with addresses invalidates it"
         (is (false? (<!! (id/verify-record
                           (assoc rec :kabel/addresses ["ws://evil.example/"]))))))

       (testing "tampering with the sequence number invalidates it"
         (is (false? (<!! (id/verify-record (assoc rec :kabel/seq-no 99))))))

       (testing "claiming a different peer id invalidates it"
         ;; The signature is still good over the addresses; only the id is
         ;; wrong. Without the genesis-authorises? half of the check this would
         ;; pass and a peer could sign itself into somebody else's slot.
         (is (false? (<!! (id/verify-record
                           (assoc rec :kabel/peer-id
                                  #uuid "00000000-0000-0000-0000-000000000000"))))))

       (testing "a record with no signature is refused"
         (is (false? (<!! (id/verify-record (dissoc rec :kabel/signature)))))))))

#?(:clj
   (deftest wire-form-survives-every-codec
     (testing "the wire record round-trips and still verifies"
       (let [kp (<!! (id/generate-identity))
             rec (<!! (id/sign-record kp ["ws://a.example/"] 1))
             wire (id/record->wire rec)]
         (is (string? (:kabel/public-key wire)))
         (is (string? (:kabel/signature wire)))
         (is (true? (<!! (id/verify-record (id/wire->record wire)))))))

     (testing "it survives pr-str / read-string, which is kabel's fallback codec"
       ;; kabel.binary/to-binary falls back to pr-str when no serialization
       ;; middleware is present, and a byte array renders as #object[[B …],
       ;; which EDN cannot read back. Sending a raw record is therefore
       ;; silently unreadable on exactly the default codec — this is the
       ;; regression test for that.
       (let [kp (<!! (id/generate-identity))
             rec (<!! (id/sign-record kp ["ws://a.example/"] 1))
             round-tripped (read-string (pr-str (id/record->wire rec)))]
         (is (= (id/record->wire rec) round-tripped))
         (is (true? (<!! (id/verify-record (id/wire->record round-tripped)))))))

     (testing "a raw record does NOT survive it, which is why the wire form exists"
       (let [kp (<!! (id/generate-identity))
             rec (<!! (id/sign-record kp ["ws://a.example/"] 1))]
         (is (thrown? Exception (read-string (pr-str rec))))))

     (testing "a malformed wire record is refused rather than throwing"
       ;; It arrives from a stranger, so it must fail verification, not the
       ;; connection.
       (is (nil? (id/wire->record {:kabel/public-key 42 :kabel/signature "ab"})))
       (is (nil? (id/wire->record nil)))
       (is (nil? (id/wire->record "not a map"))))))

#?(:cljs
   (deftest sign-verify-roundtrip-cljs
     (async done
            (go
              (let [{:keys [public private]} (<! (id/generate-keypair))
                    msg (id/utf8-bytes "hello kabel")]
                (is (= id/key-size (id/buf-length public)))
                (is (= id/key-size (id/buf-length private)))
                (let [sig (<! (id/sign private msg))]
                  (is (= id/signature-size (id/buf-length sig)))
                  (is (true? (<! (id/verify public msg sig))))
                  (is (false? (<! (id/verify public (id/utf8-bytes "hello kabe!") sig))))
                  (is (false? (<! (id/verify public msg (id/hex->bytes "00")))))))
              (done)))))

#?(:cljs
   (deftest identity-record-roundtrip-cljs
     (async done
            (go
              (let [kp (<! (id/generate-identity))
                    rec (<! (id/sign-record kp ["ws://b.example/" "ws://a.example/"] 1))]
                (is (true? (<! (id/verify-record rec))))
                (is (= (id/peer-id (:genesis kp)) (:kabel/peer-id rec)))
                (is (= ["ws://a.example/" "ws://b.example/"] (:kabel/addresses rec)))
                (is (false? (<! (id/verify-record
                                 (assoc rec :kabel/addresses ["ws://evil.example/"])))))
                (is (false? (<! (id/verify-record
                                 (assoc rec :kabel/peer-id
                                        #uuid "00000000-0000-0000-0000-000000000000"))))))
              (done)))))
