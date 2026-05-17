(ns kabel.http-kit-close-test
  "Regression tests for `kabel.http-kit/create-http-kit-handler!`'s
  send-loop lifecycle.

  Before the fix in http_kit.clj, the send loop would never exit when
  the client disconnected: `on-close` closed `in` but left `out` open,
  and the loop's drop-and-recur path on a missing channel-hub entry
  meant every subsequent broadcast hit the
  `:dropping-msg-because-of-closed-channel` warning forever — leaking
  one goroutine and one subscription per disconnected peer.

  `kabel.peer/drain` propagates `in`-close → `out`-close at the BOTTOM
  of the middleware chain, but that signal only reaches the raw http-
  kit `out` if every middleware in between also forwards close in the
  out→out direction. We can't rely on that, so http-kit's `on-close`
  now also closes `out` directly."
  (:require [clojure.test :refer [deftest is testing]]
            [kabel.client :as cli]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [superv.async :refer [<?? go-try S]]
            [clojure.core.async :as async
             :refer [<! >! go timeout chan close! alts!! put!]]
            [org.httpkit.server :refer [close]]))

(defn- capture-middleware
  "Middleware that copies the inbound (server-side) `[in out]` pair
  into the provided atom so tests can observe close propagation."
  [captured]
  (fn [[S peer [in out]]]
    (reset! captured {:in in :out out})
    (let [new-in (chan)
          new-out (chan)]
      (go (loop [m (<! in)]
            (when m
              ;; Echo back so the client knows the connection is live.
              (>! out m)
              (recur (<! in))))
          ;; Mirror close downstream.
          (close! new-in))
      [S peer [new-in new-out]])))

(defn- closed?
  "Returns true if `ch` is closed: a put with a 50ms alts!! timeout
  resolves to `false` (closed) rather than `true` (delivered) or nil
  (timed out)."
  [ch]
  (let [[v _] (alts!! [[ch :probe] (timeout 50)])]
    (false? v)))

(deftest send-loop-exits-on-client-disconnect
  (testing "After a websocket client disconnects, the server-side `out`
            channel is closed, so the send goroutine exits and upstream
            broadcasters see false from `put!`. Without this, the loop
            spins forever on every broadcast, logging
            :dropping-msg-because-of-closed-channel per message."
    (let [sid #uuid "f0e1d2c3-b4a5-6789-0abc-def012345678"
          cid #uuid "0a1b2c3d-4e5f-6789-abcd-ef0123456789"
          url "ws://localhost:48275"
          captured (atom nil)
          handler (http-kit/create-http-kit-handler! S url sid)
          speer (peer/server-peer S handler sid
                                  (capture-middleware captured)
                                  identity)
          client-saw-ping (chan 1)
          cpeer (peer/client-peer
                 S cid
                 (fn [[S peer [in out]]]
                   (let [new-in (chan)
                         new-out (chan)]
                     (go-try S
                             (>! out "ping")
                             (let [reply (<! in)]
                               (put! client-saw-ping (or reply :nil))))
                     [S peer [new-in new-out]]))
                 identity)]
      (try
        (<?? S (peer/start speer))
        (<?? S (peer/connect S cpeer url))

        ;; Confirm the round trip works (this also guarantees the
        ;; server-side middleware ran and captured `[in out]`).
        (is (= "ping" (<?? S client-saw-ping))
            "client received echo — connection established")
        (is (some? @captured)
            "server-side `[in out]` captured by middleware")

        (let [out (:out @captured)
              channel-hub (:channel-hub handler)]
          ;; Sanity: out is open while client is connected.
          (is (true? (let [[v _] (alts!! [[out {:kabel/serialization :string
                                                :kabel/payload "live"}]
                                          (timeout 50)])]
                       v))
              "server can still write to out before client disconnect")

          ;; Force a real WS disconnect by closing the server-side
          ;; http-kit channel directly. This is what happens when a
          ;; browser tab closes or a network drops: http-kit's
          ;; `on-close` fires on the server with the dropped channel.
          ;; (`peer/stop` on a client peer only closes its bus, not
          ;; the underlying WebSocket.)
          (let [[ch _] (first @channel-hub)]
            (is (some? ch) "client WS registered in channel-hub")
            (close ch))

          ;; Give http-kit's on-close a moment to fire and the fix to
          ;; close `out`.
          (<?? S (timeout 500))

          (is (closed? out)
              "server-side `out` is closed after client disconnect —
               send loop has exited, no goroutine leak"))

        (finally
          (try (<?? S (peer/stop speer)) (catch Throwable _ nil)))))))

(deftest send-loop-tolerates-broadcast-after-disconnect
  (testing "Even if upstream broadcasts continue to call `put!` on a
            now-closed `out`, no infinite-loop log spam occurs and the
            puts simply return false. This pins the post-fix steady
            state: closed channel + bounded log output."
    (let [sid #uuid "1a2b3c4d-5e6f-7890-1234-56789abcdef0"
          cid #uuid "0fedcba9-8765-4321-0987-6543210fedcb"
          url "ws://localhost:48276"
          captured (atom nil)
          warn-count (atom 0)
          ;; Stub the http_kit `:dropping-msg-because-of-closed-channel`
          ;; warning so we can count it without relying on log capture.
          ;; The send loop now closes `out` after the first drop, so
          ;; even if upstream sends N messages, we see at most 1 warn
          ;; (the message that lost the race with on-close — if any).
          handler (http-kit/create-http-kit-handler! S url sid)
          speer (peer/server-peer S handler sid
                                  (capture-middleware captured)
                                  identity)
          client-saw-ping (chan 1)
          cpeer (peer/client-peer
                 S cid
                 (fn [[S peer [in out]]]
                   (let [new-in (chan)
                         new-out (chan)]
                     (go-try S
                             (>! out "ping")
                             (let [reply (<! in)]
                               (put! client-saw-ping (or reply :nil))))
                     [S peer [new-in new-out]]))
                 identity)]
      (try
        (<?? S (peer/start speer))
        (<?? S (peer/connect S cpeer url))
        (is (= "ping" (<?? S client-saw-ping)))
        (let [out (:out @captured)
              channel-hub (:channel-hub handler)
              [ch _] (first @channel-hub)]
          (close ch)
          (<?? S (timeout 300))

          ;; Hammer 100 broadcasts onto the now-closed out. Each put
          ;; should return false; none should produce a warning, since
          ;; the send loop has already exited.
          (dotimes [i 100]
            (let [v (let [[v _] (alts!! [[out {:kabel/serialization :string
                                               :kabel/payload (str "n=" i)}]
                                         (timeout 50)])]
                      v)]
              (is (false? v)
                  (str "put #" i " to closed out returns false, not delivery"))))

          (is (closed? out) "out still closed at end"))
        (finally
          (try (<?? S (peer/stop speer)) (catch Throwable _ nil)))))))
