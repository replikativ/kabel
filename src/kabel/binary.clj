(ns kabel.binary
  "This namespace provides a minimal binary encoding for all connection types."
  (:require [clojure.edn :as edn])
  (:import [java.io ByteArrayOutputStream DataOutputStream
            ByteArrayInputStream DataInputStream]))

(def encoding-table {:binary          0
                     :string          1
                     :pr-str          2
                     :transit-json    11
                     :transit-msgpack 12
                     :fressian        13})

(def decoding-table (into {} (map (fn [[k v]] [v k])) encoding-table))

(defn to-binary [{:keys [kabel/serialization kabel/payload] :as m}]
  (let [payload (if-not serialization
                  (.getBytes (pr-str m)) ;; fallback if no serialization middleware is present
                  payload)
        serialization (if-not serialization :pr-str serialization)
        baos (ByteArrayOutputStream.)
        dos (DataOutputStream. baos)]
    (.writeInt dos (int (encoding-table serialization)))
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






