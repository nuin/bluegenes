(ns bluegenes.auth.requests
  (:require [re-frame.core :as rf]
            [bluegenes.effects :as fx]))

(def storage-key :bluegenes/auth-requests)

(def interceptors
  [(rf/inject-cofx :local-store storage-key)
   (rf/->interceptor :id ::requests
                     :before #(assoc-in % [:coeffects :auth-requests]
                                        (get-in % [:coeffects :local-store])))
   (rf/inject-cofx :local-store :bluegenes/state)
   (rf/->interceptor :id ::cached-state
                     :before #(assoc-in % [:coeffects :cached-state]
                                        (get-in % [:coeffects :local-store])))
   (rf/inject-cofx :local-store :bluegenes/login)])

(defn key-for [{:keys [mine root]}] [mine root])

(defn create [db mine]
  {:mine mine :root (get-in db [:mines mine :service :root]) :id (str (random-uuid))})

(defn attach [db {:keys [mine id]}]
  (assoc-in db [:mines mine :auth :request-id] id))

(defn persistence [persisted request]
  [storage-key (assoc persisted (key-for request) (:id request))])

(defn current? [db persisted {:keys [mine root id] :as request}]
  (and (some? id)
       (= root (get-in db [:mines mine :service :root]))
       (= id (get-in db [:mines mine :auth :request-id]))
       (= id (get persisted (key-for request)))))

(defn discard [db {:keys [mine root]} token]
  (when (and token (not= token (get-in db [:mines mine :service :token])))
    {::fx/http {:uri "/api/auth/revoke" :method :post
                :transit-params {:mine-id (name mine) :service {:root root :token token}}
                :on-success [::discarded]
                :on-unauthorised [::discard-failed]
                :on-failure [::discard-failed]}}))

(rf/reg-event-fx ::discarded
                 (fn [_ [_ response]]
                   (when-not (true? (:success response))
                     {:dispatch [::discard-failed]})))

(rf/reg-event-fx
 ::discard-failed
 (fn [_ _]
   {:dispatch [:messages/add
               {:style "warning" :timeout 10000
                :markup "A superseded sign-in was discarded locally. Remote credential cleanup could not be confirmed."}]}))
