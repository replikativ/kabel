(ns kabel.negotiate-test
  (:require [clojure.test :refer [deftest testing is #?(:cljs async)]]
            [kabel.negotiate :as n]
            [kabel.binary :refer [to-binary from-binary]]
            [kabel.binary.table :as table]
            [kabel.pubsub :as pubsub]
            #?(:clj [superv.async :refer [S <?? go-try <?]])
            #?(:cljs [superv.async :refer [S]])
            [clojure.core.async :as async :refer [chan put! close! timeout]])
  #?(:cljs (:require-macros [superv.async :refer [go-try <?]])))

;; =============================================================================
;; Agreement is a pure function, so most of this needs no channels at all
;; =============================================================================

(defn- hello [codecs & [{:keys [features max-frame binary?]}]]
  (n/make-hello {:codecs codecs
                 :features (or features #{})
                 :max-frame (or max-frame 1048576)
                 :binary? (if (nil? binary?) true binary?)}))

(deftest both-ends-agree-on-the-same-codec
  (testing "agreement does not depend on preference order"
    ;; The bug this guards: "first of MY preferences that appears in yours"
    ;; makes these two ends pick differently and neither notices. The property
    ;; is AGREEMENT, not that either side got its favourite.
    (let [a {:codecs [:cbor :fressian] :features #{} :max-frame 1048576 :binary? true}
          b {:codecs [:fressian :cbor] :features #{} :max-frame 1048576 :binary? true}
          a-picks (:codec (n/agree a (hello (:codecs b))))
          b-picks (:codec (n/agree b (hello (:codecs a))))]
      (is (= a-picks b-picks)
          "the two ends chose different codecs — the wire is now broken")
      (is (= :cbor a-picks)
          "canonical rank is the frame id, and cbor (14) outranks fressian (13)")))

  (testing "and it does not depend on who dialled"
    (let [a {:codecs [:transit-json :cbor] :features #{} :max-frame 1048576 :binary? true}
          b {:codecs [:cbor :transit-json] :features #{} :max-frame 1048576 :binary? true}]
      (is (= (:codec (n/agree a (hello (:codecs b))))
             (:codec (n/agree b (hello (:codecs a)))))))))

(deftest a-text-transport-vetoes-binary-codecs
  (testing "binary? is a fact about the transport, not a preference"
    ;; This is the whole reason negotiation is a prerequisite for SSE: the
    ;; transport's constraint has no other way to reach the codec layer.
    (let [opts {:codecs [:cbor :fressian :transit-json] :features #{}
                :max-frame 1048576 :binary? false}
          caps (n/agree opts (hello [:cbor :fressian :transit-json]))]
      (is (= :transit-json (:codec caps))
          "a text transport must not select a binary codec")
      (is (false? (:binary? caps)))))

  (testing "one end saying no is enough"
    (let [caps (n/agree {:codecs [:cbor :transit-json] :features #{}
                         :max-frame 1048576 :binary? true}
                        (hello [:cbor :transit-json] {:binary? false}))]
      (is (= :transit-json (:codec caps)))))

  (testing "and a text transport with only binary codecs has no agreement"
    (is (nil? (n/agree {:codecs [:cbor] :features #{} :max-frame 1048576
                        :binary? false}
                       (hello [:cbor]))))))

(deftest no-common-codec-is-nil-not-a-guess
  (is (nil? (n/agree {:codecs [:cbor] :features #{} :max-frame 1048576 :binary? true}
                     (hello [:fressian]))))
  (testing "an unknown codec name cannot win"
    ;; rank -1: a peer advertising a codec we have never heard of must not be
    ;; able to select it merely by naming it.
    (let [caps (n/agree {:codecs [:cbor :made-up] :features #{}
                         :max-frame 1048576 :binary? true}
                        (hello [:cbor :made-up]))]
      (is (= :cbor (:codec caps))))))

(deftest bounds-are-minima-and-features-intersect
  (let [caps (n/agree {:codecs [:cbor] :features #{:deflate :overlay/v1}
                       :max-frame 4096 :binary? true}
                      (hello [:cbor] {:features #{:deflate :something-else}
                                      :max-frame 999999}))]
    (is (= 4096 (:max-frame caps))
        "neither end may talk the other into buffering more than it chose")
    (is (= #{:deflate} (:features caps))
        "a feature is on only if BOTH ends have it")))

;; =============================================================================
;; The universal channel
;; =============================================================================

(defn- decode-sync
  "`from-binary` is 1-arity on the JVM and callback-based on ClojureScript. On
  node the callback fires synchronously, so both can be read the same way."
  [wire]
  #?(:clj (from-binary wire)
     :cljs (let [a (atom nil)] (from-binary wire #(reset! a %)) @a)))

(defn- wire-length [wire]
  #?(:clj (count wire) :cljs (.-length wire)))

(defn- wire-header-id
  "The frame's 4-byte big-endian header id."
  [wire]
  #?(:clj (.readInt (java.io.DataInputStream. (java.io.ByteArrayInputStream. wire)))
     :cljs (+ (* (aget wire 0) 0x1000000) (* (aget wire 1) 0x10000)
              (* (aget wire 2) 0x100) (aget wire 3))))

(deftest the-hello-rides-frame-id-2
  (testing "readable by every kabel peer ever built"
    ;; A hello cannot require the codec it is negotiating. to-binary's fallback
    ;; is pr-str, and every codec middleware passes unknown serializations
    ;; through untouched, so id 2 reaches the far side whatever it speaks.
    (let [h (n/make-hello (merge n/default-opts {:codecs [:cbor :transit-json]}))
          wire (to-binary h)]
      (is (= h (decode-sync wire)) "the hello must survive the wire unchanged")
      (is (= :pr-str (table/decoding-for (wire-header-id wire)))
          "must use the fallback frame, or an unupgraded peer cannot read it")
      (is (< (wire-length wire) 300)
          "a hello should be small; it is on every connect"))))

;; =============================================================================
;; The middleware
;; =============================================================================

(defn- run-negotiation
  "Wire two negotiate middlewares to each other and return both agreements."
  [S a-opts b-opts cb]
  (let [a->b (chan 10) b->a (chan 10)
        a-caps (atom ::pending) b-caps (atom ::pending)
        [_ _ [a-in _]] (n/negotiate (assoc a-opts :on-negotiated
                                           (fn [_ c] (reset! a-caps c)))
                                    [S nil [b->a a->b]])
        [_ _ [b-in _]] (n/negotiate (assoc b-opts :on-negotiated
                                           (fn [_ c] (reset! b-caps c)))
                                    [S nil [a->b b->a]])]
    ;; Drain, so the inband :kabel/negotiated messages do not block.
    (async/go-loop [] (when (async/<! a-in) (recur)))
    (async/go-loop [] (when (async/<! b-in) (recur)))
    (cb a-caps b-caps)))

#?(:clj
   (deftest middleware-exchanges-and-agrees
     (run-negotiation
      S
      {:codecs [:cbor :fressian] :timeout-ms 60000}
      {:codecs [:fressian :cbor] :timeout-ms 60000}
      (fn [a-caps b-caps]
        (<?? S (timeout 300))
        (is (= :cbor (:codec @a-caps)))
        (is (= :cbor (:codec @b-caps)))
        (is (= (:codec @a-caps) (:codec @b-caps))
            "both ends must reach the same conclusion")))))

#?(:clj
   (deftest a-silent-peer-is-legacy-not-broken
     (testing "no hello within the timeout means today's behaviour"
       ;; The compatibility path, and the reason this can be deployed without a
       ;; flag day: a peer built before negotiation existed still works.
       (let [in (chan 10) out (chan 10)
             caps (atom ::pending)
             [_ _ [new-in _]] (n/negotiate {:timeout-ms 150
                                            :on-negotiated (fn [_ c] (reset! caps c))}
                                           [S nil [in out]])]
         (async/go-loop [] (when (async/<! new-in) (recur)))
         ;; It still announces itself -- silence from the peer is not a reason
         ;; to be silent ourselves.
         (is (n/hello? (<?? S out)) "we must announce regardless")
         (<?? S (timeout 400))
         (is (nil? @caps) "a silent peer must yield nil, not a hang")))))

#?(:clj
   (deftest ordinary-traffic-passes-through
     (let [in (chan 10) out (chan 10)
           [_ _ [new-in new-out]] (n/negotiate {:timeout-ms 60000} [S nil [in out]])]
       (is (n/hello? (<?? S out)))
       (put! in {:type :some/message :payload 42})
       (is (= {:type :some/message :payload 42} (<?? S new-in))
           "non-hello traffic must be untouched")
       (put! new-out {:type :outbound})
       (is (= {:type :outbound} (<?? S out))))))

;; =============================================================================
;; The agreement is parametric: it flows THROUGH the stack
;; =============================================================================

#?(:clj
   (deftest the-agreement-reaches-middleware-that-never-heard-of-it
     (testing "pub/sub passes :kabel/negotiated through untouched"
       ;; This is what makes the handshake parametric rather than a side
       ;; channel. A middleware that cares keeps per-connection state, updates
       ;; it as the message passes, and lets its out-branch consult it. A
       ;; middleware that does not care needs no changes at all -- pub/sub
       ;; dispatches unknown types to :unrelated and passes them on, which is
       ;; why a capability added later cannot break it.
       (let [in (chan 10) out (chan 10)
             ;; pub/sub keeps per-connection state on the peer atom.
             peer (atom {})
             seen (atom [])
             ;; A stand-in for any middleware that wants to be parameterised:
             ;; it watches for the agreement and records it.
             watcher (fn [[S peer [in out]]]
                       (let [new-in (chan)]
                         (async/go-loop []
                           (when-let [m (async/<! in)]
                             (when (= :kabel/negotiated (:type m))
                               (swap! seen conj (:caps m)))
                             (async/>! new-in m)
                             (recur)))
                         [S peer [new-in out]]))
             [_ _ [top-in _]] (-> [S peer [in out]]
                                  (#(n/negotiate {:timeout-ms 60000} %))
                                  ((pubsub/make-pubsub-peer-middleware {}))
                                  watcher)]
         (async/go-loop [] (when (async/<! top-in) (recur)))
         (is (n/hello? (<?? S out)))
         ;; The peer answers.
         (put! in (n/make-hello (merge n/default-opts {:codecs [:cbor]
                                                       :features #{:deflate}})))
         (<?? S (timeout 300))
         (is (= 1 (count @seen))
             "the agreement never reached the top of the stack")
         (is (= :cbor (:codec (first @seen)))
             "a middleware can read the agreed codec without asking anyone")))))
