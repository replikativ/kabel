(ns kabel.binary-test
  (:require [kabel.binary :refer [to-binary from-binary]]
            [clojure.test :refer :all]))

(deftest to-binary-test
  (is (= (->>
          (to-binary {:kabel/serialization :transit-json
                      :kabel/payload (byte-array [1 2 3])})
          vec)
         [0 0 0 11 1 2 3])))

(deftest cbor-frame-test
  (is (= [0 0 0 14 1 2 3]
         (vec (to-binary {:kabel/serialization :cbor
                          :kabel/payload (byte-array [1 2 3])})))))

(deftest older-peers-keep-working-test
  (testing "a frame built by HAND, not by to-binary, must still decode as
            :fressian. Round-tripping our own writer would not prove this --
            the point is that bytes from a peer that predates :cbor are
            unaffected by adding it."
    (let [frame (byte-array [0 0 0 13 1 2 3])
          {:keys [kabel/serialization kabel/payload]} (from-binary frame)]
      (is (= :fressian serialization))
      (is (= [1 2 3] (vec payload))))))

(deftest an-unknown-frame-id-does-not-reach-application-code
  (testing "a frame from a peer using a codec this build has never heard of.
            It used to decode to {:kabel/serialization nil :kabel/payload bytes}
            and pass through every middleware guard untouched, so application
            code received raw undecoded bytes shaped like a real message.

            Asserted with a hand-built frame because to-binary refuses to WRITE
            an unknown id — the read side is a separate defence and needs its
            own evidence."
    (let [frame (byte-array [0 0 0 99 1 2 3])
          e (try (from-binary frame) (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e)
          "loud, rather than a map with a nil serialization")
      (is (= :kabel/unknown-serialization-id (:type (ex-data e))))
      (is (= 99 (:id (ex-data e)))))))

(deftest from-binary-test
  (let [bin (to-binary {:kabel/serialization :transit-json
                        :kabel/payload (byte-array [1 2 3])})
        bin (to-binary {:foo "bar"})]
    (is (= (from-binary bin)
           {:foo "bar"}))))
