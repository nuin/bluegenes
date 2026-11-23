(ns bluegenes.auth-fixture
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.util.codec :as codec])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def fixture-user {:username "fixture-account" :email "fixture@example.test"})

(defn- output [token]
  {:output {:user fixture-user :token token :renamedLists {}}
   :wasSuccessful true})

(defn- form-parameters [text]
  (if (str/blank? text) {} (codec/form-decode text)))

(defn start-mine []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)
        calls (atom [])
        errors (atom [])
        counts (atom {:exchanges 0 :transfers 0 :revoked #{}})
        entries (atom {})
        controls (atom {})
        handle-request
        (fn [exchange]
          (let [uri (.getRequestURI exchange)
                path (.getPath uri)
                params (merge (form-parameters (.getRawQuery uri))
                              (form-parameters (slurp (.getRequestBody exchange))))
                operation (last (str/split path #"/"))
                handle (get params "handle")
                entry (get @entries handle)
                [status body]
                (do
                  (swap! calls conj {:path path :params params
                                     :authorization (.getFirst (.getRequestHeaders exchange)
                                                               "Authorization")})
                  (if-let [status (get-in @controls [:status operation])]
                    [status {:error "upstream-secret-sentinel"}]
                    (case operation
                      "oauth2authenticator" [200 {:link "https://provider.example.test/authorize?client_id=test"}]
                      "oauth2callback" (do (swap! counts update :exchanges inc)
                                           (swap! counts update :transfers inc)
                                           [200 (output "legacy-token-sentinel")])
                      "prepare" (let [handle (str (java.util.UUID/randomUUID))
                                      link (str "https://provider.example.test/authorize?"
                                                (codec/form-encode {"state" (get params "state")
                                                                    "redirect_uri" (get params "redirect_uri")}))
                                      link (if-let [alter (:authorization-url @controls)] (alter link) link)]
                                  (swap! entries assoc handle (atom {:phase :pending}))
                                  [200 {:output {:handle handle :authorizationUrl link}}])
                      "exchange" (do
                                   (swap! counts update :exchanges inc)
                                   (when-let [{:keys [entered release]} (:exchange @controls)]
                                     (deliver entered true)
                                     (when (= ::timeout (deref release 5000 ::timeout))
                                       (throw (ex-info "Exchange barrier expired" {}))))
                                   (locking entry
                                     (if (= :pending (:phase @entry))
                                       (do (swap! entry assoc :phase :verified)
                                           [200 {:output {:success true}}])
                                       [409 {:error "Inactive transaction"}])))
                      "complete" (locking entry
                                   (if (= :verified (:phase @entry))
                                     (do
                                       (when-let [{:keys [entered release]} (:complete @controls)]
                                         (deliver entered true)
                                         (when (= ::timeout (deref release 5000 ::timeout))
                                           (throw (ex-info "Completion barrier expired" {}))))
                                       (swap! counts update :transfers inc)
                                       (let [token (str "issued-token-" (:transfers @counts))]
                                         (swap! entry assoc :phase :completed :token token)
                                         [200 (output token)]))
                                     [409 {:error "Inactive transaction"}]))
                      "acknowledge" (do (swap! entry assoc :phase :delivered)
                                        [200 {:output {:success true}}])
                      "cancel" (do (when entry
                                     (locking entry
                                       (when-let [token (:token @entry)]
                                         (swap! counts update :revoked conj token))
                                       (swap! entry assoc :phase :canceled)))
                                   [200 {:output {:success true}}])
                      "revoke" (do (swap! counts update :revoked conj (get params "token"))
                                   [200 {:output {:success true}}])
                      "login" [200 (output "password-token")]
                      "logout" (do
                                 (when-let [{:keys [entered release]} (:logout @controls)]
                                   (deliver entered true)
                                   (when (= ::timeout (deref release 5000 ::timeout))
                                     (throw (ex-info "Logout barrier expired" {}))))
                                 [200 {:wasSuccessful true}])
                      "users" [200 {:user {:temporaryToken "registration-token"}}]
                      [404 {:error "Unsupported fixture endpoint"}])))
                bytes (.getBytes (json/generate-string body) StandardCharsets/UTF_8)]
            (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
            (.sendResponseHeaders exchange status (alength bytes))
            (with-open [stream (.getResponseBody exchange)]
              (.write stream bytes))))]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (try (handle-request exchange)
                             (catch Exception error
                               (swap! errors conj error)
                               (throw error))))))
    (.setExecutor server executor)
    (.start server)
    {:root (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mine")
     :calls calls :counts counts :controls controls :entries entries :errors errors
     :close (fn [] (.stop server 0) (.shutdownNow executor))}))
