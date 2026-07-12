(ns kabel.auth.integration-test
  "Integration tests for kabel-auth with full kabel peer communication.

   These tests simulate the flow that simmis will use:
   1. HTTP auth endpoints for login/register
   2. WebSocket connection with kabel
   3. Authentication via kabel/auth message
   4. Principal propagation to the application"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :refer [chan >!! <!! close! timeout alts!! go-loop]]
            [kabel.auth.http-kit :as auth-hk]
            [kabel.auth.websocket :as ws-auth]
            [kabel.auth.routes :as routes]
            [kabel.auth.store.memory :refer [memory-auth-store]]
            [kabel.auth.jwt :as jwt]
            [kabel.peer :as peer]
            [kabel.client :as client]
            [superv.async :refer [S <?? go-try <?]]
            [org.httpkit.server :as http-kit]))

(def test-secret "integration-test-secret-key")

(defn make-test-config []
  {:store (memory-auth-store)
   :dev-mode false
   :jwt {:secret test-secret
         :alg :HS256
         :issuer "test-app"
         :audience "test-api"
         :access-token-expiry 3600
         :refresh-token-expiry 86400}
   :password {:enabled true
              :min-length 8}})

;; Legacy test - keep for backwards compatibility

(deftest inbound-principal-is-injected
  (testing "Server receives :kabel/principal injected by authenticated handler"
    (let [sid #uuid "fd0278e4-081c-4925-abb9-ff4210be271b"
          cid #uuid "898dcf36-e07a-4338-92fd-f818d573444a"
          url "ws://localhost:47310"
          captured (chan)
          validate-request! (fn [_] {:sub "alice@example.org"})
          handler (auth-hk/create-authenticated-http-kit-handler! S url sid validate-request!)
          ;; server middleware that forwards inbound and captures messages
          server-mw (fn [[S peer [in out]]]
                      (let [new-in (chan)]
                        (go-try S
                                (loop [i (<? S in)]
                                  (when i
                                    (>!! captured i)
                                    (recur (<? S in)))))
                        [S peer [new-in out]]))
          speer (peer/server-peer S handler sid server-mw)
          _ (<?? S (peer/start speer))
          ;; connect a lightweight client
          [cin cout] (<?? S (client/client-connect! S url cid))]
      (try
        ;; send a simple message
        (>!! cout {:type :ping :value 1})
        ;; expect to capture the inbound at server with injected principal
        (let [[msg _] (alts!! [captured (timeout 2000)])]
          (is (some? msg) "server should receive a message")
          (is (= :ping (:type msg)))
          (is (= {:sub "alice@example.org"} (:kabel/principal msg))))
        (finally
          (close! cout)
          (close! cin))))))

;; New integration test: Full auth flow with WebSocket auth middleware

(deftest validate-middleware-integration
  (testing "Authentication via :kabel/auth message"
    (let [sid #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
          cid #uuid "11111111-2222-3333-4444-555555666666"
          url "ws://localhost:47311"
          captured (chan)
          config {:jwt {:secret test-secret :alg :HS256}}

          ;; Create a test token
          token (jwt/sign-hs256 test-secret
                                {:sub "test-user-123"
                                 :email "integration@example.com"
                                 :name "Integration User"
                                 :exp (+ (quot (System/currentTimeMillis) 1000) 3600)})

          ;; Server with auth middleware
          auth-mw (ws-auth/validate-middleware config)

          ;; Application middleware that captures authenticated messages
          app-mw (fn [[S peer [in out]]]
                   (let [new-in (chan)]
                     (go-try S
                             (loop [msg (<? S in)]
                               (when msg
                                 (>!! captured msg)
                                 (recur (<? S in)))))
                     [S peer [new-in out]]))

          ;; Compose middlewares: auth first, then app
          combined-mw (fn [ctx]
                        (-> ctx auth-mw app-mw))

          ;; Use basic http-kit handler (no HTTP auth)
          handler (auth-hk/create-authenticated-http-kit-handler!
                   S url sid (constantly nil))

          speer (peer/server-peer S handler sid combined-mw)
          _ (<?? S (peer/start speer))
          [cin cout] (<?? S (client/client-connect! S url cid))]

      (try
        ;; Step 1: Send auth message
        (>!! cout {:type :kabel/auth :token token})

        ;; Should receive auth-ok (from the middleware, sent to out)
        ;; Note: In real setup, this would be in a separate response channel

        ;; Step 2: Send application message
        (Thread/sleep 100) ;; Give auth time to process
        (>!! cout {:type :my-app-message :data "hello"})

        ;; Step 3: Check that message has principal attached
        (let [[msg _] (alts!! [captured (timeout 2000)])]
          (is (some? msg) "should receive application message")
          (is (= :my-app-message (:type msg)))
          (is (= "hello" (:data msg)))
          (is (= "integration@example.com" (get-in msg [:kabel/principal :email])))
          (is (= "Integration User" (get-in msg [:kabel/principal :name]))))

        (finally
          (close! cout)
          (close! cin))))))

