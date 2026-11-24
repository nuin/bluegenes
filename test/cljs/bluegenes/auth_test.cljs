(ns bluegenes.auth-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame.test :refer-macros [run-test-sync]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [bluegenes.events]
            [bluegenes.events.auth :as auth]
            [bluegenes.effects :as fx]
            [bluegenes.test-utils :as utils]
            [bluegenes.config :as config]
            [imcljs.fetch :as fetch]
            [imcljs.auth :as im-auth]
            [cljs.core.async :refer [chan]]))

(use-fixtures :each utils/fixtures)

(def initial-db
  {:current-mine :a
   :mines {:a {:service {:root "https://a.example/mine" :token "anonymous-a"}}
           :b {:service {:root "https://b.example/mine" :token "anonymous-b"}}}
   :assets {:lists {:a [{:name "private-a"}] :b [{:name "private-b"}]}
            :templates {:a {:private {}}}}
   :lists {:by-id {1 {:name "private-a"}}}})

(defn- setup!
  ([] (setup! true))
  ([stub-assets?]
   (utils/stub-local-storage)
   (reset! rf-db/app-db initial-db)
   (let [requests (atom [])]
     (rf/reg-fx ::fx/http #(swap! requests conj %))
     (doseq [event (cond-> [:bluegenes.events.boot/start-router :reboot]
                     stub-assets? (into [:assets/fetch-lists :assets/fetch-templates]))]
       (rf/reg-event-fx event (fn [_ _] {})))
     requests)))

(defn- complete! [request kind body]
  (rf/dispatch (conj (get request kind) body)))

(deftest login-response-belongs-to-its-origin-mine
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/login "account-a" "password"])
     (let [request (last @requests)]
       (swap! rf-db/app-db assoc :current-mine :b)
       (complete! request :on-success {:identity {:token "login-a"}})
       (is (= "login-a" (get-in @rf-db/app-db [:mines :a :service :token])))
       (is (= "anonymous-b" (get-in @rf-db/app-db [:mines :b :service :token])))
       (is (= "login-a" (get-in @utils/stubbed-storage [":bluegenes/login" :a :token])))
       (is (= [{:name "private-b"}] (get-in @rf-db/app-db [:assets :lists :b])))))))

(deftest logout-clears-private-state-before-the-network-responds
  (run-test-sync
   (let [requests (setup!)]
     (swap! rf-db/app-db assoc-in [:mines :a :auth :identity] {:token "old-token"})
     (rf/dispatch [:save-login :a {:token "old-token"}])
     (rf/dispatch [::auth/logout])
     (is (nil? (get-in @rf-db/app-db [:mines :a :auth :identity])))
     (is (nil? (get-in @rf-db/app-db [:mines :a :service :token])))
     (is (empty? (get-in @rf-db/app-db [:assets :lists :a])))
     (is (empty? (get-in @rf-db/app-db [:assets :templates :a])))
     (is (nil? (get-in @utils/stubbed-storage [":bluegenes/login" :a])))
     (complete! (last @requests) :on-failure {:status 503})
     (is (some #(= "warning" (:style %)) (vals (:messages @rf-db/app-db)))))))

(deftest overlapping-logins-ignore-late-success-and-revoke-its-token
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/login "first" "password"])
     (let [first (last @requests)]
       (rf/dispatch [::auth/login "second" "password"])
       (complete! (last @requests) :on-success {:identity {:token "second-token"}})
       (complete! first :on-success {:identity {:token "first-token"}})
       (is (= "second-token" (get-in @rf-db/app-db [:mines :a :service :token])))
       (is (= "second-token" (get-in @utils/stubbed-storage [":bluegenes/login" :a :token])))
       (is (= "/api/auth/revoke" (:uri (last @requests))))
       (is (= "first-token" (get-in (last @requests) [:transit-params :service :token])))))))

(deftest late-failure-cannot-clear-a-newer-login
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/login "first" "password"])
     (let [first (last @requests)]
       (rf/dispatch [::auth/login "second" "password"])
       (complete! (last @requests) :on-success {:identity {:token "second-token"}})
       (complete! first :on-failure {:body {:error "old failure"}})
       (is (= "second-token" (get-in @rf-db/app-db [:mines :a :auth :identity :token])))
       (is (false? (get-in @rf-db/app-db [:mines :a :auth :error?])))))))

(deftest a-login-response-after-logout-cannot-restore-credentials
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/login "first" "password"])
     (let [first (last @requests)]
       (rf/dispatch [::auth/logout])
       (complete! first :on-success {:identity {:token "late-token"}})
       (is (nil? (get-in @rf-db/app-db [:mines :a :service :token])))
       (is (nil? (get-in @utils/stubbed-storage [":bluegenes/login" :a])))
       (is (= "/api/auth/revoke" (:uri (last @requests))))))))

(deftest logout-response-does-not-clear-a-newer-login
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/logout])
     (let [logout (last @requests)]
       (rf/dispatch [::auth/login "new" "password"])
       (complete! (last @requests) :on-success {:identity {:token "new-token"}})
       (complete! logout :on-success {:success true :revoked true})
       (is (= "new-token" (get-in @rf-db/app-db [:mines :a :service :token])))
       (is (= "new-token" (get-in @utils/stubbed-storage [":bluegenes/login" :a :token])))))))

