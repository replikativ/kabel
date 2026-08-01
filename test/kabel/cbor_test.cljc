(ns kabel.cbor-test
  "The CBOR middleware.

  Several of these assert things the fressian path cannot do at all, and one
  asserts a failure mode konserve's clj-cbor serializer had — rejecting
  handlers — so that it cannot come back."
  (:require
   #?(:clj [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing async]])
   #?(:clj [superv.async :refer [<?? go-try S <? put?]]
      :cljs [superv.async :refer [go-try S <? put?] :include-macros true])
   [boring.core :as boring]
   [boring.data :as bdata]
   [clojure.core.async :refer [chan]]
   [kabel.middleware.cbor :refer [cbor record-registry]]
   [kabel.middleware.dual :refer [dual-read-cbor-write dual-read-fressian-write]]
   [kabel.middleware.fressian :refer [fressian]])
  #?(:cljs (:require-macros [clojure.core.async :refer [go]])))

(defrecord WirePoint [x y])

(defn- mw [make]
  (let [in (chan) out (chan)
        [_ _ [tin tout]] (make [S nil [in out]])]
    {:in in :out out :tin tin :tout tout}))

#?(:clj
   (deftest boring-frame-shape
     (testing "a non-map value goes out tagged :cbor with a byte payload"
       (let [{:keys [out tout]} (mw #(cbor %))]
         (put? S tout [1 :cbor "string"])
         (let [{:keys [kabel/serialization kabel/payload]} (<?? S out)]
           (is (= :cbor serialization))
           (is (bytes? payload)))))))

#?(:clj
   (deftest boring-round-trip
     (testing "values survive a full encode/decode through the middleware"
       (doseq [v [[1 :cbor "string"]
                  {:a 1 :b [2 3] :c #{:x :y}}
                  #uuid "9682952b-fafa-4b41-8e4a-31ae948d6f08"
                  {:nested {:deep {:er [1 2 3]}}}]]
         (let [{:keys [in tin]} (mw #(cbor %))]
           (put? S in {:kabel/serialization :cbor
                       :kabel/payload (boring/encode v)})
           (is (= v (<?? S tin)) (pr-str v)))))))

#?(:clj
   (deftest map-metadata-is-merged
     (testing "a decoded MAP must absorb the frame's other keys (:host and
               friends). This is the contract the fressian middleware has and
               it is easy to drop when porting."
       (let [{:keys [in tin]} (mw #(cbor %))]
         (put? S in {:kabel/serialization :cbor
                     :kabel/payload (boring/encode {:a 1})
                     :kabel/host "example.com"})
         (is (= {:a 1 :kabel/host "example.com"} (<?? S tin)))))))

#?(:clj
   (deftest records-round-trip-with-no-registration
     (testing "boring writes a record's type name natively via CBOR tag 27, so
               unlike fressian it needs NO write handler. Without a read
               handler the value still carries its name and fields rather than
               being lost — which is what makes dropping incognito safe."
       (let [{:keys [in tin]} (mw #(cbor %))]
         (put? S in {:kabel/serialization :cbor
                     :kabel/payload (boring/encode (->WirePoint 3 4))})
         (let [back (<?? S tin)]
           (is (some? back))
           (is (= 3 (:x back)))
           (is (= 4 (:y back))))))))

#?(:clj
   (deftest records-reconstruct-via-incognito-handlers
     (testing "incognito keys handlers by the normalized type symbol, which is
               exactly boring's own wire name — so the bridge is a rename"
       (let [inc-atom (atom {'kabel.cbor_test.WirePoint map->WirePoint})
             {:keys [in tin]} (mw #(cbor (atom (boring/tag-registry)) (atom {})
                                         inc-atom (atom {}) %))]
         (put? S in {:kabel/serialization :cbor
                     :kabel/payload (boring/encode (->WirePoint 3 4))})
         (let [back (<?? S tin)]
           (is (= (->WirePoint 3 4) back))
           (is (= WirePoint (type back))))))))

#?(:clj
   (deftest write-handlers-are-accepted-not-rejected
     (testing "konserve's clj-cbor serializer THREW on any handler, which is
               precisely why it could never carry a record. Passing a non-empty
               write-handlers atom must be a no-op, not an error."
       (let [{:keys [in tin]} (mw #(cbor (atom (boring/tag-registry))
                                         (atom {'some.Thing identity})
                                         (atom {}) (atom {'some.Thing identity}) %))]
         (put? S in {:kabel/serialization :cbor
                     :kabel/payload (boring/encode {:a 1})})
         (is (= {:a 1} (<?? S tin)))))))

(deftest incognito-fold-is-a-rename
  (testing "record-registry keys by the symbol's string form"
    (let [reg (record-registry (boring/tag-registry)
                               {'my.ns.Thing (fn [m] (assoc m :built true))})
          bs (boring/encode (bdata/unknown-record "my.ns.Thing" {:a 1}))]
      (is (= {:a 1 :built true} (boring/decode bs {:registry reg}))))))

#?(:clj
   (deftest dual-format-reads-both-writes-one
     (testing "the rollout mechanism. Composition alone must yield a peer that
               understands 13 AND 14; which one it WRITES is decided by which
               middleware is outermost."
       (testing "dual-read-cbor-write reads a fressian frame"
         (let [{:keys [in tin]} (mw dual-read-cbor-write)]
           (put? S in {:kabel/serialization :fressian
                       :kabel/payload (let [baos (java.io.ByteArrayOutputStream.)
                                            w (clojure.data.fressian/create-writer baos)]
                                        (clojure.data.fressian/write-object w {:a 1})
                                        (.toByteArray baos))})
           (is (= {:a 1} (<?? S tin)))))
       (testing "and a CBOR frame"
         (let [{:keys [in tin]} (mw dual-read-cbor-write)]
           (put? S in {:kabel/serialization :cbor
                       :kabel/payload (boring/encode {:a 1})})
           (is (= {:a 1} (<?? S tin)))))
       (testing "writing :cbor"
         (let [{:keys [out tout]} (mw dual-read-cbor-write)]
           (put? S tout {:a 1})
           (is (= :cbor (:kabel/serialization (<?? S out))))))
       (testing "while dual-read-fressian-write still writes :fressian — this
                 is step 1 of the rollout, and it must not change the wire"
         (let [{:keys [out tout]} (mw dual-read-fressian-write)]
           (put? S tout {:a 1})
           (is (= :fressian (:kabel/serialization (<?? S out)))))
         (testing "yet still reads :cbor"
           (let [{:keys [in tin]} (mw dual-read-fressian-write)]
             (put? S in {:kabel/serialization :cbor
                         :kabel/payload (boring/encode {:a 1})})
             (is (= {:a 1} (<?? S tin)))))))))

#?(:clj
   (deftest registry-changes-reach-the-decoder
     (testing "registering a tag AFTER the middleware is built must take effect
               in BOTH directions.

               The decoder memoises its registry, and the cache used to be keyed
               on the incognito handler map alone. Since the encoder derefs
               registry-atom per frame, a new registration took effect for
               writing immediately and for reading never -- for the life of the
               connection, in one direction only, with no error. Keyed on the
               base registry too."
       (let [reg-atom (atom (boring/tag-registry))
             {:keys [in tin]} (mw #(cbor reg-atom nil %))]
         ;; Decode once to populate the cache.
         (put? S in {:kabel/serialization :cbor :kabel/payload (boring/encode {:a 1})})
         (is (= {:a 1} (<?? S tin)) "warm the registry cache")

         ;; Register a record constructor, then decode a value that needs it.
         (swap! reg-atom boring/register-record
                "kabel.cbor_test.WirePoint" map->WirePoint)
         (put? S in {:kabel/serialization :cbor
                     :kabel/payload (boring/encode
                                     (bdata/unknown-record "kabel.cbor_test.WirePoint"
                                                           {:x 3 :y 4}))})
         (is (= (->WirePoint 3 4) (<?? S tin))
             "the decoder sees the registration, not the registry it cached")))))
