(ns bluegenes.auth.session-store
  (:require [ring.middleware.session.store :as session]
            [ring.middleware.session :as ring-session]
            [bluegenes.auth.transactions :as transactions])
  (:import [java.util UUID]))

(defn memory-store
  "Retired keys permit only cancellation of the generation that was rotated."
  []
  (let [entries (atom {})
        clock #(System/currentTimeMillis)
        prune! #(swap! entries (fn [items]
                                 (into {} (filter (fn [[_ entry]]
                                                    (< (clock) (:expires entry))) items))))
        write! (fn [key data]
                 (locking entries
                   (prune!)
                   (let [entry (get @entries key)]
                     (if (or (:retired? entry) (and key (nil? entry))
                             (:retired-auth-context data))
                       key
                       (let [key (or key (str (UUID/randomUUID)))]
                         (when (and (not (contains? @entries key)) (>= (count @entries) 20000))
                           (throw (ex-info "Session capacity reached" {:status 503})))
                         (swap! entries assoc key {:data data :expires (+ (clock) 86400000)})
                         key)))))]
    (reify session/SessionStore
      (read-session [_ key]
        (prune!)
        (:data (get @entries key)))
      (write-session [_ key data]
        (if-let [id (:auth-context data)]
          (when-let [context (get @(:contexts transactions/store) id)]
            (locking (:lock context)
              (if (= (:auth-generation data) (:generation @(:data context)))
                (write! key data)
                key)))
          (write! key data)))
      (delete-session [_ key]
        (locking entries
          (when-let [entry (get @entries key)]
            (if-let [id (get-in entry [:data :auth-context])]
              (swap! entries assoc key
                     {:retired? true :expires (+ (clock) 300000)
                      :data {:retired-auth-context id
                             :auth-generation (get-in entry [:data :auth-generation])}})
              (when-not (:retired? entry) (swap! entries dissoc key)))))
        nil))))

(defn wrap-session [handler options]
  (let [options (-> (merge {:store (memory-store) :cookie-name "ring-session"} options)
                    (update :cookie-attrs #(merge {:path "/" :http-only true} %)))]
    (fn [request]
      (let [request (ring-session/session-request request options)
            response (handler request)
            data (:session response)
            context (get @(:contexts transactions/store) (:auth-context data))
            respond #(ring-session/session-response % request options)]
        (if context
          (locking (:lock context)
            (respond (if (= (:auth-generation data) (:generation @(:data context)))
                       response (dissoc response :session))))
          (respond response))))))
