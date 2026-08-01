(ns kabel.binary
  "This namespace provides a minimal binary encoding for all connection types."
  (:require [cljs.reader :refer [read-string]]
            [kabel.binary.table :as table]
            [hasch.platform :refer [utf8]]
            [kabel.util :refer [on-node?]]
            [goog.crypt :as crypt]))

;; TODO this namespace needs a refactoring once the target platforms for js are
;; pinned down

;; Re-exported from kabel.binary.table so existing consumers keep working; the
;; table itself lives there because this ns is platform-split and the JVM and
;; ClojureScript copies used to drift.
(def encoding-table table/encoding-table)
(def decoding-table table/decoding-table)

(defn to-binary [{:keys [kabel/serialization kabel/payload] :as m}]
  (let [payload (if-not serialization
                  (utf8 (pr-str m)) ;; fallback if no serialization middleware is present
                  payload)
        serialization (if-not serialization :pr-str serialization)
        ;; The header is a 4-byte BIG-ENDIAN int, matching the JVM's
        ;; .writeInt. Writing only the low byte -- as this did -- agrees with
        ;; the JVM only for ids <= 255 and silently truncates above.
        e (table/encoding-for serialization)
        header (array (bit-and (bit-shift-right e 24) 0xff)
                      (bit-and (bit-shift-right e 16) 0xff)
                      (bit-and (bit-shift-right e 8) 0xff)
                      (bit-and e 0xff))
        ;; manual concat
        wrapped (js/Uint8Array. (+ 4 (.-length payload)))
        _ (.set wrapped (js/Uint8Array. header) 0)
        _ (.set wrapped (js/Uint8Array. payload) 4)]
    (if-not (on-node?)
      (if (exists? js/Blob)
        (js/Blob. #js [wrapped])
        ;; react native
        wrapped)
      (.from js/Buffer wrapped))))

(defn- header-id
  "The frame's 4-byte BIG-ENDIAN header id, from a Uint8Array of its first four
  bytes.

  Extracted because all three platform branches below need it and only one of
  them had it right: the Blob and react-native branches read `(aget 3)`, the low
  byte alone, which agrees with the JVM's `.writeInt` only for ids <= 255 and
  silently decodes a frame with id 256 as :binary (0). Latent rather than broken
  today -- every id in the table is small -- but it is the same defect that was
  fixed in the node branch, in the two branches that were missed."
  [hdr]
  (+ (* (aget hdr 0) 0x1000000) (* (aget hdr 1) 0x10000)
     (* (aget hdr 2) 0x100) (aget hdr 3)))

(defn from-binary [binary cb]
  (let [l (if (on-node?)
            (.-length binary) ;; Buffer
            (if (exists? js/Blob)
              (.-size binary) ;; Blob
              (.-byteLength binary) ;; react native array buffer
              ))]
    (if (on-node?)
      (cb
       (let [hdr (js/Uint8Array. (.slice binary 0 4))
             payload (.slice binary 4 l)]
         (try
           ;; Inside the try: an unknown id now throws rather than yielding nil,
           ;; and the caller learns about it as an ex-info value the same way it
           ;; learns about a parse failure.
           (if (= (table/decoding-for (header-id hdr)) :pr-str)
             (-> (.toString (.from js/Buffer payload) "utf8") read-string)
             {:kabel/serialization (table/decoding-for (header-id hdr))
              :kabel/payload (.from js/Buffer payload)})
           (catch js/Error e
             (ex-info "Cannot parse binary." {:error e})))))
      (if (exists? js/Blob)
        (let [fr (js/FileReader.)]
          (set! (.-onload fr)
                #(let [b (.. % -target -result)
                       hdr (js/Uint8Array. (.slice b 0 4))
                       payload (js/Uint8Array. (.slice b 4 l))]
                   (cb
                    (try
                      (if (= (table/decoding-for (header-id hdr)) :pr-str)
                        (-> payload
                            crypt/utf8ByteArrayToString
                            read-string)
                        {:kabel/serialization (table/decoding-for (header-id hdr))
                         :kabel/payload payload})
                      (catch js/Error e
                        (ex-info "Cannot parse binary."
                                 {:error e}))))))
          (.readAsArrayBuffer fr binary))
        ;; react native as array buffer
        (let [b binary
              hdr (js/Uint8Array. (.slice b 0 4))
              payload (js/Uint8Array. (.slice b 4 l))]
          (cb
           (try
             (if (= (table/decoding-for (header-id hdr)) :pr-str)
               (-> payload
                   crypt/utf8ByteArrayToString
                   read-string)
               {:kabel/serialization (table/decoding-for (header-id hdr))
                :kabel/payload payload})
             (catch js/Error e
               (ex-info "Cannot parse binary."
                        {:error e})))))))))




