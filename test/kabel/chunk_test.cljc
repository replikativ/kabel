(ns kabel.chunk-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.chunk :as ch]
            [kabel.content :as c]
            [kabel.identity :as id]
            [kabel.sim :as sim]))

(defn- payload
  "Deterministic bytes, so a test that fails fails the same way twice."
  [n]
  (id/seq->bytes (map #(mod (* % 7) 251) (range n))))

(defn- other-bytes
  "Bytes of length `n` that are NOT a prefix of `payload`.

  Worth its own function: `(payload 256)` is byte-identical to the first
  256-byte piece of `(payload 1000)`, so using it as a \"tampered\" piece
  substitutes a piece for itself and every verification passes. Two tests were
  green for that reason before this existed."
  [n]
  (id/seq->bytes (map #(mod (+ 13 (* % 11)) 251) (range n))))

;; =============================================================================
;; Split / assemble
;; =============================================================================

(deftest round-trips
  (testing "an exact multiple of the chunk size"
    (let [bs (payload 1024)
          {:keys [manifest pieces]} (ch/split bs {:chunk-size 256})]
      (is (= 4 (count (ch/piece-keys manifest))))
      (is (= (id/bytes->hex bs) (id/bytes->hex (ch/assemble manifest pieces))))))

  (testing "a ragged final piece"
    (let [bs (payload 1000)
          {:keys [manifest pieces]} (ch/split bs {:chunk-size 256})]
      (is (= 4 (count (ch/piece-keys manifest))))
      (is (= 1000 (:size manifest)))
      (is (= (id/bytes->hex bs) (id/bytes->hex (ch/assemble manifest pieces))))))

  (testing "smaller than one chunk"
    (let [bs (payload 10)
          {:keys [manifest pieces]} (ch/split bs {:chunk-size 256})]
      (is (= 1 (count (ch/piece-keys manifest))))
      (is (= (id/bytes->hex bs) (id/bytes->hex (ch/assemble manifest pieces))))))

  (testing "empty"
    (let [{:keys [manifest pieces]} (ch/split (id/byte-buf 0) {:chunk-size 256})]
      (is (= 0 (:size manifest)))
      (is (= 0 (id/buf-length (ch/assemble manifest pieces)))))))

(deftest addresses-agree-across-platforms
  (testing "piece and manifest addresses are pinned"
    ;; The load-bearing cross-platform fact: a piece address is
    ;; `hasch/uuid` over a byte buffer, and that is a JVM byte[] on one platform
    ;; and a Uint8Array on the other. Round-trip tests only prove each platform
    ;; agrees with ITSELF — a peer could still be unable to verify a piece
    ;; another peer produced. These values pin it.
    (let [{:keys [key manifest]} (ch/split (payload 1000) {:chunk-size 256})]
      (is (= #uuid "0c56341e-85c5-5a5d-bc09-b516dc4bbc86" key))
      (is (= #uuid "0804aa14-9c95-5a47-9cd0-74a179a5d055"
             (first (ch/piece-keys manifest)))))))

(deftest identical-pieces-are-stored-once
  (testing "a repeated run of bytes costs one piece but keeps every position"
    ;; Content addressing gives deduplication for free; the manifest still
    ;; names the piece at each offset it occupies, so assembly is unaffected.
    (let [bs (id/seq->bytes (repeat 1024 7))
          {:keys [manifest pieces]} (ch/split bs {:chunk-size 256})]
      (is (= 4 (count (ch/piece-keys manifest))))
      (is (= 1 (count pieces)) "identical pieces were stored more than once")
      (is (= (id/bytes->hex bs) (id/bytes->hex (ch/assemble manifest pieces)))))))

(deftest manifests-are-bounded
  (testing "a value that would exceed :max-pieces is refused, not attempted"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (ch/split (payload 10000) {:chunk-size 1 :max-pieces 100})))))

(deftest assembly-refuses-every-way-of-being-wrong
  (let [bs (payload 1000)
        {:keys [manifest pieces]} (ch/split bs {:chunk-size 256})
        ks (ch/piece-keys manifest)]

    (testing "a missing piece"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (ch/assemble manifest (dissoc pieces (first ks))))))

    (testing "a piece that does not hash to its address"
      ;; The pieces come from strangers. A substituted piece that happened to be
      ;; the right length would otherwise assemble into plausible garbage.
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (ch/assemble manifest
                                (assoc pieces (first ks) (other-bytes 256))))))

    (testing "a manifest whose size disagrees with its pieces"
      ;; Catches a truncated or padded transfer whose pieces individually
      ;; verified — the failure the per-piece hash cannot see.
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (ch/assemble (assoc manifest :size 999) pieces))))

    (testing "something that is not a manifest at all"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (ch/assemble {:not "a manifest"} pieces))))))

