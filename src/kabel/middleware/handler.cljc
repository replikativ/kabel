(ns kabel.middleware.handler
  "Generic callback handler middleware."
  (:require [clojure.set :as set]
            #?(:clj [superv.async :refer [<? >? go-loop-try]]
               :cljs [superv.async :refer [superv-init]])
            ;; Both arms of the old reader conditional here were identical.
            [clojure.core.async :as async :refer [chan close!]]
            [replikativ.logging :as log])
  ;; No `(:require-macros [clojure.core.async :refer [go go-loop]])`: this
  ;; namespace uses neither macro -- only go-loop-try, from superv.async -- and
  ;; that unused require made the ns form INVALID under standard ClojureScript
  ;; tooling ("`:as` alias must be unique", because clojure.core.async is
  ;; rewritten to cljs.core.async and aliased twice). shadow-cljs tolerates it,
  ;; so it never surfaced in kabel's own builds; `cljs.main` refuses to compile.
  #?(:cljs (:require-macros [superv.async :refer [<? >? go-loop-try]])))

(def ^:private failed
  "Marks a callback that threw. An object no message can be identical to; a
  keyword could arrive as decoded data."
  #?(:clj (Object.) :cljs (js-obj)))

(defn handler
  "Applies given callback functions to messages on [in out] channels and passes
  through the return value of the callback. The callbacks have to return a
  go-channel.

  A callback that throws (e.g. a frame that does not decode) ends its direction:
  the error is logged and the channels downstream of it are closed, so the
  middleware above sees its input close and retires the connection. A loop that
  just stopped would leave the connection open while it reads or writes nothing."
  [cb-in cb-out [S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop-try S [i (<? S in)]
                 (if i
                   (let [r (try (<? S (cb-in i))
                                (catch #?(:clj Throwable :cljs :default) e
                                  (log/error :kabel/handler-in-failed {:error (str e)})
                                  failed))]
                     (if (identical? failed r)
                       (close! new-in)
                       (do
                         (when r
                           (>? S new-in r))
                         (recur (<? S in)))))
                   (close! new-in)))
    (go-loop-try S [o (<? S new-out)]
                 (if o
                   (let [r (try (<? S (cb-out o))
                                (catch #?(:clj Throwable :cljs :default) e
                                  (log/error :kabel/handler-out-failed {:error (str e)})
                                  failed))]
                     (if (identical? failed r)
                       (do (close! new-out)
                           (close! out))
                       (do
                         (when r
                           (>? S out r))
                         (recur (<? S new-out)))))
                   (close! out)))
    [S peer [new-in new-out]]))
