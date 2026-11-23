(ns bluegenes.auth-http
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [config.core :as config]
            [ring.util.codec :as codec]
            [bluegenes.handler :as handler]
            [bluegenes.auth.transactions :as transactions]
            [bluegenes.auth-fixture :as fixture])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.nio.charset StandardCharsets]))

(defn configuration [mine]
  {:bluegenes-deploy-path "/bluegenes"
   :bluegenes-public-url "https://ui.example.test/bluegenes"
   :bluegenes-default-service-root (:root mine)
   :bluegenes-default-mine-name "Fixture"
   :bluegenes-default-namespace "test"
   :bluegenes-additional-mines []})

(defn browser [] (atom nil))

(defn request! [browser method path params]
  (let [body (when (= :post method) (json/generate-string params))
        response (handler/handler
                  {:request-method method :uri path :scheme :https
                   :server-name "ui.example.test" :server-port 443
                   :remote-addr "127.0.0.1"
                   :query-string (when (= :get method)
                                   (if (string? params) params (codec/form-encode params)))
                   :headers (cond-> {"accept" "application/json"}
                              body (assoc "content-type" "application/json")
                              @browser (assoc "cookie" @browser))
                   :body (when body (ByteArrayInputStream.
                                     (.getBytes body StandardCharsets/UTF_8)))})
        cookies (get-in response [:headers "Set-Cookie"])
        text (let [body (:body response)]
               (cond
                 (string? body) body
                 (instance? InputStream body) (slurp body)
                 (nil? body) ""
                 :else (String. ^bytes body StandardCharsets/UTF_8)))]
    (doseq [cookie (if (string? cookies) [cookies] cookies)]
      (reset! browser (first (str/split cookie #";"))))
    (assoc response :text text)))

(defn begin! [browser mine]
  (request! browser :get "/bluegenes/test" {})
  (request! browser :post "/bluegenes/api/auth/oauth2authenticator"
            {:service {:root (:bluegenes-default-service-root config/env) :token "anonymous-token"}
             :mine-id "test" :provider "TEST" :request-id "browser-request-1"}))

(defn authorization-params [response]
  (let [text (:text response)
        link (if (str/starts-with? text "https://") text
                 (:authorizationUrl (json/parse-string text true)))]
    (codec/form-decode (.getRawQuery (java.net.URI. link)))))

(defmacro with-mine [[mine] & body]
  `(let [~mine (fixture/start-mine)]
     (try
       (with-redefs [config/env (configuration ~mine)]
         (with-redefs [transactions/store (transactions/new-store)]
           (with-redefs [handler/handler (handler/make-handler)] ~@body)))
       (finally ((:close ~mine))))))
