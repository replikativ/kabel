(ns kabel.auth.store-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.auth.store.protocol :as p]
            [kabel.auth.store.memory :refer [memory-auth-store]])
  (:import [java.util UUID]))

;; User tests

(deftest create-user-test
  (testing "Creating a user with email"
    (let [store (memory-auth-store)
          user (p/create-user! store {:party/email "alice@example.com"
                                      :party/display-name "Alice"})]
      (is (uuid? (:party/id user)))
      (is (= "alice@example.com" (:party/email user)))
      (is (= "Alice" (:party/display-name user)))
      (is (inst? (:party/created user)))))

  (testing "Email is required"
    (let [store (memory-auth-store)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Email is required"
                            (p/create-user! store {:party/display-name "No Email"})))))

  (testing "Duplicate email throws"
    (let [store (memory-auth-store)]
      (p/create-user! store {:party/email "bob@example.com"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Email already exists"
                            (p/create-user! store {:party/email "bob@example.com"}))))))

(deftest find-user-test
  (testing "Find user by email"
    (let [store (memory-auth-store)
          created (p/create-user! store {:party/email "carol@example.com"})]
      (is (= created (p/find-user-by-email store "carol@example.com")))
      (is (nil? (p/find-user-by-email store "nonexistent@example.com")))))

  (testing "Find user by ID"
    (let [store (memory-auth-store)
          created (p/create-user! store {:party/email "dave@example.com"})
          user-id (:party/id created)]
      (is (= created (p/find-user-by-id store user-id)))
      (is (nil? (p/find-user-by-id store (UUID/randomUUID)))))))

(deftest update-user-test
  (testing "Update user name"
    (let [store (memory-auth-store)
          created (p/create-user! store {:party/email "eve@example.com" :party/display-name "Eve"})
          updated (p/update-user! store (:party/id created) {:party/display-name "Eve Updated"})]
      (is (= "Eve Updated" (:party/display-name updated)))
      (is (= "eve@example.com" (:party/email updated)))))

  (testing "Update non-existent user throws"
    (let [store (memory-auth-store)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Party not found"
                            (p/update-user! store (UUID/randomUUID) {:party/display-name "Ghost"}))))))

;; Session tests

(deftest create-session-test
  (testing "Creating a session"
    (let [store (memory-auth-store)
          user (p/create-user! store {:party/email "frank@example.com"})
          token-hash (p/hash-token "refresh-token-123")
          expires (java.util.Date. (+ (System/currentTimeMillis) 3600000)) ;; 1 hour
          session (p/create-session! store {:session/party-id (:party/id user)
                                            :session/refresh-token-hash token-hash
                                            :session/expires expires})]
      (is (uuid? (:session/id session)))
      (is (= (:party/id user) (:session/party-id session)))
      (is (inst? (:session/created session))))))

(deftest find-session-test
  (testing "Find session by token hash"
    (let [store (memory-auth-store)
          user (p/create-user! store {:party/email "grace@example.com"})
          token-hash (p/hash-token "my-refresh-token")
          expires (java.util.Date. (+ (System/currentTimeMillis) 3600000))
          created (p/create-session! store {:session/party-id (:party/id user)
                                            :session/refresh-token-hash token-hash
                                            :session/expires expires})]
      (is (= created (p/find-session-by-token-hash store token-hash)))
      (is (nil? (p/find-session-by-token-hash store "nonexistent-hash")))))

  (testing "Expired session returns nil"
    (let [store (memory-auth-store)
          user (p/create-user! store {:party/email "henry@example.com"})
          token-hash (p/hash-token "expired-token")
          expires (java.util.Date. (- (System/currentTimeMillis) 1000)) ;; expired
          _ (p/create-session! store {:session/party-id (:party/id user)
                                      :session/refresh-token-hash token-hash
                                      :session/expires expires})]
      (is (nil? (p/find-session-by-token-hash store token-hash))))))

(deftest delete-session-test
  (testing "Delete session by ID"
    (let [store (memory-auth-store)
          user (p/create-user! store {:party/email "iris@example.com"})
          token-hash (p/hash-token "delete-me-token")
          expires (java.util.Date. (+ (System/currentTimeMillis) 3600000))
          session (p/create-session! store {:session/party-id (:party/id user)
                                            :session/refresh-token-hash token-hash
                                            :session/expires expires})]
      (is (true? (p/delete-session! store (:session/id session))))
      (is (nil? (p/find-session-by-token-hash store token-hash)))
      (is (false? (p/delete-session! store (:session/id session)))))))

(deftest delete-user-sessions-test
  (testing "Delete all sessions for a user"
    (let [store (memory-auth-store)
          user (p/create-user! store {:party/email "jake@example.com"})
          expires (java.util.Date. (+ (System/currentTimeMillis) 3600000))
          _ (p/create-session! store {:session/party-id (:party/id user)
                                      :session/refresh-token-hash (p/hash-token "token-1")
                                      :session/expires expires})
          _ (p/create-session! store {:session/party-id (:party/id user)
                                      :session/refresh-token-hash (p/hash-token "token-2")
                                      :session/expires expires})
          _ (p/create-session! store {:session/party-id (:party/id user)
                                      :session/refresh-token-hash (p/hash-token "token-3")
                                      :session/expires expires})]
      (is (= 3 (p/delete-user-sessions! store (:party/id user))))
      (is (nil? (p/find-session-by-token-hash store (p/hash-token "token-1"))))
      (is (nil? (p/find-session-by-token-hash store (p/hash-token "token-2"))))
      (is (nil? (p/find-session-by-token-hash store (p/hash-token "token-3")))))))

(deftest delete-expired-sessions-test
  (testing "Clean up expired sessions"
    (let [store (memory-auth-store)
          user (p/create-user! store {:party/email "kate@example.com"})
          future-time (java.util.Date. (+ (System/currentTimeMillis) 3600000))
          past-time (java.util.Date. (- (System/currentTimeMillis) 1000))
          _ (p/create-session! store {:session/party-id (:party/id user)
                                      :session/refresh-token-hash (p/hash-token "valid-token")
                                      :session/expires future-time})
          _ (p/create-session! store {:session/party-id (:party/id user)
                                      :session/refresh-token-hash (p/hash-token "expired-1")
                                      :session/expires past-time})
          _ (p/create-session! store {:session/party-id (:party/id user)
                                      :session/refresh-token-hash (p/hash-token "expired-2")
                                      :session/expires past-time})]
      (is (= 2 (p/delete-expired-sessions! store)))
      ;; Valid session still exists
      (is (some? (p/find-session-by-token-hash store (p/hash-token "valid-token")))))))

;; Hash token test

(deftest hash-token-test
  (testing "Token hashing is consistent"
    (is (= (p/hash-token "test-token")
           (p/hash-token "test-token"))))

  (testing "Different tokens have different hashes"
    (is (not= (p/hash-token "token-a")
              (p/hash-token "token-b")))))
