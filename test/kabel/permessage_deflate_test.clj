(ns kabel.permessage-deflate-test
  "kabel's Tyrus client permessage-deflate against a real http-kit server.

  The point of these tests is that the two ends are INDEPENDENT
  implementations: `org.replikativ.kabel.PerMessageDeflateExtension` here, and
  http-kit's `org.httpkit.server.PerMessageDeflate` there. A single
  implementation talking to itself agrees with itself no matter what it does
  with the RFC; two agreeing is evidence about the wire format.

  Requires an http-kit with permessage-deflate (2.9.0-beta4 + PR #617). Until
  that is released these run against a locally installed build, and skip with
  an explicit message rather than passing vacuously if it is absent."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http-kit])
  (:import [org.replikativ.kabel PerMessageDeflateExtension
            MessageHandlerString MessageHandlerBinary]
           [javax.websocket ClientEndpointConfig ClientEndpointConfig$Builder
            Endpoint Session]
           [org.glassfish.tyrus.client ClientManager]
           [java.net URI]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private server-has-pmd?
  (some? (try (Class/forName "org.httpkit.server.PerMessageDeflate")
              (catch ClassNotFoundException _ nil))))

(defn- echo-server
  "An http-kit WebSocket echo server on an ephemeral port. Returns [stop! port]."
  []
  (let [stop (atom nil)
        port (atom nil)
        s    (http-kit/run-server
              (fn [req]
                (http-kit/as-channel
                 req
                 {:on-receive (fn [ch msg] (http-kit/send! ch msg))}))
              {:port 0 :legacy-return-value? false})]
    (reset! port (http-kit/server-port s))
    (reset! stop #(http-kit/server-stop! s))
    [@stop @port]))

(defn- connect!
  "Open a Tyrus client session to `url`, offering permessage-deflate when
  `compress?`. Returns [session inbox], inbox a queue of received strings."
  [url compress?]
  (let [inbox   (LinkedBlockingQueue.)
        builder (ClientEndpointConfig$Builder/create)
        _       (when compress?
                  (.extensions builder (PerMessageDeflateExtension/offer)))
        cfg     (.build builder)
        ;; MessageHandlerString / MessageHandlerBinary rather than a reify of
        ;; the generic MessageHandler$Whole: the JVM erases the type parameter
        ;; and Tyrus cannot work out what to dispatch, so a raw reify silently
        ;; never fires. kabel carries these two interfaces for exactly this.
        endpoint (proxy [Endpoint] []
                   (onOpen [^Session session _config]
                     (.addMessageHandler session
                                         (proxy [MessageHandlerString] []
                                           (onMessage [m] (.put inbox m))))
                     (.addMessageHandler session
                                         (proxy [MessageHandlerBinary] []
                                           (onMessage [m] (.put inbox m)))))
                   (onError [_session t] (.put inbox (str "ERROR " t))))
        session (.connectToServer (ClientManager/createClient)
                                  ^Endpoint endpoint
                                  ^ClientEndpointConfig cfg
                                  (URI. url))]
    [session inbox]))

(defn- take! [^LinkedBlockingQueue q]
  (or (.poll q 10 TimeUnit/SECONDS) :timeout))

(defn- round-trip
  "Send each of `msgs` and collect the echoes, over one connection."
  [port compress? msgs]
  (let [[^Session session inbox] (connect! (str "ws://localhost:" port) compress?)]
    (try
      (mapv (fn [m]
              (.sendText (.getBasicRemote session) m)
              (take! inbox))
            msgs)
      (finally (.close session)))))

(deftest negotiated-round-trip
  (if-not server-has-pmd?
    (println "SKIPPED negotiated-round-trip — http-kit on the classpath has no"
             "permessage-deflate (needs 2.9.0-beta4 + PR #617)")
    (testing "messages survive a compressed round-trip through an independent
              server implementation"
      (let [[stop! port] (echo-server)]
        (try
          (let [msgs ["hello" "" "a" (apply str (repeat 5000 \x))
                      "unicode: äöü ∀x∈ℝ 🎉"]]
            (is (= msgs (round-trip port true msgs))))
          (finally (stop!)))))))

(deftest context-takeover-actually-compresses
  (if-not server-has-pmd?
    (println "SKIPPED context-takeover-actually-compresses")
    (testing "many similar messages over one connection: the point of context
              takeover is that message N is compressed against 1..N-1, so a
              round-trip must still be correct at the 500th message"
      (let [[stop! port] (echo-server)]
        (try
          (let [msgs (mapv #(str "{\"op\":\"assert\",\"e\":" % ",\"a\":\"user/name\"}")
                           (range 500))
                got  (round-trip port true msgs)]
            (is (= msgs got) "all 500 echo back intact")
            (is (= (nth msgs 499) (nth got 499)) "including the last"))
          (finally (stop!)))))))

(deftest uncompressed-still-works
  (testing "a client that does NOT offer the extension is unaffected — this is
            the regression that matters, since kabel's existing peers do not
            offer it"
    (let [[stop! port] (echo-server)]
      (try
        (let [msgs ["plain" "text"]]
          (is (= msgs (round-trip port false msgs))))
        (finally (stop!))))))

(deftest fragmented-and-control-frames
  (if-not server-has-pmd?
    (println "SKIPPED fragmented-and-control-frames")
    (testing "REAL fragmentation, via partial sends.

              The previous version of this test sent a 2 MB string with
              `sendText(String)` and claimed it exercised fragmentation. It does
              not: Tyrus emits that as ONE frame regardless of size, so the test
              asserted a property it never touched — and the implementation
              underneath it was wrong in both directions. A fragmented
              compressed message is one deflate stream split across frames, and
              compressing/inflating each fragment independently fails on the
              RFC's own example.

              `sendText(part, false)` + `sendText(last, true)` is what actually
              produces continuation frames."
      (let [[stop! port] (echo-server)]
        (try
          (let [[^Session session inbox] (connect! (str "ws://localhost:" port) true)]
            (try
              (let [parts ["the quick brown fox " "jumps over " "the lazy dog"]
                    whole (apply str parts)
                    ^javax.websocket.RemoteEndpoint$Basic remote (.getBasicRemote session)]
                (.sendText remote (first parts) false)
                (.sendText remote (second parts) false)
                (.sendText remote (last parts) true)
                (is (= whole (take! inbox)) "three-fragment message reassembles"))

              (testing "and again, so the second message sees the compression
                        history the first left behind"
                (let [^javax.websocket.RemoteEndpoint$Basic remote (.getBasicRemote session)]
                  (.sendText remote "the quick brown fox " false)
                  (.sendText remote "jumps again" true)
                  (is (= "the quick brown fox jumps again" (take! inbox)))))

              (testing "a ping interleaved into a fragmented message does not
                        disturb it — control frames are never compressed"
                (let [^javax.websocket.RemoteEndpoint$Basic remote (.getBasicRemote session)]
                  (.sendText remote "before-ping " false)
                  (.sendPing (.getAsyncRemote session) (java.nio.ByteBuffer/allocate 0))
                  (.sendText remote "after-ping" true)
                  (is (= "before-ping after-ping" (take! inbox)))))

              (testing "a single large message still works"
                (let [big (apply str (repeat 200000 "abcdefghij"))]
                  (.sendText (.getBasicRemote session) big)
                  (is (= big (take! inbox)) "2 MB message round-trips")))
              (finally (.close session))))
          (finally (stop!)))))))

(deftest binary-round-trip
  (if-not server-has-pmd?
    (println "SKIPPED binary-round-trip")
    (testing "binary frames compress too — kabel's boring transport is binary,
              so a text-only implementation would be useless to it"
      (let [[stop! port] (echo-server)]
        (try
          (let [[^Session session inbox] (connect! (str "ws://localhost:" port) true)
                payload (byte-array (map #(unchecked-byte (mod % 251)) (range 100000)))]
            (try
              (.sendBinary (.getBasicRemote session) (java.nio.ByteBuffer/wrap payload))
              (let [echoed (take! inbox)]
                (is (not= :timeout echoed) "binary echo arrived")
                (when (instance? java.nio.ByteBuffer echoed)
                  (let [bs (byte-array (.remaining ^java.nio.ByteBuffer echoed))]
                    (.get ^java.nio.ByteBuffer echoed bs)
                    (is (= (seq payload) (seq bs)) "bytes identical"))))
              (finally (.close session))))
          (finally (stop!)))))))
