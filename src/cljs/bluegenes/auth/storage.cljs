(ns bluegenes.auth.storage
  (:require [cljs-http.client :as http]
            [clojure.string :as str]))

(defn bound-login [root identity]
  (assoc identity :service-root root))

(defn login-for-root [logins mine root]
  (let [identity (get logins mine)]
    (when (and (string? root) (= root (:service-root identity)))
      identity)))

(defn public-cache [state]
  (update state :assets dissoc :lists :templates))

(defn validate-token [service token]
  (http/get (str (str/replace (:root service) #"/+$" "") "/service/user")
            {:query-params {:format "json"}
             :headers {"Authorization" (str "Token " token)}
             :with-credentials? false}))