(deftest oauth-follows-the-backends-complete-url
  (run-test-sync
   (let [requests (setup!)
         redirect (atom nil)
         url "https://provider.example/authorize?state=server-state&redirect_uri=callback"]
     (rf/reg-fx :external-redirect #(reset! redirect %))
     (rf/dispatch [::auth/oauth2 "TEST"])
     (let [request (last @requests)]
       (is (string? (get-in request [:transit-params :request-id])))
       (is (nil? (get-in request [:transit-params :redirect_uri])))
       (complete! request :on-success {:authorizationUrl url})
       (is (= url @redirect))))))

(deftest stale-oauth-html-is-discarded-before-validation
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/logout])
     (with-redefs [config/init-vars (delay {:identity {:token "stale-oauth"}
                                            :auth-request {:mine "a" :root "https://a.example/mine"
                                                           :id "old-request"}})
                   fetch/session (fn [& _] (chan 1))
                   im-auth/who-am-i? (fn [& _] (chan 1))]
       (rf/reg-fx :im-chan (fn [_]))
       (rf/dispatch [:authentication/init])
       (is (nil? (get-in @rf-db/app-db [:mines :a :auth :identity])))
       (is (= "/api/auth/revoke" (:uri (last @requests))))
       (is (= "stale-oauth" (get-in (last @requests) [:transit-params :service :token])))))))

(deftest token-validation-cannot-restore-a-logged-out-identity
  (run-test-sync
   (setup!)
   (let [pending (atom nil)]
     (rf/reg-fx :im-chan #(reset! pending %))
     (rf/dispatch [:save-login :a {:token "old-token"}])
     (with-redefs [config/init-vars (delay nil)
                   im-auth/who-am-i? (fn [& _] (chan 1))]
       (rf/dispatch [:authentication/init])
       (let [validation @pending]
         (rf/dispatch [::auth/logout])
         (complete! validation :on-success {})
         (is (nil? (get-in @rf-db/app-db [:mines :a :service :token])))
         (is (nil? (get-in @utils/stubbed-storage [":bluegenes/login" :a]))))))))

(deftest token-validation-retains-its-origin-mine
  (run-test-sync
   (setup!)
   (let [pending (atom nil)]
     (rf/reg-fx :im-chan #(reset! pending %))
     (rf/dispatch [:save-login :a {:token "account-a"}])
     (with-redefs [config/init-vars (delay nil)
                   im-auth/who-am-i? (fn [& _] (chan 1))]
       (rf/dispatch [:authentication/init])
       (swap! rf-db/app-db assoc :current-mine :b)
       (complete! @pending :on-success {})
       (is (= "account-a" (get-in @rf-db/app-db [:mines :a :service :token])))
       (is (= "anonymous-b" (get-in @rf-db/app-db [:mines :b :service :token])))))))

(deftest late-private-assets-cannot-repopulate-after-logout
  (run-test-sync
   (setup! false)
   (let [pending (atom [])]
     (rf/reg-fx :im-chan #(swap! pending conj %))
     (with-redefs [fetch/lists (fn [& _] (chan 1))
                   fetch/templates (fn [& _] (chan 1))]
       (rf/dispatch [:assets/fetch-lists])
       (rf/dispatch [:assets/fetch-templates])
       (rf/dispatch [::auth/logout])
       (complete! (first @pending) :on-success [{:name "late-private-list"}])
       (complete! (second @pending) :on-success {:private-template {}})
       (is (empty? (get-in @rf-db/app-db [:assets :lists :a])))
       (is (empty? (get-in @rf-db/app-db [:assets :templates :a])))))))

(deftest changed-service-roots-reject-late-credentials
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/login "account" "password"])
     (let [request (last @requests)]
       (swap! rf-db/app-db assoc-in [:mines :a :service :root] "https://replacement.example/mine")
       (complete! request :on-success {:identity {:token "old-root-token"}})
       (is (= "anonymous-a" (get-in @rf-db/app-db [:mines :a :service :token])))
       (is (= "https://a.example/mine" (get-in (last @requests) [:transit-params :service :root])))))))

(deftest another-tab-can-invalidate-an-outstanding-login
  (run-test-sync
   (let [requests (setup!)]
     (rf/dispatch [::auth/login "account" "password"])
     (let [request (last @requests)]
       (swap! utils/stubbed-storage assoc-in
              [":bluegenes/auth-requests" [:a "https://a.example/mine"]] "newer-tab-request")
       (complete! request :on-success {:identity {:token "late-token"}})
       (is (= "anonymous-a" (get-in @rf-db/app-db [:mines :a :service :token])))
       (is (nil? (get-in @utils/stubbed-storage [":bluegenes/login" :a])))
       (is (= "/api/auth/revoke" (:uri (last @requests))))))))

(deftest a-late-oauth-url-does-not-redirect-after-logout
  (run-test-sync
   (let [requests (setup!)
         redirect (atom nil)]
     (rf/reg-fx :external-redirect #(reset! redirect %))
     (rf/dispatch [::auth/oauth2 "TEST"])
     (let [request (last @requests)]
       (rf/dispatch [::auth/logout])
       (complete! request :on-success {:authorizationUrl "https://provider.example/late"})
       (is (nil? @redirect))))))
