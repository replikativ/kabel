(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.replikativ/kabel)
(def version "0.2.3-SNAPSHOT")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-java [_]
  (b/javac {:src-dirs ["src/main/java"]
            :class-dir class-dir
            :basis basis})
  (println "Java compilation complete"))

(defn compile-all [_]
  (clean nil)
  (compile-java nil)
  (println "All compilation complete"))