;; Test: Dev mode allows any token

(deftest dev-mode-integration
  (testing "Dev mode skips token validation"
    (let [sid #uuid "b1b2c3d4-e5f6-7890-abcd-ef1234567891"
          cid #uuid "21111111-2222-3333-4444-555555666667"
          url "ws://localhost:47312"
          captured (chan)
          config {:dev-mode true
                  :dev-principal {:sub "dev-user"
                                  :email "dev@localhost"
                                  :name "Developer"}}

          auth-mw (ws-auth/validate-middleware config)
          app-mw (fn [[S peer [in out]]]
                   (let [new-in (chan)]
                     (go-try S
                             (loop [msg (<? S in)]
                               (when msg
                                 (>!! captured msg)
                                 (recur (<? S in)))))
                     [S peer [new-in out]]))
          combined-mw (fn [ctx] (-> ctx auth-mw app-mw))
          handler (auth-hk/create-authenticated-http-kit-handler!
                   S url sid (constantly nil))
          speer (peer/server-peer S handler sid combined-mw)
          _ (<?? S (peer/start speer))
          [cin cout] (<?? S (client/client-connect! S url cid))]

      (try
        ;; Auth with invalid token (should still work in dev mode)
        (>!! cout {:type :kabel/auth :token "not-a-real-token"})

        (Thread/sleep 100)
        (>!! cout {:type :dev-test :data 123})

        (let [[msg _] (alts!! [captured (timeout 2000)])]
          (is (some? msg))
          (is (= :dev-test (:type msg)))
          (is (= "dev@localhost" (get-in msg [:kabel/principal :email]))))

        (finally
          (close! cout)
          (close! cin))))))

;; Test: Full HTTP + WebSocket flow (simulates simmis usage)

