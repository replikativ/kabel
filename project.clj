(defproject io.replikativ/kabel "0.2.2-SNAPSHOT"
  :description "A library for simple wire-like connectivity semantics."
  :url "https://github.com/replikativ/kabel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha16" :scope "provided"]
                 [org.clojure/clojurescript "1.9.542" :scope "provided"]
                 [io.replikativ/superv.async "0.2.7"]

                 [com.cognitect/transit-clj "0.8.285"]
                 [com.cognitect/transit-cljs "0.8.239" :scope "provided"]
                 [cheshire "5.7.1" :scope "provided"] ;; for JSON serialization

                 [io.replikativ/incognito "0.2.1"]

                 [io.replikativ/hasch "0.3.4"]

                 [org.slf4j/slf4j-api "1.7.25"] ;; TODO factor logging

                 [http-kit "2.2.0" :scope "provided"]
                 [org.glassfish.tyrus/tyrus-core "1.13.1"]
                 [org.glassfish.tyrus/tyrus-client "1.13.1"]
                 [org.glassfish.tyrus/tyrus-container-grizzly-client "1.13.1"]]

  :plugins [[lein-cljsbuild "1.1.4"]]

  :java-source-paths ["src/main/java"]

  :profiles {:dev {:dependencies [[figwheel-sidecar "0.5.10"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :figwheel {:nrepl-port 7888
                              :nrepl-middleware ["cider.nrepl/cider-middleware"
                                                 "cemerick.piggieback/wrap-cljs-repl"]}
                   :plugins [[lein-figwheel "0.5.8"]]
                   :repl-options {; for nREPL dev you really need to limit output
                                  :init (set! *print-length* 50)
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}
  :cljsbuild
  {:builds [{:id "cljs_repl"
             :source-paths ["src"]
             :figwheel true
             :compiler
             {:main kabel.client
              :asset-path "js/out"
              :output-to "resources/public/js/client.js"
              :output-dir "resources/public/js/out"
              :optimizations :none
              :pretty-print true}}
            {:id "test"
             :source-paths ["src" "test"]
             :compiler
             {:output-to "resources/private/js/unit-test.js"
              :output-dir "resources/private/js/out"
              :optimizations :whitespace
              :pretty-print true}}]
   :test-commands {"unit-tests" ["phantomjs" "resources/private/js/test.js"
                                 "resources/private/html/unit-test.html"]}}
  )
