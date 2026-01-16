(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'org.replikativ/kabel)
(def major 0)
(def minor 3)
(defn commit-count [] (b/git-count-revs nil))
(defn version [] (format "%d.%d.%s" major minor (commit-count)))
(def class-dir "target/classes")
(defn jar-file [] (format "target/%s-%s.jar" (name lib) (version)))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-java [_]
  (b/javac {:src-dirs ["src/main/java"]
            :class-dir class-dir
            :basis @basis})
  (println "Java compilation complete"))

(defn jar [_]
  (compile-java nil)
  (let [v (version)
        jf (jar-file)]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version v
                  :basis @basis
                  :src-dirs ["src"]
                  :scm {:url "https://github.com/replikativ/kabel"
                        :connection "scm:git:git://github.com/replikativ/kabel.git"
                        :developerConnection "scm:git:ssh://git@github.com/replikativ/kabel.git"
                        :tag (b/git-process {:git-args "rev-parse HEAD"})}
                  :pom-data [[:description "A library for simple wire-like connectivity semantics."]
                             [:url "https://github.com/replikativ/kabel"]
                             [:licenses
                              [:license
                               [:name "Eclipse Public License"]
                               [:url "http://www.eclipse.org/legal/epl-v10.html"]]]]})
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jf})
    (println "JAR created:" jf)))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Deployed to Clojars!"))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version (version)
              :jar-file (jar-file)
              :class-dir class-dir})
  (println "Installed to local Maven repo"))

(defn compile-all [_]
  (clean nil)
  (compile-java nil)
  (println "All compilation complete"))
