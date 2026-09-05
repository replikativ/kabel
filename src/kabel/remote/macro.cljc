(ns kabel.remote.macro
  "Macros for writing core.async remote invocations as ordinary functions."
  (:require #?(:clj [clojure.tools.analyzer.jvm :as ana.jvm])
            [clojure.set :as set]
            [clojure.walk :as walk]
            [kabel.remote]
            [replikativ.logging :as log]
            [superv.async :refer [S #?@(:clj [go-try])]]))

#?(:clj
   (defn free-variables
     "Find unresolved variables in a remote body so captures stay explicit."
     [env body]
     (let [free-variables (atom #{})]
       (if (:js-globals env)
         ;; ClojureScript belongs to the consumer's build, so its analyzer is
         ;; resolved only when that build expands this macro.
         (let [analyze (requiring-resolve 'cljs.analyzer/analyze)
               handlers (requiring-resolve 'cljs.analyzer/*cljs-warning-handlers*)]
           (with-bindings {handlers [(fn [warning-type _env extra]
                                       (when (= warning-type :undeclared-var)
                                         (swap! free-variables conj (:suffix extra))))]}
             (analyze env body)))
         (ana.jvm/analyze
          body
          (ana.jvm/empty-env)
          {:passes-opts
           {:validate/unresolvable-symbol-handler
            (fn [_ s _]
              (swap! free-variables conj s)
              ;; Keep the AST valid after recording the unresolved symbol.
              {:op :const :env {} :type :nil :literal? true
               :val nil :form nil :top-level true :o-tag nil :tag nil})}}))
       (disj @free-variables 'clojure))))

(defn go-remote
  "Mark a body for execution on a remote peer inside `defn-go-remote`."
  [remote explicit-args & body]
  (throw (ex-info "The go-remote macro must be used inside a defn-go-remote macro"
                  {:remote remote :explicit-args explicit-args :body body})))

(defmacro defn-go-remote
  "Define a function whose `go-remote` bodies are registered remote functions."
  {:style/indent [1 :form [1]]
   :arglists '([go-remote-name [params*] & body])}
  [go-remote-name args & body]
  {:pre [(symbol? go-remote-name) (vector? args)]}
  (let [macro-pos (select-keys (meta &form) [:line :column])
        _ (when (not= (:column macro-pos) 1)
            (log/warn ::defn-go-remote-must-be-top-level
                      {:message "defn-go-remote must be top-level for remote functions to be registered"
                       :macro-pos macro-pos}))
        remote-forms (atom [])
        new-body (walk/postwalk
                  (fn [form]
                    (if (and (seq? form) (= 'go-remote (first form)))
                      (let [[_ remote explicit-args & remote-body] form
                            _ (when-not (vector? explicit-args)
                                (throw (ex-info "go-remote requires explicit arg vector: (go-remote peer-id [arg1 arg2 ...] body...)"
                                                {:form form :got explicit-args})))
                            free-vars (free-variables &env `(do ~@remote-body))
                            declared-args (set explicit-args)
                            missing (set/difference free-vars declared-args)
                            extra (set/difference declared-args free-vars)
                            _ (when (seq missing)
                                (throw (ex-info (str "go-remote at "
                                                     (select-keys (meta form) [:line :column])
                                                     ": variables used in body but not in arg list: "
                                                     missing)
                                                {:missing missing
                                                 :declared declared-args
                                                 :used free-vars
                                                 :form form})))
                            _ (when (seq extra)
                                (log/debug ::go-remote-extra-args
                                           {:message (str "go-remote at "
                                                          (select-keys (meta form) [:line :column])
                                                          ": variables in arg list but not used in body")
                                            :extra extra
                                            :declared declared-args
                                            :used free-vars}))
                            remote-name (symbol (str *ns*)
                                                (str "go-remote-" (name go-remote-name) "-"
                                                     (count @remote-forms)))
                            arg-map (into {} (map (fn [s] [(keyword (str s)) s]) explicit-args))]
                        (swap! remote-forms conj [form explicit-args])
                        `(kabel.remote/invoke ~remote '~remote-name ~arg-map))
                      form))
                  `(do ~@body))
        remote-defs (mapv (fn [[remote-form explicit-args] i]
                            (let [[_ _ _ & remote-body] remote-form
                                  local-name (symbol (str "go-remote-" (name go-remote-name) "-" i))
                                  qualified-name (symbol (str *ns*) (str local-name))]
                              {:definition `(defn ~local-name [{:keys ~(vec explicit-args)}]
                                              (go-try S ~@remote-body))
                               :registration `(kabel.remote/register! '~qualified-name ~qualified-name)}))
                          @remote-forms
                          (range))]
    `(do
       ~@(map :definition remote-defs)
       ~@(map :registration remote-defs)
       (defn ~go-remote-name ~args
         ~new-body))))
