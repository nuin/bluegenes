(ns bluegenes.auth-integration-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [config.core :as config]
            [ring.util.codec :as codec]
            [bluegenes.handler :as handler]
            [bluegenes.auth.transactions :as transactions]
            [bluegenes.auth-fixture :as fixture]
            [bluegenes.auth-http :refer [configuration browser request! begin!
                                         authorization-params with-mine]]))

(deftest uncorrelated-callback-never-exchanges
  (let [mine (fixture/start-mine)]
    (try
      (with-redefs [config/env (configuration mine)]
        (let [browser (browser)
              begun (begin! browser mine)
              callback (request! browser :get "/bluegenes/api/auth/oauth2callback"
                                 {:provider "TEST" :state "unrelated-state" :code "code-sentinel"})]
          (is (= 200 (:status begun)))
          (is (<= 400 (:status callback) 499))
          (is (zero? (:exchanges @(:counts mine))))
          (is (zero? (:transfers @(:counts mine))))))
      (finally ((:close mine))))))

(deftest initiation-binds-provider-state-and-public-callback
  (let [mine (fixture/start-mine)]
    (try
      (with-redefs [config/env (configuration mine)]
        (let [browser (browser)
              response (begin! browser mine)
              params (authorization-params response)]
          (is (= 200 (:status response)))
          (is (re-matches #"[A-Za-z0-9_-]{43}" (or (get params "state") "")))
          (is (= "https://ui.example.test/bluegenes/api/auth/oauth2callback?provider=TEST"
                 (get params "redirect_uri")))
          (is (= "no-store" (get-in response [:headers "Cache-Control"])))
          (is (some #(= "/mine/service/oauth2/transactions/prepare" (:path %)) @(:calls mine)))))
      (finally ((:close mine))))))

(deftest valid-flow-delivers-identity-to-its-mine-once
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")
          params {:provider "TEST" :state state :code "code-sentinel"}
          callback (request! browser :get "/bluegenes/api/auth/oauth2callback" params)
          page (request! browser :get "/bluegenes/test" {})]
      (is (= 302 (:status callback)))
      (is (= "/bluegenes/test" (get-in callback [:headers "Location"])))
      (is (str/includes? (:text page) "issued-token-1"))
      (is (not (str/includes? (:text page) "legacy-token-sentinel")))
      (is (= "no-store" (get-in page [:headers "Cache-Control"])))
      (is (not (str/includes? (:text (request! browser :get "/bluegenes/test" {}))
                              "issued-token-1")))
      (is (<= 400 (:status (request! browser :get "/bluegenes/api/auth/oauth2callback" params)) 499))
      (is (= 1 (:exchanges @(:counts mine))))
      (is (= 1 (:transfers @(:counts mine)))))))

(deftest callback-rejects-missing-repeated-and-wrong-provider-values
  (doseq [mutation [(fn [params] (dissoc params :state))
                    (fn [params] (assoc params :provider "OTHER"))
                    (fn [params] (str (codec/form-encode params) "&state=second-state"))
                    (fn [params] (dissoc params :code))]]
    (with-mine [mine]
      (let [browser (browser)
            state (get (authorization-params (begin! browser mine)) "state")
            response (request! browser :get "/bluegenes/api/auth/oauth2callback"
                               (mutation {:provider "TEST" :state state :code "code-sentinel"}))]
        (is (or (<= 400 (:status response) 499) (= 302 (:status response))))
        (is (zero? (:exchanges @(:counts mine))))
        (is (zero? (:transfers @(:counts mine))))))))

(deftest another-browser-cannot-use-the-state
  (with-mine [mine]
    (let [first-browser (browser)
          second-browser (browser)
          state (get (authorization-params (begin! first-browser mine)) "state")]
      (begin! second-browser mine)
      (is (<= 400 (:status (request! second-browser :get "/bluegenes/api/auth/oauth2callback"
                                     {:provider "TEST" :state state :code "code-sentinel"})) 499))
      (is (zero? (:exchanges @(:counts mine)))))))

(deftest initiation-rejects-namespace-and-callback-tampering
  (doseq [alter [(fn [params] (assoc params :mine-id "other"))
                 (fn [params] (assoc params :redirect_uri "https://attacker.example/callback"))]]
    (with-mine [mine]
      (let [browser (browser)]
        (request! browser :get "/bluegenes/test" {})
        (let [response (request! browser :post "/bluegenes/api/auth/oauth2authenticator"
                                 (alter {:service {:root (:root mine)} :mine-id "test"
                                         :provider "TEST" :request-id "request-1"}))]
          (is (= 400 (:status response)))
          (is (empty? @(:calls mine))))))))

(deftest public-url-is-required-and-upstream-errors-are-safe
  (with-mine [mine]
    (with-redefs [config/env (dissoc config/env :bluegenes-public-url)]
      (is (= 400 (:status (begin! (browser) mine))))
      (is (empty? @(:calls mine)))))
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")]
      (swap! (:controls mine) assoc :status {"exchange" 502 "oauth2callback" 502})
      (let [response (request! browser :get "/bluegenes/api/auth/oauth2callback"
                               {:provider "TEST" :state state :code "code-sentinel"})
            page (request! browser :get "/bluegenes/test" {})]
        (is (= 302 (:status response)))
        (is (not (str/includes? (:text page) "upstream-secret-sentinel")))
        (is (not (str/includes? (:text page) "code-sentinel")))))))

(deftest expired-state-is-canceled-without-exchange
  (with-mine [mine]
    (let [clock (atom 1000)]
      (with-redefs [transactions/store (transactions/new-store {:clock #(deref clock)})]
        (let [browser (browser)
              state (get (authorization-params (begin! browser mine)) "state")]
          (swap! clock + 300000)
          (is (= 400 (:status (request! browser :get "/bluegenes/api/auth/oauth2callback"
                                        {:provider "TEST" :state state :code "code-sentinel"}))))
          (is (zero? (:exchanges @(:counts mine))))
          (is (every? #(= :canceled (:phase @%)) (vals @(:entries mine)))))))))

(deftest capacity-counts-pending-attempts-and-reclaims-expired-ones
  (with-mine [mine]
    (let [clock (atom 1000)]
      (with-redefs [transactions/store (transactions/new-store {:clock #(deref clock) :capacity 1})]
        (let [first-browser (browser)
              second-browser (browser)]
          (is (= 200 (:status (begin! first-browser mine))))
          (let [params {:service {:root (:root mine)} :mine-id "test" :provider "TEST"
                        :request-id "second-request"}]
            (is (= 503 (:status (request! second-browser :post
                                          "/bluegenes/api/auth/oauth2authenticator" params))))
            (swap! clock + 300000)
            (is (= 200 (:status (request! second-browser :post
                                          "/bluegenes/api/auth/oauth2authenticator" params))))))))))

(deftest backend-root-mapping-keeps-the-public-mine-identity
  (with-mine [mine]
    (with-redefs [config/env (assoc config/env
                                    :bluegenes-default-service-root "https://public.example.test/mine"
                                    :bluegenes-backend-service-root (:root mine))]
      (let [browser (browser)
            begun (begin! browser mine)
            state (get (authorization-params begun) "state")]
        (is (= 200 (:status begun)))
        (request! browser :get "/bluegenes/api/auth/oauth2callback"
                  {:provider "TEST" :state state :code "code-sentinel"})
        (let [page (:text (request! browser :get "/bluegenes/test" {}))]
          (is (str/includes? page "public.example.test/mine"))
          (is (str/includes? page "issued-token-1")))))))

(deftest https-context-cookie-is-secure-and-rotated
  (with-mine [mine]
    (let [browser (browser)
          first (begin! browser mine)
          cookie @browser
          state (get (authorization-params first) "state")]
      (is (str/includes? (str/join (get-in first [:headers "Set-Cookie"])) "Secure"))
      (is (str/includes? (str/join (get-in first [:headers "Set-Cookie"])) "HttpOnly"))
      (is (str/includes? (str/join (get-in first [:headers "Set-Cookie"])) "SameSite=Lax"))
      (request! browser :get "/bluegenes/api/auth/oauth2callback"
                {:provider "TEST" :state state :code "code-sentinel"})
      (is (not= cookie @browser)))))

(deftest logout-reports-unconfirmed-cancellation-and-prevents-callback
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")]
      (swap! (:controls mine) assoc :status {"cancel" 503})
      (let [response (request! browser :post "/bluegenes/api/auth/logout"
                               {:service {:root (:root mine) :token "anonymous-token"}})
            body (json/parse-string (:text response) true)]
        (is (= 200 (:status response)))
        (is (false? (:revoked body)))
        (is (string? (:warning body))))
      (is (= 400 (:status (request! browser :get "/bluegenes/api/auth/oauth2callback"
                                    {:provider "TEST" :state state :code "code-sentinel"}))))
      (is (zero? (:exchanges @(:counts mine)))))))

(deftest portal-session-updates-preserve-a-pending-login
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")]
      (request! browser :get "/bluegenes/test/portal.do" {:externalids "Gene1"})
      (is (= 302 (:status (request! browser :get "/bluegenes/api/auth/oauth2callback"
                                    {:provider "TEST" :state state :code "code-sentinel"}))))
      (is (= 1 (:transfers @(:counts mine)))))))

(deftest anonymous-page-views-do-not-allocate-auth-contexts
  (with-mine [mine]
    (request! (browser) :get "/bluegenes/test" {})
    (is (empty? @(:contexts transactions/store)))))

(deftest denial-and-unsupported-servers-never-use-the-legacy-exchange
  (with-mine [mine]
    (let [browser (browser)
          state (get (authorization-params (begin! browser mine)) "state")]
      (request! browser :get "/bluegenes/api/auth/oauth2callback"
                {:provider "TEST" :state state :error "access_denied"})
      (is (zero? (:exchanges @(:counts mine))))
      (is (every? #(= :canceled (:phase @%)) (vals @(:entries mine))))))
  (with-mine [mine]
    (swap! (:controls mine) assoc :status {"prepare" 404})
    (let [response (begin! (browser) mine)]
      (is (= 400 (:status response)))
      (is (str/includes? (:text response) "version 36"))
      (is (not-any? #(str/ends-with? (:path %) "/oauth2authenticator") @(:calls mine))))))
