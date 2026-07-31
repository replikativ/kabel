(ns kabelbench.wire-bytes
  "End-to-end: bytes actually written to a socket, with and without
  permessage-deflate.

  Everything before this was a projection -- compressing byte arrays in
  isolation. This runs a real http-kit server and two real client sockets, one
  offering the extension and one not, sends the same CBOR-encoded konserve-sync
  traffic through both, and counts what goes on the wire.

  The client is hand-built rather than a websocket library on purpose. kabel's
  JVM client is Tyrus, whose extension support is a separate question from what
  a BROWSER does -- and the browser is the case this work exists for. Browsers
  offer permessage-deflate by default, so speaking the bytes directly is what
  actually models the target."
  (:require [boring.core :as boring]
            [org.httpkit.server :as hk])
  (:import [java.io ByteArrayOutputStream DataInputStream]
           [java.net Socket]
           [org.httpkit.server PerMessageDeflate]))

;; --------------------------------------------------------------------------
;; Traffic: what konserve-sync actually puts on the wire during a sync.
;; --------------------------------------------------------------------------

(defn- sync-messages [n]
  (vec (for [i (range n)]
         {:type :pubsub/publish
          :topic :datahike/store
          :sender #uuid "aaaaaaaa-0000-0000-0000-000000000001"
          :payload {:key (keyword (str "node-" i))
                    :value {:e (+ 100 i) :a :person/name :v (str "person-" i)
                            :tx (+ 536870912 i) :added true}
                    :operation :assoc}})))

;; --------------------------------------------------------------------------
;; A minimal websocket client that counts what it writes.
;; --------------------------------------------------------------------------

(defn- handshake! [port offer-deflate?]
  (let [sock (Socket. "localhost" (int port))
        out (.getOutputStream sock)
        in (DataInputStream. (.getInputStream sock))
        req (str "GET / HTTP/1.1\r\n"
                 "Host: localhost\r\n"
                 "Upgrade: websocket\r\n"
                 "Connection: Upgrade\r\n"
                 "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                 "Sec-WebSocket-Version: 13\r\n"
                 (when offer-deflate?
                   "Sec-WebSocket-Extensions: permessage-deflate\r\n")
                 "\r\n")]
    (.write out (.getBytes req "UTF-8"))
    (.flush out)
    (let [headers (loop [acc []]
                    (let [l (.readLine in)]
                      (if (or (nil? l) (= "" l)) acc (recur (conj acc l)))))]
      [sock in out headers])))

(defn- frame ^bytes [^bytes payload rsv1]
  (let [n (alength payload)
        mask (byte-array [9 8 7 6])
        buf (ByteArrayOutputStream.)]
    (.write buf (unchecked-byte (bit-or 0x80 (if rsv1 0x40 0) 0x02))) ; FIN|BINARY
    (cond
      (<= n 125) (.write buf (unchecked-byte (bit-or 0x80 n)))
      (<= n 0xFFFF) (do (.write buf (unchecked-byte (bit-or 0x80 126)))
                        (.write buf (unchecked-byte (bit-shift-right n 8)))
                        (.write buf (unchecked-byte (bit-and n 0xFF))))
      :else (throw (ex-info "payload too large for this bench" {:n n})))
    (.write buf mask)
    (dotimes [i n]
      (.write buf (unchecked-byte (bit-xor (aget payload i) (aget mask (mod i 4))))))
    (.toByteArray buf)))

(defn- run [port msgs offer-deflate?]
  (let [[sock in out headers] (handshake! port offer-deflate?)
        negotiated (boolean (some #(re-find #"(?i)permessage-deflate" %) headers))
        codec (when negotiated (PerMessageDeflate/negotiate "permessage-deflate" (* 4 1024 1024)))
        sent (atom 0)]
    (try
      (doseq [m msgs]
        (let [^bytes enc (boring/encode m)
              ^bytes payload (if codec (.compress codec enc (alength enc)) enc)
              ^bytes f (frame payload (boolean codec))]
          (.write out f)
          (swap! sent + (alength f))))
      (.flush out)
      {:negotiated negotiated
       :wire-bytes @sent
       :payload-bytes (reduce + (map #(alength ^bytes (boring/encode %)) msgs))}
      (finally
        (when codec (.end codec))
        (.close sock)))))

(defn -main [& _]
  (let [received (atom 0)
        server (hk/run-server
                (fn [req]
                  (hk/as-channel req {:on-receive (fn [_ msg]
                                                    (swap! received + (count msg)))}))
                {:port 0 :join? false})
        port (:local-port (meta server))]
    (try
      (let [msgs (sync-messages 500)
            plain (run port msgs false)
            deflated (run port msgs true)]
        (println)
        (println "500 konserve-sync messages, CBOR-encoded, over a real socket")
        (println "to a real http-kit server. Bytes counted as written by the client.")
        (println)
        (printf "%-34s %12s %12s%n" "" "no extension" "permessage-deflate")
        (println (apply str (repeat 60 \-)))
        (printf "%-34s %12s %12s%n" "extension negotiated"
                (:negotiated plain) (:negotiated deflated))
        (printf "%-34s %12d %12d%n" "CBOR payload (before framing)"
                (:payload-bytes plain) (:payload-bytes deflated))
        (printf "%-34s %12d %12d%n" "BYTES ON THE WIRE"
                (:wire-bytes plain) (:wire-bytes deflated))
        (printf "%-34s %12s %12s%n" "vs no extension" "--"
                (format "%+.1f%%" (* 100.0 (/ (- (:wire-bytes deflated) (:wire-bytes plain))
                                              (double (:wire-bytes plain))))))
        (println)
        (when-not (:negotiated deflated)
          (println "!! the extension did NOT negotiate -- the right column is meaningless"))
        (Thread/sleep 300)
        (printf "server decoded %d bytes of application payload%n" @received)
        (println "(equal across both runs means the server inflated correctly)")
        (println))
      (finally (server) (shutdown-agents)))))
