(ns bluegenes.metadata
  (:require [cljs.core.async :refer [<!]]
            [cljs-http.core :as http-core]
            [cljs-http.client :as http]
            [imcljs.internal.defaults :as defaults])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn model-response [{:keys [status body]}]
  (let [failure {:status (if (and (number? status) (<= 400 status 599)) status 502)
                 :body {:error "The mine did not return a usable data model."}}]
    (if (and (number? status) (<= 200 status 299))
      (try
        (let [model (:model (js->clj (js/JSON.parse body) :keywordize-keys true))]
          (if (and (map? (:classes model)) (seq (:classes model)))
            model
            failure))
        (catch js/Error _ failure))
      failure)))

(defn fetch-model [{:keys [root token]}]
  (let [request (-> http-core/request http/wrap-query-params http/wrap-url)
        response (request (-> {:url (defaults/url root "/model")
                               :request-method :get
                               :query-params {:format "json"}
                               :with-credentials? false
                               :timeout 30000}
                              (defaults/wrap-auth token)))]
    (go (model-response (<! response)))))
