(ns kabel.middleware.fressian
  "Fressian serialization middleware for Kabel.

   Supports custom Fressian read/write handlers in addition to Incognito handlers.
   Handler merging strategy follows Konserve's implementation for compatibility."
  (:require [kabel.middleware.handler :refer [handler]]
            #?(:clj [superv.async :refer [go-try]])
            #?(:clj [clojure.data.fressian :as fress]
               :cljs [fress.api :as fress])
            [incognito.fressian :refer [incognito-read-handlers
                                        incognito-write-handlers]])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream])
     :cljs (:require-macros [superv.async :refer [go-try]]
                            [clojure.core.async :refer [go]])))

(defn merge-read-handlers
  "Merges custom and incognito read handlers following Konserve's strategy.
   On JVM: applies associative-lookup for efficient handler dispatch.
   On JS: simple merge is sufficient."
  [custom-handlers incognito-handlers-atom]
  (let [incognito-h (incognito-read-handlers incognito-handlers-atom)]
    #?(:clj (-> (merge fress/clojure-read-handlers
                       custom-handlers
                       incognito-h)
                fress/associative-lookup)
       :cljs (merge custom-handlers incognito-h))))

(defn merge-write-handlers
  "Merges custom and incognito write handlers following Konserve's strategy.
   On JVM: applies associative-lookup and inheritance-lookup.
   On JS: simple merge is sufficient."
  [custom-handlers incognito-handlers-atom]
  (let [incognito-h (incognito-write-handlers incognito-handlers-atom)]
    #?(:clj (-> (merge fress/clojure-write-handlers
                       custom-handlers
                       incognito-h)
                fress/associative-lookup
                fress/inheritance-lookup)
       :cljs (merge custom-handlers incognito-h))))

(defn fressian-read
  "Reads a value from Fressian-encoded bytes using the provided handlers."
  [bytes handlers]
  (fress/read bytes :handlers handlers))

(defn fressian-write
  "Writes a value to Fressian-encoded bytes using the provided handlers."
  [val handlers]
  #?(:clj (let [baos (ByteArrayOutputStream.)
                writer (fress/create-writer baos :handlers handlers)]
            (fress/write-object writer val)
            (.toByteArray baos))
     :cljs (fress/write val :handlers handlers)))

(defn fressian
  "Serializes all incoming and outgoing edn datastructures in Fressian format.

   Parameters:
   - custom-read-handlers: atom containing map of tag-name -> ReadHandler/function
   - custom-write-handlers: atom containing map of Class/Type -> {tag WriteHandler/function}
   - incognito-read-handlers-atom: atom for Incognito type handlers (optional, defaults to empty)
   - incognito-write-handlers-atom: atom for Incognito type handlers (optional, defaults to empty)

   Examples:
   ;; Basic usage with Incognito only
   (fressian [S peer [in out]])

   ;; With custom handlers
   (fressian (atom custom-read-handlers)
             (atom custom-write-handlers)
             [S peer [in out]])

   ;; With both custom and Incognito handlers
   (fressian (atom custom-read-handlers)
             (atom custom-write-handlers)
             incognito-read-atom
             incognito-write-atom
             [S peer [in out]])"
  ([[S peer [in out]]]
   (fressian (atom {}) (atom {}) (atom {}) (atom {}) [S peer [in out]]))
  ([custom-read-handlers custom-write-handlers [S peer [in out]]]
   (fressian custom-read-handlers custom-write-handlers
             (atom {}) (atom {}) [S peer [in out]]))
  ([custom-read-handlers custom-write-handlers
    incognito-read-handlers-atom incognito-write-handlers-atom
    [S peer [in out]]]
   (handler
     ;; Deserialize incoming messages
    #(go-try S
             (let [{:keys [kabel/serialization kabel/payload]} %]
               (if (= serialization :fressian)
                 (let [handlers (merge-read-handlers
                                 @custom-read-handlers
                                 incognito-read-handlers-atom)
                       v (fressian-read payload handlers)
                  ;; Merge any additional message metadata (like :host)
                       merged (if (map? v)
                                (merge v (dissoc % :kabel/serialization :kabel/payload))
                                v)]
                   #_(debug {:event :fressian-deserialized :value merged})
                   merged)
                 %)))

     ;; Serialize outgoing messages
    #(go-try S
             (if (:kabel/serialization %) ; already serialized
               %
               (do
                 #_(debug {:event :fressian-serialize :value %})
                 {:kabel/serialization :fressian
                  :kabel/payload (fressian-write
                                  %
                                  (merge-write-handlers
                                   @custom-write-handlers
                                   incognito-write-handlers-atom))})))

    [S peer [in out]])))
