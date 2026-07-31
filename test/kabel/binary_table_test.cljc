(ns kabel.binary-table-test
  "The frame encoding table is durable wire state: the id is written into every
  frame and peers do not negotiate. These tests exist to make a renumbering a
  test failure rather than a silent wire break."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.binary.table :as table]))

(deftest table-is-pinned-exactly
  (testing "the WHOLE table, not just the new entry — an id that changes
            meaning breaks every peer already deployed, and only an exact
            assertion catches a renumbering of an existing one"
    (is (= {:binary          0
            :string          1
            :pr-str          2
            :transit-json    11
            :transit-msgpack 12
            :fressian        13
            :boring          14}
           table/encoding-table))))

(deftest boring-is-additive
  (testing "adding :boring must not have disturbed any existing id"
    (is (= 13 (get table/encoding-table :fressian)))
    (is (= :fressian (get table/decoding-table 13)))
    (is (= 14 (get table/encoding-table :boring)))
    (is (= :boring (get table/decoding-table 14)))))

(deftest decoding-table-is-a-bijection
  (testing "two keywords sharing an id would make decoding ambiguous"
    (is (= (count table/encoding-table) (count table/decoding-table)))))

(deftest an-unknown-frame-id-fails-loudly
  (testing "an unknown id used to decode to nil, and nil flowed onward: every
            serialization middleware guards its in-branch on a match, so the
            frame fell through all of them and the RAW payload reached
            application code as though it were a decoded value.

            That is the failure a peer hits when it meets a codec added after it
            was built, and silence is the worst possible shape for it. A dead
            connection carrying a typed error naming the id is diagnosable."
    (let [e (try (table/decoding-for 99)
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (= :kabel/unknown-serialization-id (:type (ex-data e))))
      (is (= 99 (:id (ex-data e))) "the id is in the data, not only the message"))
    (testing "and known ids still resolve"
      (is (= :fressian (table/decoding-for 13)))
      (is (= :boring (table/decoding-for 14)))
      (is (= :binary (table/decoding-for 0)) "including 0, which is falsy-adjacent"))))

(deftest unknown-serialization-throws-on-both-platforms
  (testing "this used to diverge: the JVM threw an NPE from (int nil) while
            ClojureScript silently wrote 0 — which is :binary — because
            Uint8Array coerces nil to 0. Producing a VALID frame for the WRONG
            codec is the worse failure, so both must now throw."
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (table/encoding-for :no-such-codec)))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (table/encoding-for nil)))
    (testing "and known ones still resolve"
      (is (= 13 (table/encoding-for :fressian)))
      (is (= 14 (table/encoding-for :boring))))))
