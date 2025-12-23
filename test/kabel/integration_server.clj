(ns kabel.integration-server
  "Test server for cross-platform integration tests."
  (:require [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [superv.async :refer [<?? go-loop-try <? >? S]]
            [clojure.core.async :refer [chan]]
            [kabel.middleware.fressian :refer [fressian]]))

(def url "ws://localhost:47295")
(def server-id #uuid "05a06e85-e7ca-4213-9fe5-04ae511e50a0")

(defn pong-middleware
  "Simple echo middleware that mirrors messages back."
  [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop-try S [i (<? S in)]
                 (when i
                   (>? S out i)
                   (recur (<? S in))))
    [S peer [new-in new-out]]))

(defn -main [& args]
  (println "Starting integration test server on" url)
  (let [handler (http-kit/create-http-kit-handler! S url server-id)
        server (peer/server-peer S handler server-id pong-middleware fressian)]
    (<?? S (peer/start server))
    (println "Server started successfully")
    ;; Keep server running
    @(promise)))
