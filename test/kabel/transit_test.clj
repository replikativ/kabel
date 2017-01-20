(ns kabel.transit-test
  (:require [clojure.test :refer :all]
            [kabel.client :as cli]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [clojure.core.async :refer [timeout go go-loop <! >! <!! put! chan]]
            [kabel.middleware.transit :refer [transit]]))

(deftest transit-test
  (testing "Checking some simple transit encoding in both directions."
    (let [in (chan)
          out (chan)
          [_ _ [tin tout]] (transit [S nil [in out]])]
      (put? S tout [1 :transit "string"])
      (is (= (vec (<?? S out))
             [91 49 44 34 126 58 116 114 97 110 115 105
              116 34 44 34 115 116 114 105 110 103 34 93]))
      (put? S in (byte-array [91 49 44 34 126 58 116 114 97 110 115 105
                              116 34 44 34 115 116 114 105 110 103 34 93]))
      (is (= (<?? S tin) [1 :transit "string"])))))

