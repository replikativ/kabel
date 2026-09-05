(ns kabel.remote.missionary
  "Optional Missionary remote-invocation macros and core.async bridges.

   Missionary is intentionally not a Kabel runtime dependency. Consumers of
   this optional namespace provide Missionary on their own classpath, while
   Kabel's test alias provides it only for exercising this backend."
  (:require [clojure.core.async :refer [chan close! put! take!]]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [kabel.remote]
            [kabel.remote.macro :as remote.macro]
            [missionary.core :as m]
            [replikativ.logging :as log]))

(defn task->chan
  "Run a Missionary task and expose its result or failure on a channel."
  [task]
  (let [ch (chan)]
    (task (fn [result]
            (when-not (nil? result)
              (put! ch result))
            (close! ch))
          (fn [error]
            (put! ch error)
            (close! ch)))
    ch))

(defn- take-task
  "Create a task that completes when a channel take is accepted."
  [ch]
  (doto (m/dfv) (->> (take! ch))))

(defn chan->task
  "Turn a channel into a task and rethrow an exception yielded by the channel."
  [ch]
  (m/sp
   (let [result (m/? (take-task ch))]
     (if (instance? #?(:clj Throwable :cljs js/Error) result)
       (throw result)
       result))))

(defn sp-remote
  "Mark a body for execution on a remote peer inside `defn-sp-remote`."
  [remote explicit-args & body]
  (throw (ex-info "The sp-remote macro must be used inside a defn-sp-remote macro"
                  {:remote remote :explicit-args explicit-args :body body})))

(defmacro defn-sp-remote
  "Define a function whose `sp-remote` bodies are registered remote tasks."
  {:style/indent [1 :form [1]]
   :arglists '([sp-remote-name [params*] & body])}
  [sp-remote-name args & body]
  {:pre [(symbol? sp-remote-name) (vector? args)]}
  (let [macro-pos (select-keys (meta &form) [:line :column])
        _ (when (not= (:column macro-pos) 1)
            (log/warn ::defn-sp-remote-must-be-top-level
                      {:message "defn-sp-remote must be top-level for remote functions to be registered"
                       :macro-pos macro-pos}))
        remote-forms (atom [])
        new-body (walk/postwalk
                  (fn [form]
                    (if (and (seq? form) (= 'sp-remote (first form)))
                      (let [[_ remote explicit-args & remote-body] form
                            _ (when-not (vector? explicit-args)
                                (throw (ex-info "sp-remote requires explicit arg vector: (sp-remote peer-id [arg1 arg2 ...] body...)"
                                                {:form form :got explicit-args})))
                            free-vars (remote.macro/free-variables &env `(do ~@remote-body))
                            declared-args (set explicit-args)
                            missing (set/difference free-vars declared-args)
                            extra (set/difference declared-args free-vars)
                            _ (when (seq missing)
                                (throw (ex-info (str "sp-remote at "
                                                     (select-keys (meta form) [:line :column])
                                                     ": variables used in body but not in arg list: "
                                                     missing)
                                                {:missing missing
                                                 :declared declared-args
                                                 :used free-vars
                                                 :form form})))
                            _ (when (seq extra)
                                (log/debug ::sp-remote-extra-args
                                           {:message (str "sp-remote at "
                                                          (select-keys (meta form) [:line :column])
                                                          ": variables in arg list but not used in body")
                                            :extra extra
                                            :declared declared-args
                                            :used free-vars}))
                            remote-name (symbol (str *ns*)
                                                (str "sp-remote-" (name sp-remote-name) "-"
                                                     (count @remote-forms)))
                            arg-map (into {} (map (fn [s] [(keyword (str s)) s]) explicit-args))]
                        (swap! remote-forms conj [form explicit-args])
                        `(kabel.remote.missionary/chan->task
                          (kabel.remote/invoke ~remote '~remote-name ~arg-map)))
                      form))
                  `(do ~@body))
        remote-defs (mapv (fn [[remote-form explicit-args] i]
                            (let [[_ _ _ & remote-body] remote-form
                                  local-name (symbol (str "sp-remote-" (name sp-remote-name) "-" i))
                                  qualified-name (symbol (str *ns*) (str local-name))]
                              {:definition `(defn ~local-name [{:keys ~(vec explicit-args)}]
                                              (kabel.remote.missionary/task->chan
                                               (m/sp ~@remote-body)))
                               :registration `(kabel.remote/register! '~qualified-name ~qualified-name)}))
                          @remote-forms
                          (range))]
    `(do
       ~@(map :definition remote-defs)
       ~@(map :registration remote-defs)
       (defn ~sp-remote-name ~args
         ~new-body))))
