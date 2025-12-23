(ns kabel.binary-test
  (:require [kabel.binary :refer [to-binary from-binary]]
            [clojure.test :refer :all]))

(deftest to-binary-test
  (is (= (->>
          (to-binary {:kabel/serialization :transit-json
                      :kabel/payload (byte-array [1 2 3])})
          vec)
         [0 0 0 11 1 2 3])))

(deftest from-binary-test
  (let [bin (to-binary {:kabel/serialization :transit-json
                        :kabel/payload (byte-array [1 2 3])})
        bin (to-binary {:foo "bar"})]
    (is (= (from-binary bin)
           {:foo "bar"}))))
