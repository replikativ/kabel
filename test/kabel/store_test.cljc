(ns kabel.store-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.store.memory :as mem]
            [kabel.store.protocol :as p]
            #?(:clj [clojure.core.async :refer [<!!]]
               :cljs [clojure.core.async :refer [go <!]])
            #?(:cljs [cljs.test :refer [async]])))

#?(:clj
   (deftest memory-store-roundtrip
     (let [s (mem/new-memory-store)]
       (testing "a missing key yields nil rather than throwing"
         (is (nil? (<!! (p/-load s :nothing)))))

       (testing "store and load"
         (is (= {:a 1} (<!! (p/-store! s :book {:a 1}))))
         (is (= {:a 1} (<!! (p/-load s :book)))))

       (testing "keys"
         (<!! (p/-store! s :epoch 7))
         (is (= #{:book :epoch} (<!! (p/-keys* s)))))

       (testing "remove"
         (is (true? (<!! (p/-remove! s :book))))
         (is (nil? (<!! (p/-load s :book))))
         (is (= #{:epoch} (<!! (p/-keys* s)))))

       (testing "an initial map seeds the store, which is how a caller supplies
                 a keypair without a durable implementation"
         (let [s2 (mem/new-memory-store {:identity {:public "pk"}})]
           (is (= {:public "pk"} (<!! (p/-load s2 :identity)))))))))

#?(:cljs
   (deftest memory-store-roundtrip-cljs
     (async done
            (go
              (let [s (mem/new-memory-store)]
                (is (nil? (<! (p/-load s :nothing))))
                (is (= {:a 1} (<! (p/-store! s :book {:a 1}))))
                (is (= {:a 1} (<! (p/-load s :book))))
                (is (= #{:book} (<! (p/-keys* s))))
                (is (true? (<! (p/-remove! s :book))))
                (is (nil? (<! (p/-load s :book)))))
              (done)))))

(deftest monotonic-epoch-never-goes-backwards
  (testing "with no history it is simply the clock"
    (let [e (p/monotonic-epoch nil)]
      (is (pos? e))))

  (testing "a clock that jumped backwards cannot reissue a used epoch"
    ;; This is the correctness requirement the epoch exists for: if a peer
    ;; reused an epoch after an NTP step-back, its fresh sequence numbers
    ;; would be suppressed as duplicates by peers remembering the old run.
    (let [future-epoch (+ (p/monotonic-epoch nil) 1000000)]
      (is (> (p/monotonic-epoch future-epoch) future-epoch))))

  (testing "successive epochs strictly increase even within one millisecond"
    (let [a (p/monotonic-epoch nil)
          b (p/monotonic-epoch a)
          c (p/monotonic-epoch b)]
      (is (< a b))
      (is (< b c)))))
