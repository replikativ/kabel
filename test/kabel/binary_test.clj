(ns kabel.binary-test
  (:require [kabel.binary :refer [to-binary from-binary]]
            [clojure.test :refer :all]))

(deftest to-binary-test
  (is (= (->>
          (to-binary {:kabel/serialization :transit-json
                      :kabel/payload (byte-array [1 2 3])})
          vec)
         [0 0 0 11 1 2 3])))

(deftest boring-frame-test
  (is (= [0 0 0 14 1 2 3]
         (vec (to-binary {:kabel/serialization :boring
                          :kabel/payload (byte-array [1 2 3])})))))

(deftest older-peers-keep-working-test
  (testing "a frame built by HAND, not by to-binary, must still decode as
            :fressian. Round-tripping our own writer would not prove this --
            the point is that bytes from a peer that predates :boring are
            unaffected by adding it."
    (let [frame (byte-array [0 0 0 13 1 2 3])
          {:keys [kabel/serialization kabel/payload]} (from-binary frame)]
      (is (= :fressian serialization))
      (is (= [1 2 3] (vec payload))))))

(deftest from-binary-test
  (let [bin (to-binary {:kabel/serialization :transit-json
                        :kabel/payload (byte-array [1 2 3])})
        bin (to-binary {:foo "bar"})]
    (is (= (from-binary bin)
           {:foo "bar"}))))
