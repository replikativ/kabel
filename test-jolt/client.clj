(require '[kabel.client :as client]
         '[kabel.middleware.cbor :as cbor]
         '[clojure.core.async :as a]
         '[superv.async :refer [S]])

(let [ready (a/<!! (client/client-connect! S (first *command-line-args*) "test-client"))]
  (when (instance? Throwable ready) (throw ready))
  (let [[_ _ [in out]] (cbor/cbor [S nil ready])]
    (try
      (assert (a/>!! out {"n" 7}))
      (let [[reply ch] (a/alts!! [in (a/timeout 10000)])]
        (assert (= ch in))
        (assert (= 8 (get reply "n")))
        (assert (= "127.0.0.1" (:kabel/host reply))))
      (finally (a/close! out)))))
(println "Verified fragmented CBOR reply")
(shutdown-agents)
