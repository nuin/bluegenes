(ns bluegenes.auth-race-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [bluegenes.auth.transactions :as transactions]
            [bluegenes.index :as index]
            [ring.middleware.session :as session]
            [bluegenes.auth-http :refer [browser request! begin! authorization-params with-mine]]))

(defn- callback! [browser state]
  (request! browser :get "/bluegenes/api/auth/oauth2callback"
            {:provider "TEST" :state state :code "fixture-code"}))

(defn- account! [browser mine operation]
  (request! browser :post (str "/bluegenes/api/auth/" operation)
            {:service {:root (:root mine) :token "anonymous-token"} :mine-id "test"
             :username "fixture@example.test" :password "fixture-password"}))

(defn- barrier [] {:entered (promise) :release (promise)})

(defn- await! [value] (deref value 5000 ::timeout))

(defn- page! [browser] (:text (request! browser :get "/bluegenes/test" {})))

(deftest concurrent-callbacks-exchange-only-once
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")
          gate (barrier)]
      (swap! (:controls mine) assoc :exchange gate)
      (let [first (future (callback! browser state))]
        (try
          (is (= true (await! (:entered gate))))
          (is (= 400 (:status (callback! browser state))))
          (finally (deliver (:release gate) true)))
        (is (= 302 (:status (await! first))))
        (is (= 1 (:exchanges @(:counts mine))))
        (is (= 1 (:transfers @(:counts mine))))))))

(deftest logout-and-new-login-cancel-an-exchanging-attempt
  (doseq [operation ["logout" "login" "register"]]
    (with-mine [mine]
      (let [browser (browser)
            state (get (authorization-params (begin! browser mine)) "state")
            gate (barrier)]
        (swap! (:controls mine) assoc :exchange gate)
        (let [pending (future (callback! browser state))]
          (try
            (is (= true (await! (:entered gate))))
            (is (= 200 (:status (account! browser mine operation))))
            (finally (deliver (:release gate) true)))
          (is (= 302 (:status (await! pending))))
          (is (zero? (:transfers @(:counts mine))))
          (is (not (str/includes? (page! browser) "issued-token-"))))))))

(deftest logout-waits-for-a-committing-transfer-and-revokes-it
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")
          gate (barrier)]
      (swap! (:controls mine) assoc :complete gate)
      (let [pending (future (callback! browser state))]
        (try
          (is (= true (await! (:entered gate))))
          (let [logout (future (account! browser mine "logout"))]
            (is (= ::waiting (deref logout 100 ::waiting)))
            (deliver (:release gate) true)
            (is (= 200 (:status (await! logout)))))
          (finally (deliver (:release gate) true)))
        (is (= 302 (:status (await! pending))))
        (is (= #{"issued-token-1"} (:revoked @(:counts mine))))
        (is (not (str/includes? (page! browser) "issued-token-1")))))))