(deftest missing-pieces-drives-resume
  (testing "a fetcher knows exactly what it still needs"
    (let [bs (payload 1000)
          {:keys [manifest pieces]} (ch/split bs {:chunk-size 256})
          ks (ch/piece-keys manifest)
          held (set (take 2 ks))]
      (is (= (vec (drop 2 ks)) (ch/missing-pieces manifest held)))
      (is (empty? (ch/missing-pieces manifest (set ks)))))))

(deftest chunked-predicate
  (is (ch/chunked? (:manifest (ch/split (payload 10) {:chunk-size 4}))))
  (is (not (ch/chunked? {:addresses [:a]})))
  (is (not (ch/chunked? "a string")))
  (is (not (ch/chunked? nil))))

;; =============================================================================
;; Transfer — the point is that nothing new is needed
;; =============================================================================

(defn- net [ids blocks-by-id sim-opts node-opts]
  (reduce (fn [s id]
            (sim/add-node s id
                          (fn [state event ctx]
                            (let [[st a1] (c/sync-peers state (remove #{id} ids))
                                  {st2 :state a2 :actions} (c/handler st event ctx)]
                              {:state st2 :actions (vec (concat a1 a2))}))
                          (c/make-state id (get blocks-by-id id {}) node-opts)))
          (sim/make-sim sim-opts)
          ids))

(deftest a-chunked-value-transfers-as-an-ordinary-tree
  (testing "one :content/fetch-tree moves a manifest and all its pieces"
    ;; The whole design claim: chunking is a representation, not a protocol.
    ;; `kabel.content` has no idea this value is chunked.
    (let [bs (payload 20000)
          split (ch/split bs {:chunk-size 1024})
          blocks (ch/blocks split)
          n (count blocks)
          s (-> (net [:a :b] {:b blocks} {:seed 5}
                     {:max-blocks (inc n) :max-tree-nodes 1000})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch-tree
                                           :root (:key split)})
                (sim/run-until 40000))
          sa (sim/node-state s :a)]
      (is (= n (count (:blocks sa)))
          (str "got " (count (:blocks sa)) " of " n " blocks"))

      (testing "and the fetcher can reassemble the original bytes"
        (let [manifest (get-in sa [:blocks (:key split)])
              pieces (select-keys (:blocks sa) (ch/piece-keys manifest))]
          (is (ch/chunked? manifest))
          (is (= (id/bytes->hex bs)
                 (id/bytes->hex (ch/assemble manifest pieces)))))))))

(deftest a-tampered-piece-is-refused-in-transit
  (testing "content verification rejects a bad piece before assembly ever sees it"
    (let [bs (payload 4096)
          split (ch/split bs {:chunk-size 1024})
          blocks (ch/blocks split)
          victim (first (ch/piece-keys (:manifest split)))
          tampered (assoc blocks victim (other-bytes 1024))
          n (count blocks)
          s (-> (net [:a :b] {:b tampered} {:seed 6}
                     {:max-blocks (inc n) :max-tree-nodes 1000})
                (sim/run-until 5000)
                (sim/send-message :app :a {:type :content/fetch-tree
                                           :root (:key split)})
                (sim/run-until 40000))
          sa (sim/node-state s :a)]
      (is (not (c/have? sa victim)) "a tampered piece was accepted")
      (is (pos? (get-in sa [:stats :verify-failed])))
      (testing "so the value is known-incomplete rather than silently corrupt"
        (let [manifest (get-in sa [:blocks (:key split)])]
          (is (= [victim] (ch/missing-pieces manifest (set (keys (:blocks sa)))))))))))
