(ns kabel.examples.pingpong
  (:require [kabel.platform :refer [create-http-kit-handler! start stop]]
            [kabel.peer :refer [client-peer server-peer connect]]
            [kabel.platform-log :refer [info warn error]]
            [kabel.middleware.log :refer [logger]]

            [full.async :refer [go-try go-loop-try> <? <??]]
            [clojure.core.async :refer [go-loop <!>! >!! timeout chan]]))

;; track errors (this happens through full.async recursively through
;; all participating subsystems)
(def err-ch (chan))
(go-loop [e (<! err-ch)]
          (when e
            (warn "ERROR:" e)
            (recur (<! err-ch))))

(def handler (create-http-kit-handler! "ws://127.0.0.1:9090/" err-ch))

(defn pong-middleware [[peer [in out]]]
  (let [new-in (chan)]
    (go-loop [p (<! in)]
      (when p
        (info "received ping, sending pong")
        (>! out {:type :pong})
        (>! new-in p) ;; pass along (or not...)
        (recur (<! in))))
    [peer [new-in out]]))

(def log-atom (atom {}))

(def remote-peer (server-peer handler "REMOTE" err-ch
                              (comp pong-middleware
                                    (partial logger log-atom :server))))

(start remote-peer)
#_(stop remote-peer)



(defn ping [[peer [in out]]]
  (go-loop []
    (<! (timeout 5000))
    (>! out {:type :ping})
    (recur))
  [peer [in out]])

(def local-peer (client-peer "CLIENT" err-ch ping))

(connect local-peer "ws://127.0.0.1:9090/")

(comment
  ;; inspect server side message exchange through logging middleware
  @log-atom

  )
