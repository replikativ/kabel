# kabel

A minimal, modern connection library modelling a bidirectional wire to
pass clojure values between endpoints. Peers in Clojure and
ClojureScript are symmetric and hence allow symmetric cross-platform
implementations. At the moment web-sockets are supported and transit
is used as serialization format.

## Usage

Add this to your project dependencies:
[![Clojars Project](http://clojars.org/io.replikativ/kabel/latest-version.svg)](http://clojars.org/io.replikativ/kabel)

From [examples/pingpong.clj](pingpong.clj):

~~~ clojure
(ns kabel.examples.pingpong
  (:require [kabel.platform :refer [create-http-kit-handler! start stop]]
            [kabel.peer :refer [client-peer server-peer connect]]
            [kabel.platform-log :refer [info warn error]]
            [kabel.middleware.log :refer [logger]]

            [full.async :refer [go-try go-loop-try> <? <??]]
            [clojure.core.async :refer [go-loop <! >! >!! timeout chan]]))

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

The client-side works the same in ClojureScript from the browser.

~~~


## TODO
- factor platform neutral logging
- implement node.js websocket server

## License

Copyright © 2015 Konrad Kühne, Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
