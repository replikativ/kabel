(ns kabel.sealed-test
  (:require [clojure.test :refer [deftest testing is]]
            [hasch.core :refer [uuid]]
            [kabel.content :as c]
            [kabel.sealed :as sealed]
            [kabel.sim :as sim]
            #?(:cljs [cljs.reader])))

(defn- read-edn
  "Portable EDN read. `read-string` is JVM-only — in ClojureScript it lives in
  `cljs.reader`, and the difference compiles clean on one platform and fails on
  the other."
  [s]
  #?(:clj (read-string s)
     :cljs (cljs.reader/read-string s)))

(defn- fake-encrypt
  "Stands in for konserve's cipher. Reversible, keyed, and NOT cryptography —
  the point of the tests below is the transfer shape, not the algorithm."
  [k plaintext]
  {:k k :blob (pr-str plaintext)})

(defn- fake-decrypt [k ct]
  (when (= k (:k ct)) (read-edn (:blob ct))))

(defn- seal
  "A sealed block wrapping `plaintext`, naming `children`."
  [k children plaintext]
  (sealed/make-sealed children (fake-encrypt k plaintext)))

;; =============================================================================
;; Shape
;; =============================================================================

(deftest a-sealed-block-is-an-ordinary-content-addressed-block
  (let [b (seal "key" [] {:secret "diary"})]
    (testing "its address is the hash of the whole block"
      (is (= (uuid b) (sealed/address b))))

    (testing "so kabel.content verifies it with no special case"
      (is (c/verified? (sealed/address b) b)))

    (testing "and a tampered payload fails that check"
      (is (not (c/verified? (sealed/address b)
                            (assoc b :ciphertext (fake-encrypt "key" "other"))))))))

(deftest the-address-is-unguessable-without-the-key
  (testing "knowing the plaintext does not reveal where it lives"
    ;; This is what a hash(plaintext) storage index would leak: anyone who
    ;; guesses the content can locate it and confirm who holds it.
    (let [plaintext {:secret "diary"}
          mine (seal "my-key" [] plaintext)
          theirs (seal "their-key" [] plaintext)]
      (is (not= (sealed/address mine) (sealed/address theirs))
          "the same plaintext landed at the same address under different keys"))))

(deftest children-are-visible-so-a-provider-can-walk
  (testing "the addresses-fn sees children without reading anything"
    (let [leaf (seal "k" [] {:leaf 1})
          parent (seal "k" [(sealed/address leaf)] {:branch true})]
      (is (= [(sealed/address leaf)] (sealed/children parent)))

      (testing "under the default :addresses projector, so no caller needs to know"
        (is (= [(sealed/address leaf)] (:addresses parent))))))

  (testing "a plain block is not sealed"
    (is (not (sealed/sealed? {:addresses [] :plain true})))
    (is (nil? (sealed/children {:addresses [:a]})))))

;; =============================================================================
;; Transfer — the verify-cap tier
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

(deftest a-provider-serves-what-it-cannot-read
  (testing "a sealed DAG transfers in one exchange, and the provider stays blind"
    ;; Tahoe's verify-cap tier: hold, check, repair and re-serve content you
    ;; have no ability to decrypt. Tahoe designed the traversal half and never
    ;; shipped it; here it falls out of the existing tree walk.
    (let [k "reader-key"
          leaves (mapv #(seal k [] {:leaf %}) (range 4))
          parent (seal k (mapv sealed/address leaves) {:branch true})
          blocks (into {(sealed/address parent) parent}
                       (for [l leaves] [(sealed/address l) l]))
          n (count blocks)
          s (-> (net [:reader :provider] {:provider blocks} {:seed 5}
                     {:max-blocks (inc n) :max-tree-nodes 100})
                (sim/run-until 5000)
                (sim/send-message :app :reader
                                  {:type :content/fetch-tree
                                   :root (sealed/address parent)})
                (sim/run-until 40000))
          st (sim/node-state s :reader)]

      (testing "the whole sealed DAG arrived"
        (is (= n (count (:blocks st))))
        (is (every? #(c/have? st %) (keys blocks))))

      (testing "the reader can open it, because it holds the key"
        (let [got (get-in st [:blocks (sealed/address parent)])]
          (is (sealed/sealed? got))
          (is (= {:branch true} (fake-decrypt k (sealed/ciphertext got))))))

      (testing "and the provider never could have"
        ;; It served every byte and verified every address without the key.
        (let [pst (sim/node-state s :provider)
              held (get-in pst [:blocks (sealed/address parent)])]
          (is (nil? (fake-decrypt "wrong-key" (sealed/ciphertext held)))
              "the provider decrypted content it should not be able to read")
          (is (c/verified? (sealed/address parent) held)
              "but it can still verify what it serves"))))))

(deftest a-tampered-sealed-block-is-refused-in-transit
  (testing "sealing does not weaken the untrusted-provider guarantee"
    (let [k "key"
          leaf (seal k [] {:leaf 1})
          parent (seal k [(sealed/address leaf)] {:branch true})
          honest {(sealed/address parent) parent (sealed/address leaf) leaf}
          tampered (assoc honest (sealed/address leaf) (seal k [] {:leaf :evil}))
          s (-> (net [:reader :liar] {:liar tampered} {:seed 6}
                     {:max-blocks 10 :max-tree-nodes 100})
                (sim/run-until 5000)
                (sim/send-message :app :reader
                                  {:type :content/fetch-tree
                                   :root (sealed/address parent)})
                (sim/run-until 40000))
          st (sim/node-state s :reader)]
      (is (not (c/have? st (sealed/address leaf))) "a tampered sealed block was accepted")
      (is (pos? (get-in st [:stats :verify-failed]))))))