(deftest logout-during-render-discards-the-identity-and-stale-session-write
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")
          gate (barrier)
          render index/index]
      (callback! browser state)
      (with-redefs [index/index (fn [init options]
                                  (when (:identity init)
                                    (deliver (:entered gate) true)
                                    (await! (:release gate)))
                                  (render init options))]
        (let [pending (future (page! browser))]
          (try
            (is (= true (await! (:entered gate))))
            (is (= 200 (:status (account! browser mine "logout"))))
            (finally (deliver (:release gate) true)))
          (is (not (str/includes? (await! pending) "issued-token-1")))
          (is (not (str/includes? (page! browser) "issued-token-1")))
          (is (= #{"issued-token-1"} (:revoked @(:counts mine)))))))))

(deftest expiry-during-exchange-cancels-without-transfer
  (with-mine [mine]
    (let [clock (atom 1000)]
      (with-redefs [transactions/store (transactions/new-store {:clock #(deref clock)})]
        (let [browser (browser)
              state (get (authorization-params (begin! browser mine)) "state")
              gate (barrier)]
          (swap! (:controls mine) assoc :exchange gate)
          (let [pending (future (callback! browser state))]
            (try
              (is (= true (await! (:entered gate))))
              (swap! clock + 300000)
              (finally (deliver (:release gate) true)))
            (is (= 302 (:status (await! pending))))
            (is (zero? (:transfers @(:counts mine))))
            (is (every? #(= :canceled (:phase @%)) (vals @(:entries mine))))))))))

(deftest expiry-during-render-revokes-undelivered-credentials
  (with-mine [mine]
    (let [clock (atom 1000)
          render index/index]
      (with-redefs [transactions/store (transactions/new-store {:clock #(deref clock)})]
        (let [browser (browser)
              state (get (authorization-params (begin! browser mine)) "state")]
          (callback! browser state)
          (with-redefs [index/index (fn [init options]
                                      (when (:identity init) (swap! clock + 300000))
                                      (render init options))]
            (is (not (str/includes? (page! browser) "issued-token-1"))))
          (is (= #{"issued-token-1"} (:revoked @(:counts mine)))))))))

(deftest rendering-failure-cancels-and-shows-a-safe-notice
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")
          render index/index]
      (callback! browser state)
      (with-redefs [index/index (fn [init options]
                                  (when (:identity init) (throw (ex-info "secret-render-error" {})))
                                  (render init options))]
        (let [page (page! browser)]
          (is (not (str/includes? page "issued-token-1")))
          (is (not (str/includes? page "secret-render-error")))))
      (is (= #{"issued-token-1"} (:revoked @(:counts mine)))))))

(deftest malformed-authorization-urls-are-rejected-and-canceled
  (doseq [alter [#(str % "&state=duplicate")
                 #(str/replace % "state=" "ignored=")
                 #(str/replace % "https:" "javascript:")
                 #(str % "#fragment")]]
    (with-mine [mine]
      (swap! (:controls mine) assoc :authorization-url alter)
      (let [response (begin! (browser) mine)]
        (is (= 502 (:status response)))
        (is (not (str/includes? (:text response) "provider.example.test")))
        (is (every? #(= :canceled (:phase @%)) (vals @(:entries mine))))))))

(deftest password-login-waits-for-an-earlier-account-logout
  (with-mine [mine]
    (let [browser (browser)
          gate (barrier)]
      (begin! browser mine)
      (swap! (:controls mine) assoc :logout gate)
      (let [logout (future (account! browser mine "logout"))]
        (try
          (is (= true (await! (:entered gate))))
          (let [login (future (account! browser mine "login"))]
            (is (= ::waiting (deref login 100 ::waiting)))
            (deliver (:release gate) true)
            (is (= 200 (:status (await! login)))))
          (finally (deliver (:release gate) true)))
        (is (= 200 (:status (await! logout))))))))

(deftest delayed-page-cannot-resurrect-a-rotated-cookie
  (with-mine [mine]
    (let [current (browser)
          state (get (authorization-params (begin! current mine)) "state")
          old (atom @current)
          gate (barrier)
          render index/index
          first? (atom true)]
      (with-redefs [index/index (fn [init options]
                                  (when (compare-and-set! first? true false)
                                    (deliver (:entered gate) true)
                                    (await! (:release gate)))
                                  (render init options))]
        (let [page (future (page! old))]
          (try
            (is (= true (await! (:entered gate))))
            (is (= 302 (:status (callback! current state))))
            (is (not= @old @current))
            (finally (deliver (:release gate) true)))
          (is (string? (await! page)))
          (is (not (str/includes? (page! old) "issued-token-1")))
          (is (str/includes? (page! current) "issued-token-1")))))))

(deftest logout-with-a-cookie-in-flight-during-rotation-cancels-the-same-attempt
  (with-mine [mine]
    (let [current (browser)
          state (get (authorization-params (begin! current mine)) "state")
          overlapping (atom @current)]
      (is (= 302 (:status (callback! current state))))
      (is (= 200 (:status (account! overlapping mine "logout"))))
      (is (= #{"issued-token-1"} (:revoked @(:counts mine))))
      (is (not (str/includes? (page! current) "issued-token-1"))))))

(deftest stale-callback-response-cannot-rotate-a-newer-session
  (with-mine [mine]
    (let [current (browser)
          state (get (authorization-params (begin! current mine)) "state")
          callback-browser (atom @current)
          gate (barrier)
          respond session/session-response]
      (with-redefs [session/session-response
                    (fn [response request options]
                      (when (= "/bluegenes/api/auth/oauth2callback" (:uri request))
                        (deliver (:entered gate) true)
                        (await! (:release gate)))
                      (respond response request options))]
        (let [callback (future (callback! callback-browser state))]
          (try
            (is (= true (await! (:entered gate))))
            (let [logout (future (account! current mine "logout"))]
              (deliver (:release gate) true)
              (is (= 200 (:status (await! logout)))))
            (finally (deliver (:release gate) true)))
          (is (= 302 (:status (await! callback))))
          (is (not (str/includes? (page! callback-browser) "issued-token-1")))
          (is (not (str/includes? (page! current) "issued-token-1"))))))))
