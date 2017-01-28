(ns kabel.binary
  "This namespace provides a minimal binary encoding for all connection types."
  (:require [cljs.reader :refer [read-string]]
            [hasch.platform :refer [utf8]]))

(defn on-node? []
  (and (exists? js/process)
       (exists? js/process.versions)
       (exists? js/process.versions.node)
       true))

(def encoding-table {:binary          0
                     :string          1
                     :pr-str          2
                     :transit-json    11
                     :transit-msgpack 12})

(def decoding-table (into {} (map (fn [[k v]] [v k])) encoding-table))


(defn to-binary [{:keys [kabel/serialization kabel/payload] :as m}]
  (let [payload (if-not serialization
                  (utf8 (pr-str m)) ;; fallback if no serialization middleware is present
                  payload)
        serialization (if-not serialization :pr-str serialization)
        wrapped (-> (array 0 0 0 (encoding-table serialization))
                    (.concat payload)
                    (js/Uint8Array.))]
    (.log js/console "foo" wrapped)
    (if-not (on-node?)
      (js/Blob. #js [wrapped])
      (js/Buffer. wrapped))))


(defn from-binary [binary cb]
  (if-not (on-node?)
    ;; Browser
    (let [l (.-size binary)
          fr (js/FileReader.)]
      (set! (.-onload fr)
            #(let [b (.. % -target -result)

                   encoding (-> (.slice b 0 4)
                                (js/Uint8Array.)
                                (aget 3)
                                decoding-table)
                   payload (js/Uint8Array. (.slice b 4 l))]
               (cb
                (if (= encoding :pr-str)
                  (-> (js/TextDecoder. "utf-8")
                      (.decode payload)
                      read-string)
                  {:kabel/serialization encoding
                   :kabel/payload payload}))))
      (.readAsArrayBuffer fr binary))
    ;; nodejs TODO needs to be retested
    (let [l (.-length binary)
          encoding (-> (.slice binary 0 4)
                       (js/Uint8Array.)
                       (aget 3)
                       decoding-table)
          payload (js/Uint8Array. (.slice binary 4 l))]
      (cb
       (if (= encoding :pr-str)
         ;; TODO use also TextDecoder or non-stack blowing routine
         (js/String.fromCharCode.apply
          nil
          (js/Uint8Array. binary))
         {:kabel/serialization encoding
          :kabel/payload payload})))))




