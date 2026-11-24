(ns bluegenes.events.auth
  (:require [re-frame.core :refer [reg-event-db reg-event-fx]]
            [bluegenes.effects :as fx]
            [bluegenes.route :as route]
            [imcljs.auth :as im-auth]
            [bluegenes.interceptors :refer [origin]]
            [bluegenes.auth.requests :as requests]))

(defn slim-service
  "Constrains a service map to only the keys needed by the backend API."
  [service]
  (select-keys service [:root :token]))

(defn renamedLists->message [renamedLists]
  [:messages/add
   {:markup [:div
             [:p "The following lists have been renamed due to their name conflicting with an existing list."]
             (into [:ul]
                   (for [[old-kw new-name] renamedLists]
                     [:li (name old-kw) " → " new-name]))]
    :timeout 15000
    :style "info"}])

(defn- account-request [{:keys [db auth-requests]} operation username password]
  (let [mine (:current-mine db)
        request (requests/create db mine)]
    {:db (-> (requests/attach db request)
             (update-in [:mines mine :auth] assoc :thinking? true :error? false))
     :persist (requests/persistence auth-requests request)
     ::fx/http {:uri (str "/api/auth/" operation) :method :post
                :on-success [::login-success request]
                :on-failure [::login-failure request]
                :on-unauthorised [::login-failure request]
                :transit-params {:username username :password password :mine-id (name mine)
                                 :service (slim-service (get-in db [:mines mine :service]))}}}))

(reg-event-fx ::login requests/interceptors
              (fn [cofx [_ username password]] (account-request cofx "login" username password)))

(reg-event-fx ::register requests/interceptors
              (fn [cofx [_ username password]] (account-request cofx "register" username password)))

(defn clear-private-assets [db mine]
  (cond-> (-> db
              (assoc-in [:assets :lists mine] [])
              (assoc-in [:assets :templates mine] {}))
    (= mine (:current-mine db)) (assoc-in [:lists :by-id] {})))

(reg-event-fx
 ::login-success requests/interceptors
 (fn [{:keys [db auth-requests local-store]}
      [_ {:keys [mine] :as request} {:keys [identity renamedLists]}]]
   (if-not (requests/current? db auth-requests request)
     (requests/discard db request (:token identity))
     (let [selected? (= mine (:current-mine db))]
       {:db (cond-> (-> db
                        (update-in [:mines mine :auth] assoc :thinking? false :identity identity
                                   :message nil :error? false)
                        (assoc-in [:mines mine :service :token] (:token identity))
                        (clear-private-assets mine))
              selected? route/force-controllers-rerun)
        :persist [:bluegenes/login (assoc local-store mine identity)]
        :dispatch-n (cond-> [[:assets/fetch-lists nil mine]
                             [:assets/fetch-templates nil mine]]
                      (seq renamedLists) (conj (renamedLists->message renamedLists))
                      selected? (conj [:bluegenes.events.boot/start-router]))
        :track-event ["login"]}))))

(reg-event-fx
 ::login-failure requests/interceptors
 (fn [{:keys [db auth-requests]} [_ {:keys [mine] :as request} res]]
   (when (requests/current? db auth-requests request)
     {:db (update-in db [:mines mine :auth] assoc :thinking? false :error? true
                     :message (or (get-in res [:body :error]) "Sign-in could not complete. Please try again."))})))

(reg-event-fx
 ::logout requests/interceptors
 (fn [{:keys [db auth-requests local-store]} _]
   (let [mine (:current-mine db)
         request (requests/create db mine)]
     {:db (-> (requests/attach db request)
              (update-in [:mines mine :auth] assoc :thinking? true :identity nil :error? false :message nil)
              (assoc-in [:mines mine :service :token] nil)
              (clear-private-assets mine))
      :persist-many [(requests/persistence auth-requests request)
                     [:bluegenes/login (dissoc local-store mine)]]
      ::fx/http {:uri "/api/auth/logout" :method :post
                 :on-success [::logout-success request]
                 :on-failure [::logout-success request]
                 :on-unauthorised [::logout-success request]
                 :transit-params {:mine-id (name mine)
                                  :service (slim-service (get-in db [:mines mine :service]))}}})))

