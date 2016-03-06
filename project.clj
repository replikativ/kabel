(defproject io.replikativ/kabel "0.1.4-SNAPSHOT"
  :description "A library for simple wire-like connectivity semantics."
  :url "https://github.com/replikativ/kabel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]

                 [http-kit "2.1.21-alpha2"]
                 [http.async.client "0.6.0"]

                 [com.cognitect/transit-cljs "0.8.232"] ;; TODO remove
                                                        ;; once cljs
                                                        ;; works again
                                                        ;; without it
                 [io.replikativ/incognito "0.2.0-beta1"]
                 [io.replikativ/hasch "0.3.0-beta6"]
                 [io.replikativ/konserve "0.3.3"]

                 [es.topiq/full.async "0.2.8-beta1"]
                 [kordano/full.cljs.async "0.1.3-alpha"]

                 [com.taoensso/timbre "4.0.2"]])