(deftest full-http-websocket-flow
  (testing "Complete flow: HTTP login -> WebSocket auth -> Application message"
    (let [;; Configuration
          config (make-test-config)
          sid #uuid "c1b2c3d4-e5f6-7890-abcd-ef1234567892"
          cid #uuid "31111111-2222-3333-4444-555555666668"
          ws-url "ws://localhost:47313"
          captured (chan)

          ;; HTTP routes handler
          http-handler (routes/auth-handler config)

          ;; WebSocket auth middleware
          auth-mw (ws-auth/validate-middleware
                   {:jwt {:secret test-secret :alg :HS256}})

          app-mw (fn [[S peer [in out]]]
                   (let [new-in (chan)]
                     (go-try S
                             (loop [msg (<? S in)]
                               (when msg
                                 (>!! captured msg)
                                 (recur (<? S in)))))
                     [S peer [new-in out]]))
          combined-mw (fn [ctx] (-> ctx auth-mw app-mw))
          handler (auth-hk/create-authenticated-http-kit-handler!
                   S ws-url sid (constantly nil))
          speer (peer/server-peer S handler sid combined-mw)
          _ (<?? S (peer/start speer))]

      (try
        ;; Step 1: Register user via HTTP
        (let [register-response (http-handler {:request-method :post
                                               :uri "/register"
                                               :body {:email "full-flow@example.com"
                                                      :password "password123"
                                                      :name "Full Flow User"}
                                               :headers {}
                                               :remote-addr "127.0.0.1"})]
          (is (= 201 (:status register-response))))

        ;; Step 2: Login via HTTP to get tokens
        (let [login-response (http-handler {:request-method :post
                                            :uri "/login"
                                            :body {:email "full-flow@example.com"
                                                   :password "password123"}
                                            :headers {}
                                            :remote-addr "127.0.0.1"})
              access-token (get-in login-response [:body :access_token])]
          (is (= 200 (:status login-response)))
          (is (string? access-token))

          ;; Step 3: Connect WebSocket
          (let [[cin cout] (<?? S (client/client-connect! S ws-url cid))]
            (try
              ;; Step 4: Authenticate WebSocket with token from HTTP login
              (>!! cout {:type :kabel/auth :token access-token})

              (Thread/sleep 100)

              ;; Step 5: Send application message
              (>!! cout {:type :app-request :action "get-data"})

              ;; Step 6: Verify principal is attached
              (let [[msg _] (alts!! [captured (timeout 2000)])]
                (is (some? msg) "should receive app message")
                (is (= :app-request (:type msg)))
                (is (= "full-flow@example.com"
                       (get-in msg [:kabel/principal :email]))))

              (finally
                (close! cout)
                (close! cin)))))

        (catch Exception e
          (is false (str "Test failed with exception: " (.getMessage e))))))))

;; Test: Token refresh without reconnecting

(deftest token-refresh-without-reconnect
  (testing "Refresh token updates principal without reconnecting"
    (let [sid #uuid "d1b2c3d4-e5f6-7890-abcd-ef1234567893"
          cid #uuid "41111111-2222-3333-4444-555555666669"
          url "ws://localhost:47314"
          captured (chan)
          config {:jwt {:secret test-secret :alg :HS256}}

          ;; Two tokens with different names
          token1 (jwt/sign-hs256 test-secret
                                 {:sub "user-1"
                                  :email "refresh-test@example.com"
                                  :name "Before Refresh"
                                  :exp (+ (quot (System/currentTimeMillis) 1000) 3600)})
          token2 (jwt/sign-hs256 test-secret
                                 {:sub "user-1"
                                  :email "refresh-test@example.com"
                                  :name "After Refresh"
                                  :exp (+ (quot (System/currentTimeMillis) 1000) 3600)})

          auth-mw (ws-auth/validate-middleware config)
          app-mw (fn [[S peer [in out]]]
                   (let [new-in (chan)]
                     (go-try S
                             (loop [msg (<? S in)]
                               (when msg
                                 (>!! captured msg)
                                 (recur (<? S in)))))
                     [S peer [new-in out]]))
          combined-mw (fn [ctx] (-> ctx auth-mw app-mw))
          handler (auth-hk/create-authenticated-http-kit-handler!
                   S url sid (constantly nil))
          speer (peer/server-peer S handler sid combined-mw)
          _ (<?? S (peer/start speer))
          [cin cout] (<?? S (client/client-connect! S url cid))]

      (try
        ;; Initial auth
        (>!! cout {:type :kabel/auth :token token1})
        (Thread/sleep 100)

        ;; Send message, check principal
        (>!! cout {:type :check-1})
        (let [[msg _] (alts!! [captured (timeout 2000)])]
          (is (= "Before Refresh" (get-in msg [:kabel/principal :name]))))

        ;; Refresh token
        (>!! cout {:type :kabel/auth-refresh :token token2})
        (Thread/sleep 100)

        ;; Send message, check updated principal
        (>!! cout {:type :check-2})
        (let [[msg _] (alts!! [captured (timeout 2000)])]
          (is (= "After Refresh" (get-in msg [:kabel/principal :name]))))

        (finally
          (close! cout)
          (close! cin))))))