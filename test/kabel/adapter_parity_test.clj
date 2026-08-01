(ns kabel.adapter-parity-test
  "kabel's own roundtrip, run over http-kit AND over Jetty 12.

  `kabel.ring-ws` is only an abstraction if something other than http-kit can
  drive it. One implementation agrees with itself whatever it does with the
  Ring contract; two agreeing is evidence about the contract. Same reason the
  permessage-deflate tests run against an independent server -- and that found
  a real RFC bug.

  Deliberately the SAME shape as `kabel.core-test/roundtrip-test`, so a failure
  here is about the adapter and not about a hand-rolled peer.

  Jetty is an OPTIONAL test dependency, behind the `:jetty` alias -- kabel does
  not declare `ring-jetty9-adapter` and http-kit stays its only runtime server.
  So `kabel.jetty` is resolved dynamically: a static `:require` would make a
  plain `clj -X:test` fail to LOAD this namespace, turning an absent optional
  dependency into a broken test suite for anyone who just cloned the repo.
  Without the alias the Jetty half skips with a message rather than passing
  vacuously -- the same bargain `kabel.permessage-deflate-test` makes. CI runs
  `clj -X:auth:test:jetty`, so parity is genuinely exercised there."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.peer :as peer]
            [kabel.ring-ws :as ring-ws]
            [superv.async :refer [<?? go-try S <? put?]]
            [clojure.core.async :refer [timeout go-loop <! >! chan]]
            ;; The real adapters, not copies of them: a parity test that
            ;; reimplements the thing it is testing proves nothing.
            [org.httpkit.server :as http-kit]
            [kabel.http-kit :as kabel-http-kit]))

(def ^:private jetty
  "`kabel.jetty`'s two public fns, or nil when the adapter is absent."
  (try
    (require 'kabel.jetty)
    {:run-server @(resolve 'kabel.jetty/jetty-run-server)
     :create-handler! @(resolve 'kabel.jetty/create-jetty-handler!)}
    (catch Exception _ nil)))

(defn- skip! [what]
  (println "SKIPPED" what "— no ring-jetty9-adapter on the classpath"
           "(run with the :jetty alias)"))

(defn- pong-middleware [[S peer [in out]]]
  (let [new-in (chan) new-out (chan)]
    (go-loop [i (<! in)]
      (when i (>! out i) (recur (<! in))))
    [S peer [new-in new-out]]))

(defn- roundtrip-over
  "kabel.core-test/roundtrip-test, with the server injected."
  [port run-server]
  (let [sid (java.util.UUID/randomUUID)
        cid (java.util.UUID/randomUUID)
        url (str "ws://localhost:" port)
        got (chan)
        handler (ring-ws/create-ws-handler! S url sid (atom {}) (atom {})
                                            {:run-server run-server})
        speer (peer/server-peer S handler sid pong-middleware identity)
        cpeer (peer/client-peer S cid
                                (fn [[S peer [in out]]]
                                  (let [new-in (chan) new-out (chan)]
                                    (go-try S
                                            (put? S out "ping")
                                            (put? S got (<? S in)))
                                    [S peer [new-in new-out]]))
                                identity)]
    (try
      (<?? S (peer/start speer))
      (<?? S (peer/connect S cpeer url))
      (first (clojure.core.async/alts!! [got (timeout 8000)]))
      (finally (<?? S (peer/stop speer))))))

(deftest ring-ws-works-on-both-adapters
  (testing "the same handler driven by two independent Ring WebSocket
            implementations. Passing on http-kit and failing on Jetty would
            mean kabel.ring-ws is still coupled to http-kit."
    (testing "http-kit"
      (is (= "ping" (roundtrip-over 47411 http-kit/run-server))))
    (testing "Jetty 12"
      (if-not jetty
        (skip! "ring-ws-works-on-both-adapters/Jetty 12")
        (is (= "ping" (roundtrip-over 47412 (:run-server jetty))))))))

(deftest create-handler-entry-points-agree
  (testing "the two namespaces a user actually calls, not just the run-server
            they wrap. create-jetty-handler! and create-http-kit-handler! take
            the same arguments and return the same map shape, so switching
            servers is a one-line change."
    (when-not jetty
      (skip! "create-handler-entry-points-agree/kabel.jetty"))
    (doseq [[nm port create!] (cond-> [["kabel.http-kit" 47415
                                        kabel-http-kit/create-http-kit-handler!]]
                                jetty (conj ["kabel.jetty" 47416
                                             (:create-handler! jetty)]))]
      (testing nm
        (let [sid (java.util.UUID/randomUUID)
              cid (java.util.UUID/randomUUID)
              url (str "ws://localhost:" port)
              got (chan)
              handler (create! S url sid)
              speer (peer/server-peer S handler sid pong-middleware identity)
              cpeer (peer/client-peer S cid
                                      (fn [[S peer [in out]]]
                                        (let [new-in (chan) new-out (chan)]
                                          (go-try S
                                                  (put? S out "ping")
                                                  (put? S got (<? S in)))
                                          [S peer [new-in new-out]]))
                                      identity)]
          (is (= #{:new-conns :channel-hub :context-hub :start-fn :url :handler}
                 (set (keys handler)))
              (str nm " returns the documented map shape"))
          (try
            (<?? S (peer/start speer))
            (<?? S (peer/connect S cpeer url))
            (is (= "ping" (first (clojure.core.async/alts!! [got (timeout 8000)])))
                (str nm " round-trips"))
            (finally (<?? S (peer/stop speer)))))))))
