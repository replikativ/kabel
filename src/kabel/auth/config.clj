(ns kabel.auth.config
  "Configuration handling for kabel.auth.")

(def default-config
  {:dev-mode false
   :jwt {:access-token-expiry 3600      ;; 1 hour
         :refresh-token-expiry 604800}  ;; 7 days
   :password {:enabled true
              :min-length 8}
   :account-linking :reject})

(defn merge-config
  "Merge user config with defaults."
  [config]
  (-> default-config
      (merge config)
      (update :jwt #(merge (:jwt default-config) %))
      (update :password #(merge (:password default-config) %))))

(defn validate-config
  "Validate auth configuration. Returns {:valid true} or {:valid false :errors [...]}."
  [config]
  (let [errors (cond-> []
                 (nil? (:store config))
                 (conj "Auth store is required")

                 (and (not (:dev-mode config))
                      (nil? (get-in config [:jwt :secret]))
                      (nil? (get-in config [:jwt :private-key])))
                 (conj "JWT secret or private-key is required (unless dev-mode)"))]
    (if (empty? errors)
      {:valid true}
      {:valid false :errors errors})))
