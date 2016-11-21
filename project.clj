(defproject io.replikativ/kabel "0.1.9-SNAPSHOT"
  :description "A library for simple wire-like connectivity semantics."
  :url "https://github.com/replikativ/kabel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [org.clojure/clojurescript "1.9.229"]
                 [io.replikativ/superv.async "0.2.2-SNAPSHOT"]

                 [http-kit "2.2.0"]
                 [http.async.client "1.2.0"]
                 [aleph "0.4.2-alpha8"]

                 [com.cognitect/transit-cljs "0.8.239"] ;; TODO remove
                                                        ;; once cljs
                                                        ;; works again
                                                        ;; without it
                 [io.replikativ/hasch "0.3.1"]

                 [org.slf4j/slf4j-api "1.7.12"]])
