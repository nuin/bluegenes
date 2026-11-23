(ns bluegenes.auth.intermine
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [ring.util.codec :as codec])
  (:import [java.net URI]))

(defn validate-authorization-url! [url state redirect]
  (let [valid? (try
                 (let [uri (URI. url)
                       params (codec/form-decode (.getRawQuery uri))
                       loopback? (#{"localhost" "127.0.0.1" "::1" "[::1]"} (.getHost uri))]
                   (and (<= (count url) 16384) (.getHost uri)
                        (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                        (or (= "https" (.getScheme uri))
                            (and loopback? (= "http" (.getScheme uri))))
                        (= state (get params "state")) (= redirect (get params "redirect_uri"))))
                 (catch Exception _ false))]
    (when-not valid?
      (throw (ex-info "The mine returned an invalid authorization URL" {:status 502})))))

(defn request!
  "Call the opt-in transaction API without forwarding upstream bodies or errors."
  [{:keys [root]} operation parameters]
  (let [response (try
                   (http/post (str (str/replace root #"/+$" "")
                                   "/service/oauth2/transactions/" operation)
                              {:form-params parameters :as :json :coerce :always
                               :accept :json :throw-exceptions false :follow-redirects false
                               :connection-timeout 3000 :socket-timeout 10000})
                   (catch Exception _
                     (throw (ex-info "Could not reach the configured mine"
                                     {:status 502 :kind :network}))))
        {:keys [status body]} response]
    (cond
      (and (= 200 status) (map? (:output body))) (:output body)
      (#{301 302 303 307 308 404 405} status)
      (throw (ex-info "OAuth requires a mine with service version 36 or newer"
                      {:status 400 :kind :unsupported}))
      :else (throw (ex-info "The mine could not complete the authentication request"
                            {:status 502 :kind :upstream})))))

(defn cancel!
  "Cancel an attempt and revoke its credential, including after handle expiry."
  [{:keys [service handle identity]}]
  (let [cancelled? (if handle
                     (try (request! service "cancel" {:handle handle}) true
                          (catch Exception _ false))
                     true)
        revoked? (if-let [token (:token identity)]
                   (try (request! service "revoke" {:token token}) true
                        (catch Exception _ false))
                   true)]
    (when-not (and cancelled? revoked?)
      (throw (ex-info "Local sign-out completed, but remote cancellation could not be confirmed"
                      {:status 503 :kind :cleanup})))))
