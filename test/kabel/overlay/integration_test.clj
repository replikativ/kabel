(ns kabel.overlay.integration-test
  "The overlay over a real WebSocket, with real kabel peers.

  Everything below this level is tested in the simulator against pure state
  machines. These tests exist to check the part the simulator cannot: that the
  identity handshake completes over a real wire, that connections register
  under the right peer id, and that a publish crosses a socket."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.http-kit :as http-kit]
            [kabel.identity :as id]
            [kabel.overlay :as overlay]
            [kabel.overlay.runtime :as rt]
            [kabel.peer :as peer]
            [superv.async :refer [<?? S]]
            [clojure.core.async :refer [timeout chan >!! <! go-loop]]))

(def ^:private port-counter (atom 48300))

(defn- unique-port
  "Ports from a monotone counter, not `rand-int`.

  Random ports collide, and the collision got much more likely once membership
  started *retrying* dials: a client whose server has been stopped keeps
  redialling that port, so a later test that lands on it inherits a stranger's
  connection attempts. One flaky run is one too many for a race that a counter
  removes outright."
  []
  (swap! port-counter inc))

(defn- wait-for
  "Poll `pred` until it holds or `ms` elapses. Returns whether it held.

  Real sockets need a settling window; the simulator's virtual clock is what
  makes the *protocol* tests deterministic, so these need only be patient
  rather than precise."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (<?? S (timeout 50)) (recur))))))

(deftest handshake-identifies-both-ends
  (testing "two real peers connect, identify each other, and register"
    (let [port (unique-port)
          url (str "ws://localhost:" port)
          server-kp (<?? S (id/generate-identity))
          client-kp (<?? S (id/generate-identity))
          server-id (id/peer-id (:genesis server-kp))
          client-id (id/peer-id (:genesis client-kp))

          [server-mw install-server!] (rt/deferred-middleware)
          server-peer (peer/server-peer
                       S (http-kit/create-http-kit-handler! S url server-id)
                       server-id server-mw identity)
          server (<?? S (rt/start! S server-peer
                                   {:identity server-kp
                                    :addresses [url]
                                    :topics #{:t}}))]
      ;; server-peer captured server-mw lexically, so the middleware must be
      ;; installed through the indirection rather than swapped onto the atom.
      (install-server! (:middleware server))
      (<?? S (peer/start server-peer))
      (try
        (let [[client-mw install-client!] (rt/deferred-middleware)
              client-peer (peer/client-peer S client-id client-mw identity)
              client (<?? S (rt/start! S client-peer
                                       {:identity client-kp
                                        :addresses []
                                        :topics #{:t}
                                        :seeds [{:peer-id server-id
                                                 :addresses [url]
                                                 :group "seed"}]}))]
          (install-client! (:middleware client))
          (<?? S (peer/connect S client-peer url))

          (testing "each side learns the other's peer id from the signed hello"
            (is (wait-for 5000 #(contains? (rt/connections client-peer) server-id))
                "client never registered the server")
            (is (wait-for 5000 #(contains? (rt/connections server-peer) client-id))
                "server never registered the client"))

          (testing "and the ids are the ones derived from the public keys"
            ;; Not merely "some id" — the whole point of a self-certifying id
            ;; is that it is derivable from the key and cannot be asserted.
            (is (= #{server-id} (rt/connections client-peer)))
            (is (= #{client-id} (rt/connections server-peer))))

          (testing "membership records the connection"
            (is (wait-for 5000
                          #(overlay/connections (rt/overlay-state client))))
            (is (some #{server-id} (overlay/connections (rt/overlay-state client))))))
        (finally
          (<?? S (peer/stop server-peer)))))))

(deftest publish-crosses-a-real-socket
  (testing "a publish on one peer is delivered on the other"
    (let [port (unique-port)
          url (str "ws://localhost:" port)
          server-kp (<?? S (id/generate-identity))
          client-kp (<?? S (id/generate-identity))
          server-id (id/peer-id (:genesis server-kp))
          client-id (id/peer-id (:genesis client-kp))

          [server-mw install-server!] (rt/deferred-middleware)
          server-peer (peer/server-peer
                       S (http-kit/create-http-kit-handler! S url server-id)
                       server-id server-mw identity)
          server (<?? S (rt/start! S server-peer
                                   {:identity server-kp
                                    :addresses [url]
                                    :topics #{:t}}))]
      (install-server! (:middleware server))
      (<?? S (peer/start server-peer))
      (try
        (let [[client-mw install-client!] (rt/deferred-middleware)
              client-peer (peer/client-peer S client-id client-mw identity)
              client (<?? S (rt/start! S client-peer
                                       {:identity client-kp
                                        :addresses []
                                        :topics #{:t}
                                        :seeds [{:peer-id server-id
                                                 :addresses [url]
                                                 :group "seed"}]}))]
          (install-client! (:middleware client))
          (<?? S (peer/connect S client-peer url))
          (is (wait-for 5000 #(contains? (rt/connections server-peer) client-id)))

          (testing "client to server"
            (rt/publish! client :t "from-client")
            (is (wait-for 5000
                          #(= ["from-client"]
                              (overlay/delivered (rt/overlay-state server))))
                (str "server delivered "
                     (overlay/delivered (rt/overlay-state server)))))

          (testing "server to client"
            (rt/publish! server :t "from-server")
            (is (wait-for 5000
                          #(some #{"from-server"}
                                 (overlay/delivered (rt/overlay-state client))))
                (str "client delivered "
                     (overlay/delivered (rt/overlay-state client))))))
        (finally
          (<?? S (peer/stop server-peer)))))))

(deftest closing-a-socket-reaches-the-state-machine
  (testing "when the server goes away, the client stops believing in it"
    ;; The gap this closes: without feeding the close back in, membership holds
    ;; a connection nobody is draining, and — worse — never redials, because a
    ;; peer it believes it is connected to is not a dial candidate. kabel has
    ;; no reconnect of its own, so this path *is* the reconnect.
    (let [port (unique-port)
          url (str "ws://localhost:" port)
          server-kp (<?? S (id/generate-identity))
          client-kp (<?? S (id/generate-identity))
          server-id (id/peer-id (:genesis server-kp))
          client-id (id/peer-id (:genesis client-kp))

          [server-mw install-server!] (rt/deferred-middleware)
          server-peer (peer/server-peer
                       S (http-kit/create-http-kit-handler! S url server-id)
                       server-id server-mw identity)
          server (<?? S (rt/start! S server-peer
                                   {:identity server-kp
                                    :addresses [url]
                                    :topics #{:t}}))]
      (install-server! (:middleware server))
      (<?? S (peer/start server-peer))

      (let [[client-mw install-client!] (rt/deferred-middleware)
            client-peer (peer/client-peer S client-id client-mw identity)
            client (<?? S (rt/start! S client-peer
                                     {:identity client-kp
                                      :addresses []
                                      :topics #{:t}
                                      :seeds [{:peer-id server-id
                                               :addresses [url]
                                               :group "seed"}]}))]
        (install-client! (:middleware client))
        (<?? S (peer/connect S client-peer url))
        (is (wait-for 5000 #(contains? (rt/connections client-peer) server-id))
            "never connected, so the teardown proves nothing")
        (is (wait-for 5000 #(some #{server-id}
                                  (overlay/connections (rt/overlay-state client)))))

        (<?? S (peer/stop server-peer))

        (testing "the transport registry drops it"
          (is (wait-for 5000 #(not (contains? (rt/connections client-peer) server-id)))
              "the registry still holds a dead connection"))

        (testing "and so does membership"
          (is (wait-for 5000
                        #(not (some #{server-id}
                                    (overlay/connections (rt/overlay-state client)))))
              "membership still believes in a dead connection"))))))

(deftest forged-publishes-never-reach-the-state-machine
  (testing "a peer cannot publish under somebody else's identity"
    ;; The db-root case: an authenticated, connected peer tries to announce a
    ;; new root *as if* it came from the database's owner. Authorisation alone
    ;; would not stop this — the attacker is a legitimate member of the
    ;; network. Only the signature does.
    (let [attacker-kp (<?? S (id/generate-identity))
          victim-kp (<?? S (id/generate-identity))
          honest-kp (<?? S (id/generate-identity))
          attacker-id (id/peer-id (:genesis attacker-kp))
          victim-id (id/peer-id (:genesis victim-kp))

          seen (atom [])
          ctx (rt/make-runtime {:id (id/peer-id (:genesis honest-kp))
                                :state {}
                                :handler (fn [st ev _] (swap! seen conj ev)
                                           {:state st :actions []})
                                :effects {:send! (fn [_ _])
                                          :connect! (fn [_ _ _])
                                          :disconnect! (fn [_])
                                          :schedule! (fn [_ _])}})
          _ (rt/run! S ctx)

          peer (peer/client-peer S :honest identity identity)
          in (chan 16)
          out (chan 16)
          _ ((rt/middleware {:ctx ctx :identity honest-kp :addresses [] :seq-no 0})
             [S peer [in out]])
          ;; Drain what the middleware sends, or its hello blocks the loop.
          _ (go-loop [] (when (<! out) (recur)))

          gossip (fn [origin] {:type :gossip :origin origin :epoch 0 :seq 1
                               :topic :db/roots :hops 0 :payload {:root "x"}})]

      ;; The attacker completes a perfectly valid identity handshake — it is a
      ;; real peer, not an outsider.
      (>!! in {:type rt/hello-type
               :record (id/record->wire
                        (<?? S (id/sign-record attacker-kp [] 0)))})
      (is (wait-for 3000 #(contains? (rt/connections peer) attacker-id))
          "the attacker never connected, so the test proves nothing")

      (testing "a publish signed by the attacker but claiming the victim's id is dropped"
        (>!! in {:type rt/frame-type
                 :payload (<?? S (rt/sign-gossip S attacker-kp (gossip victim-id)))})
        (<?? S (timeout 300))
        (is (not-any? #(= victim-id (get-in % [:payload :origin])) @seen)
            "a forged publish reached the state machine"))

      (testing "an unsigned publish is dropped too"
        (>!! in {:type rt/frame-type :payload (gossip attacker-id)})
        (<?? S (timeout 300))
        (is (empty? (filter #(= :gossip (get-in % [:payload :type])) @seen))))

      (testing "a tampered payload is dropped"
        (let [signed (<?? S (rt/sign-gossip S attacker-kp (gossip attacker-id)))]
          (>!! in {:type rt/frame-type
                   :payload (assoc signed :payload {:root "tampered"})})
          (<?? S (timeout 300))
          (is (empty? (filter #(= :gossip (get-in % [:payload :type])) @seen)))))

      (testing "but the attacker's own genuine publish is accepted"
        ;; The check is authentication, not exclusion: a member may publish
        ;; under its own name. Whether it is *allowed* to set that topic is a
        ;; separate question, and that is what :authorize-fn is for.
        (>!! in {:type rt/frame-type
                 :payload (<?? S (rt/sign-gossip S attacker-kp (gossip attacker-id)))})
        (is (wait-for 3000
                      #(some (fn [e] (and (= :gossip (get-in e [:payload :type]))
                                          (= attacker-id (get-in e [:payload :origin]))))
                             @seen))
            "a genuine publish was rejected")))))

(deftest epoch-advances-across-restarts
  (testing "a fresh runtime never reuses an epoch"
    ;; The epoch is what keeps a restarted peer's sequence numbers from being
    ;; suppressed as duplicates of its previous run.
    (let [kp (<?? S (id/generate-identity))
          peer-id (id/peer-id (:genesis kp))
          p1 (peer/client-peer S peer-id identity identity)
          a (<?? S (rt/start! S p1 {:identity kp :addresses [] :topics #{:t}}))
          _ (<?? S (timeout 5))
          p2 (peer/client-peer S peer-id identity identity)
          b (<?? S (rt/start! S p2 {:identity kp :addresses [] :topics #{:t}}))]
      (is (< (:epoch a) (:epoch b)))
      (is (= peer-id (:peer-id a) (:peer-id b))
          "identity must survive a restart; the epoch must not"))))
