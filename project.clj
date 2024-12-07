(defproject io.replikativ/kabel "0.2.3-SNAPSHOT"
  :description "A library for simple wire-like connectivity semantics."
  :url "https://github.com/replikativ/kabel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1" :scope "provided"]
                 [org.clojure/clojurescript "1.11.132" :scope "provided"]

                 [com.cognitect/transit-clj "1.0.333"]
                 [com.cognitect/transit-cljs "0.8.280" :scope "provided"]
                 [cheshire "5.13.0" :scope "provided"] ;; for JSON serialization

                 [io.replikativ/superv.async "0.3.48"]
                 [io.replikativ/incognito "0.3.66"]
                 [io.replikativ/hasch "0.3.94"]

                 [com.taoensso/timbre "6.6.1"]

                 [http-kit "2.8.0"  :scope "provided"]
                 [org.glassfish.tyrus/tyrus-core "1.21"]
                 [org.glassfish.tyrus/tyrus-client "1.21"]
                 [org.glassfish.tyrus/tyrus-container-grizzly-client "1.21"]]

  :plugins [[lein-cljsbuild "1.1.8"]]

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