(reg-event-fx
 ::logout-success requests/interceptors
 (fn [{:keys [db auth-requests]} [_ {:keys [mine] :as request} response]]
   (when (requests/current? db auth-requests request)
     {:db (assoc-in db [:mines mine :auth :thinking?] false)
      :dispatch-n (cond-> []
                    (= mine (:current-mine db)) (conj [:reboot])
                    (not (or (true? (:revoked response)) (= 401 (:status response))))
                    (conj [:messages/add
                           {:style "warning" :timeout 10000
                            :markup "Signed out locally. Remote sign-out could not be confirmed."}]))})))

(reg-event-fx
 ::request-reset-password
 [(origin)]
 (fn [{db :db origin :origin} [_ email]]
   (let [service (get-in db [:mines (:current-mine db) :service])
         redirectUrl (str origin (route/href ::route/resetpassword))]
     {:db (update-in db [:mines (:current-mine db) :auth] assoc
                     :thinking? true)
      :im-chan {:chan (im-auth/request-password-reset service email redirectUrl)
                :on-success [::request-reset-password-success]
                :on-failure [::request-reset-password-failure]}})))

(reg-event-db
 ::request-reset-password-success
 (fn [db [_]]
   (update-in db [:mines (:current-mine db) :auth] assoc
              :thinking? false
              :error? false
              :message nil
              :request-reset-success? true)))

(reg-event-db
 ::request-reset-password-failure
 (fn [db [_ res]]
   (update-in db [:mines (:current-mine db) :auth] assoc
              :thinking? false
              :error? true
              :message (if (empty? res)
                         "This feature is not supported in this version of Intermine"
                         (or (get-in res [:body :error])
                             "Failed to send recovery email"))
              :request-reset-success? false)))

(reg-event-fx
 ::reset-password
 (fn [{db :db} [_ new-password token]]
   (let [service (get-in db [:mines (:current-mine db) :service])]
     {:db (assoc-in db [:mines (:current-mine db) :auth :reset-password-in-progress?] true)
      :im-chan {:chan (im-auth/password-reset service new-password token)
                :on-success [::reset-password-success]
                :on-failure [::reset-password-failure]
                :on-unauthorised [::reset-password-failure]}})))

(reg-event-db
 ::reset-password-success
 (fn [db [_]]
   (update-in db [:mines (:current-mine db) :auth] assoc
              :reset-password-in-progress? false
              :reset-password-success? true
              :reset-password-error nil)))

(reg-event-db
 ::reset-password-failure
 (fn [db [_ {:keys [status] :as res}]]
   (update-in db [:mines (:current-mine db) :auth] assoc
              :reset-password-in-progress? false
              :reset-password-success? false
              :reset-password-error (if (= status 405)
                                      "This feature is not supported in this version of Intermine"
                                      (or (get-in res [:body :error])
                                          "Failed to reset password")))))

(reg-event-db
 ::clear-reset-password-page
 (fn [db]
   (update-in db [:mines (:current-mine db) :auth] dissoc
              :reset-password-in-progress?
              :reset-password-success?
              :reset-password-error)))

(reg-event-db
 ::clear-error
 (fn [db]
   (update-in db [:mines (:current-mine db) :auth] assoc
              :error? false
              :message nil
              :request-reset-success? false)))

(reg-event-fx
 ::oauth2 requests/interceptors
 (fn [{:keys [db auth-requests]} [_ provider]]
   (let [mine (:current-mine db)
         request (requests/create db mine)]
     {:db (-> (requests/attach db request)
              (update-in [:mines mine :auth] assoc :thinking? true :error? false))
      :persist (requests/persistence auth-requests request)
      ::fx/http {:uri "/api/auth/oauth2authenticator" :method :post
                 :on-success [::oauth2-success request]
                 :on-failure [::login-failure request]
                 :on-unauthorised [::login-failure request]
                 :transit-params {:service (slim-service (get-in db [:mines mine :service]))
                                  :mine-id (name mine) :provider provider :request-id (:id request)}}})))

(reg-event-fx
 ::oauth2-success requests/interceptors
 (fn [{:keys [db auth-requests]} [_ request {:keys [authorizationUrl]}]]
   (when (requests/current? db auth-requests request)
     {:external-redirect authorizationUrl})))
