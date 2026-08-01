(ns kabel.middleware.handler
  "Generic callback handler middleware."
  (:require [clojure.set :as set]
            #?(:clj [superv.async :refer [<? >? go-loop-try]]
               :cljs [superv.async :refer [superv-init]])
            ;; Both arms of the old reader conditional here were identical.
            [clojure.core.async :as async :refer [chan close!]])
  ;; No `(:require-macros [clojure.core.async :refer [go go-loop]])`: this
  ;; namespace uses neither macro -- only go-loop-try, from superv.async -- and
  ;; that unused require made the ns form INVALID under standard ClojureScript
  ;; tooling ("`:as` alias must be unique", because clojure.core.async is
  ;; rewritten to cljs.core.async and aliased twice). shadow-cljs tolerates it,
  ;; so it never surfaced in kabel's own builds; `cljs.main` refuses to compile.
  #?(:cljs (:require-macros [superv.async :refer [<? >? go-loop-try]])))

(defn handler
  "Applies given callback functions to messages on [in out] channels and passes
  through the return value of the callback. The callbacks have to return a
  go-channel."
  [cb-in cb-out [S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop-try S [i (<? S in)]
                 (if i
                   (do
                     (when-let [i (<? S (cb-in i))]
                       (>? S new-in i))
                     (recur (<? S in)))
                   (close! new-in)))
    (go-loop-try S [o (<? S new-out)]
                 (if o
                   (do
                     (when-let [o (<? S (cb-out o))]
                       (>? S out o))
                     (recur (<? S new-out)))
                   (close! out)))
    [S peer [new-in new-out]]))
