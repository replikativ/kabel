(ns kabel.binary
  "This namespace provides a minimal binary encoding for all connection types."
  (:require [clojure.edn :as edn]
            [kabel.binary.table :as table])
  (:import [java.io ByteArrayOutputStream DataOutputStream
            ByteArrayInputStream DataInputStream]))

;; Re-exported from kabel.binary.table so existing consumers keep working; the
;; table itself lives there because this ns is platform-split and the JVM and
;; ClojureScript copies used to drift.
(def encoding-table table/encoding-table)
(def decoding-table table/decoding-table)

(defn to-binary [{:keys [kabel/serialization kabel/payload] :as m}]
  (let [payload (if-not serialization
                  (.getBytes (pr-str m)) ;; fallback if no serialization middleware is present
                  payload)
        serialization (if-not serialization :pr-str serialization)
        baos (ByteArrayOutputStream.)
        dos (DataOutputStream. baos)]
    (.writeInt dos (int (table/encoding-for serialization)))
    (.flush dos)
    (.write baos payload)
    (.toByteArray baos)))

(defn from-binary [binary]
  (let [bais (ByteArrayInputStream. binary)
        dis (DataInputStream. bais)
        encoding (decoding-table (.readInt dis))
        payload (byte-array (- (count binary) 4))]
    (.readFully dis payload)
    (if (= encoding :pr-str)
      (edn/read-string (String. payload "UTF-8"))
      {:kabel/serialization encoding
       :kabel/payload payload})))






