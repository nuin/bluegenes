(ns bluegenes.ws.auth
  (:require [compojure.core :refer [POST defroutes GET]]
            [imcljs.auth :as im-auth]
            [ring.util.http-response :as response]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [config.core :refer [env]]
            [clojure.string :as str]
            [clj-http.client :as http]
            [bluegenes.auth.intermine :as mine]
            [bluegenes.auth.transactions :as transactions]
            [bluegenes.utils :as utils :refer [env->mines]])
  (:import [java.net URI URLEncoder]
           [java.nio.charset StandardCharsets]))

(defn use-backend-service [service]
  (let [{default :bluegenes-default-service-root backend :bluegenes-backend-service-root} env]
    (if (and (not-empty backend) (= (:root service) default))
      (assoc service :root backend)
      service)))

(defn- single-value? [value limit]
  (and (string? value) (not (str/blank? value)) (<= (count value) limit)))

(defn- invalid-request []
  (ex-info "Authentication parameters do not match a configured mine" {:status 400}))

(defn configured-mine [{:keys [service mine-id]}]
  (let [mines (filter #(and (single-value? (:root %) 2048)
                            (single-value? (:namespace %) 128))
                      (env->mines (dissoc env :bluegenes-backend-service-root)))
        matches (filter #(and (= (:root service) (:root %))
                              (or (nil? mine-id) (= mine-id (:namespace %)))) mines)
        selected (when (= 1 (count matches)) (first matches))]
    (when-not (and selected (or (nil? (:token service)) (single-value? (:token service) 1024)))
      (throw (invalid-request)))
    {:mine-id (:namespace selected)
     :mine-path (str (:bluegenes-deploy-path env) "/" (:namespace selected))
     :public-root (:root selected)
     :service (use-backend-service (select-keys service [:root :token]))}))

(defn public-callback [provider]
  (try
    (let [value (:bluegenes-public-url env)
          uri (URI. value)
          path (str/replace (or (.getPath uri) "") #"/+$" "")
          deploy (str/replace (or (:bluegenes-deploy-path env) "") #"/+$" "")
          loopback? (#{"localhost" "127.0.0.1" "::1" "[::1]"} (some-> (.getHost uri) str/lower-case))]
      (when-not (and (.getHost uri) (nil? (.getUserInfo uri)) (nil? (.getRawQuery uri))
                     (nil? (.getFragment uri)) (= path deploy)
                     (<= -1 (.getPort uri) 65535)
                     (or (= "https" (.getScheme uri)) (and loopback? (= "http" (.getScheme uri)))))
        (throw (IllegalArgumentException.)))
      (str (str/replace value #"/+$" "") "/api/auth/oauth2callback?provider="
           (URLEncoder/encode provider StandardCharsets/UTF_8)))
    (catch Exception _
      (throw (ex-info "Set BLUEGENES_PUBLIC_URL to the HTTPS application URL, including its deployment path. HTTP is allowed only on loopback."
                      {:status 400 :kind :configuration})))))

(defn secure-cookie? []
  (str/starts-with? (or (:bluegenes-public-url env) "") "https://"))

(defn login
  "Keep the explicit older-server 302/basic-auth compatibility path."
  [service username password]
  (let [res (im-auth/login service username password)]
    (if (= (:status res) 302)
      (let [token (im-auth/basic-auth service username password)
            user (im-auth/who-am-i? (assoc service :token token) token)]
        [(assoc user :token token)])
      (let [{:keys [renamedLists user token]} (:output res)]
        [(assoc user :token token) renamedLists]))))

(defn- account-response [request register?]
  (let [{:keys [username password] :as params} (:params request)
        {:keys [service]} (configured-mine params)
        context (transactions/context! request)]
    (when-not (and (single-value? username 320) (single-value? password 4096))
      (throw (ex-info "Enter an email address and password" {:status 400})))
    (locking (:lock context)
      (transactions/invalidate! context)
      (http/with-middleware [#'http/wrap-request #'utils/wrap-timeout]
        (when register? (im-auth/register service username password))
        (let [[identity renamed] (login service username password)]
          (when-not (single-value? (:token identity) 4096)
            (throw (ex-info "The mine did not return credentials" {:status 502})))
          (-> (response/ok {:identity identity :renamedLists renamed})
              (assoc :session (with-meta (transactions/session request context) {:recreate true}))))))))

(defn handle-auth [request] (account-response request false))
(defn register [request] (account-response request true))

(defn logout [request]
  (let [{:keys [service mine-id]} (configured-mine (:params request))
        context (transactions/logout-context! request)]
    (locking (:lock context)
      (transactions/logout-context! request)
      (let [revoked? (try
                       (when (:token service)
                         (http/with-middleware [#'http/wrap-request #'utils/wrap-timeout]
                           (im-auth/logout service)))
                       true
                       (catch Exception error (= 401 (:status (ex-data error)))))
            canceled? (try (transactions/invalidate! context mine-id) true (catch Exception _ false))
            confirmed? (and canceled? revoked?)]
        (-> (response/ok (cond-> {:success true :revoked confirmed?}
                           (not confirmed?)
                           (assoc :warning "Signed out locally. Remote sign-out could not be confirmed.")))
            (assoc :session (with-meta (transactions/session request context) {:recreate true})))))))

(defn revoke [request]
  (let [{:keys [service]} (configured-mine (:params request))]
    (when-not (:token service) (throw (invalid-request)))
    (mine/request! service "revoke" {:token (:token service)})
    (response/ok {:success true})))

(defn oauth2authenticator [request]
  (let [{:keys [mine-id provider request-id redirect_uri] :as params} (:params request)
        binding (configured-mine params)]
    (when-not (and (single-value? mine-id 128) (single-value? provider 128)
                   (single-value? request-id 128))
      (throw (invalid-request)))
    (let [redirect (public-callback provider)]
      (when (and redirect_uri (not= redirect redirect_uri)) (throw (invalid-request)))
      (let [context (transactions/context! request)
            result (transactions/begin! context (assoc binding :provider provider
                                                       :request-id request-id :redirect-uri redirect))]
        (-> (response/ok result)
            (assoc :session (transactions/session request context)))))))

(defn oauth2callback [request]
  (let [context (transactions/existing-context request)
        attempt (transactions/claim! context (:params request))]
    (transactions/finish! context attempt (:params request))
    (let [session (transactions/callback-session request context attempt)]
      (cond-> (response/found (:mine-path attempt))
        session (assoc :session (with-meta session {:recreate true}))))))

(defn- safe-error [error]
  (let [{:keys [status kind]} (ex-data error)
        status (if (#{400 401 403 409 429 502 503} status) status 502)
        message (if (#{:state :configuration :unsupported :cleanup} kind)
                  (ex-message error)
                  (if (= 401 status) "The email or password was not accepted."
                      "Authentication could not complete. Please check the details and try again."))]
    {:status status :body {:error message}}))

(defn- wrap-auth [handler]
  (fn [request]
    (-> (try (handler request) (catch Exception error (safe-error error)))
        (assoc-in [:headers "Cache-Control"] "no-store"))))

(defroutes routes
  (POST "/logout" [] (wrap-auth logout))
  (POST "/login" [] (wrap-auth handle-auth))
  (POST "/register" [] (wrap-auth register))
  (POST "/revoke" [] (wrap-auth revoke))
  (POST "/oauth2authenticator" [] (wrap-auth oauth2authenticator))
  (wrap-params
   (wrap-keyword-params
    (GET "/oauth2callback" [] (wrap-auth oauth2callback)))))
