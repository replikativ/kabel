(ns kabel.negotiate-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.negotiate :as n]))

(defn- caps [codecs & [{:keys [features max-frame binary?]}]]
  (n/capabilities {:codecs codecs
                   :features (or features #{})
                   :max-frame (or max-frame 1048576)
                   :binary? (if (nil? binary?) true binary?)}))

(deftest both-ends-agree-on-the-same-codec
  (testing "agreement does not depend on preference order"
    ;; The bug this guards: "first of MY preferences that appears in yours"
    ;; makes these two ends pick differently and neither notices. The property
    ;; is AGREEMENT, not that either side got its favourite.
    (let [a (caps [:cbor :fressian])
          b (caps [:fressian :cbor])]
      (is (= (:codec (n/agree a b)) (:codec (n/agree b a)))
          "the two ends chose different codecs — the wire is now broken")
      (is (= :cbor (:codec (n/agree a b)))
          "canonical rank is the frame id, and cbor (14) outranks fressian (13)")))

  (testing "and it does not depend on who dialled"
    (let [a (caps [:transit-json :cbor])
          b (caps [:cbor :transit-json])]
      (is (= (:codec (n/agree a b)) (:codec (n/agree b a)))))))

(deftest a-text-transport-vetoes-binary-codecs
  (testing "binary? is a fact about the transport, not a preference"
    ;; This is how a transport's constraint reaches the codec choice; it has no
    ;; other route.
    (let [c (n/agree (caps [:cbor :fressian :transit-json] {:binary? false})
                     (caps [:cbor :fressian :transit-json]))]
      (is (= :transit-json (:codec c))
          "a text transport must not select a binary codec")
      (is (false? (:binary? c)))))

  (testing "one end saying no is enough"
    (is (= :transit-json
           (:codec (n/agree (caps [:cbor :transit-json])
                            (caps [:cbor :transit-json] {:binary? false}))))))

  (testing "and a text transport with only binary codecs has no agreement"
    (is (nil? (n/agree (caps [:cbor] {:binary? false}) (caps [:cbor]))))))

(deftest no-common-codec-is-nil-not-a-guess
  (is (nil? (n/agree (caps [:cbor]) (caps [:fressian]))))
  (testing "an unknown codec name cannot win"
    ;; rank -1: a peer must not be able to select a codec we have never heard
    ;; of merely by naming it.
    (is (= :cbor (:codec (n/agree (caps [:cbor :made-up]) (caps [:cbor :made-up])))))))

(deftest bounds-are-minima-and-features-intersect
  (let [c (n/agree (caps [:cbor] {:features #{:deflate :overlay/v1} :max-frame 4096})
                   (caps [:cbor] {:features #{:deflate :other} :max-frame 999999}))]
    (is (= 4096 (:max-frame c))
        "neither end may talk the other into buffering more than it chose")
    (is (= #{:deflate} (:features c))
        "a feature is on only if BOTH ends have it")))

(deftest capabilities-has-no-default-codec-list
  ;; Deliberate: a middleware stack is a composed function and cannot be
  ;; introspected, so only the caller knows what it installed. A default here
  ;; would advertise what this namespace can NAME, and two peers would agree on
  ;; a codec neither had.
  (is (= [] (:kabel/codecs (n/capabilities {:max-frame 1024})))))
