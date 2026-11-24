(ns bluegenes.auth.transactions
  (:require [bluegenes.auth.intermine :as mine]
            [clojure.string :as str])
  (:import [java.security SecureRandom MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def ^:private random (SecureRandom.))
(def ^:private lifetime-ms (* 5 60 1000))
(def ^:private context-lifetime-ms (* 24 60 60 1000))

(defn new-store
  ([] (new-store {}))
  ([{:keys [clock capacity] :or {clock #(System/currentTimeMillis) capacity 10000}}]
   {:contexts (atom {}) :active (atom {}) :clock clock :capacity capacity}))

(defonce store (new-store))

(defn- now [] ((:clock store)))

(defn- nonce []
  (let [bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- invalid-state []
  (ex-info "This sign-in attempt is invalid or has expired. Please start again."
           {:status 400 :kind :state}))

(defn existing-context [request]
  (when-let [context (get @(:contexts store) (get-in request [:session :auth-context]))]
    (when (< (- (now) (:seen @(:data context))) context-lifetime-ms)
      (swap! (:data context) assoc :seen (now))
      context)))

(defn context! [request]
  (when (get-in request [:session :retired-auth-context]) (throw (invalid-state)))
  (or (existing-context request)
      (locking (:contexts store)
        (swap! (:contexts store)
               #(into {} (filter (fn [[_ context]]
                                   (< (- (now) (:seen @(:data context))) context-lifetime-ms)) %)))
        (when (>= (count @(:contexts store)) 10000)
          (throw (ex-info "Authentication is busy. Please try again later." {:status 503})))
        (let [context {:id (nonce) :lock (Object.)
                       :data (atom {:generation 0 :seen (now)})}]
          (swap! (:contexts store) assoc (:id context) context)
          context))))

(defn session [request context]
  (-> (:session request)
      (dissoc :identity :service :mine-id :redirect_uri)
      (assoc :auth-context (:id context)
             :auth-generation (:generation @(:data context)))))

(defn logout-context! [request]
  (if-let [id (get-in request [:session :retired-auth-context])]
    (let [context (get @(:contexts store) id)]
      (when-not (and context (= (get-in request [:session :auth-generation])
                                (:generation @(:data context))))
        (throw (invalid-state)))
      context)
    (context! request)))

(defn- owned? [context attempt]
  (and (identical? context (get @(:contexts store) (:id context)))
       (= (:generation attempt) (:generation @(:data context)))
       (= (:handle attempt) (get-in @(:data context) [:attempt :handle]))))

(defn callback-session [request context attempt]
  (locking (:lock context)
    (when (owned? context attempt)
      (session request context))))

(defn- reserve! [state expires]
  (locking (:active store)
    (swap! (:active store) #(into {} (filter (fn [[_ deadline]] (< (now) deadline)) %)))
    (when (>= (count @(:active store)) (:capacity store))
      (throw (ex-info "Authentication is busy. Please try again later." {:status 503})))
    (swap! (:active store) assoc state expires)))

(defn- release! [attempt]
  (swap! (:active store) dissoc (:state attempt)))

(defn invalidate!
  "Invalidate before cleanup; a timeout leaves the attempt canceled locally."
  ([context] (invalidate! context nil))
  ([context logout-mine]
   (locking (:lock context)
     (let [attempt (:attempt @(:data context))
           cleanup? (and attempt (or (not= :delivered (:phase attempt))
                                     (= logout-mine (:mine-id attempt))))]
       (swap! (:data context) #(-> % (update :generation inc) (dissoc :notice)))
       (when cleanup?
         (swap! (:data context) assoc-in [:attempt :phase] :canceling)
         (mine/cancel! attempt))
       (release! attempt)
       (swap! (:data context) dissoc :attempt)))))

(defn begin! [context binding]
  (locking (:lock context)
    (invalidate! context)
    (let [state (nonce)
          generation (:generation @(:data context))
          expires (+ (now) lifetime-ms)]
      (reserve! state expires)
      (try
        (let [prepared (mine/request! (:service binding) "prepare"
                                      (cond-> {:provider (:provider binding)
                                               :redirect_uri (:redirect-uri binding) :state state}
                                        (get-in binding [:service :token])
                                        (assoc :token (get-in binding [:service :token]))))
              {:keys [handle authorizationUrl]} prepared]
          (when-not (and (string? handle) (not (str/blank? handle)) (<= (count handle) 1024))
            (throw (ex-info "The mine returned an invalid sign-in response" {:status 502})))
          (swap! (:data context) assoc :attempt
                 (assoc binding :state state :handle handle :generation generation
                        :phase :pending :expires expires))
          (mine/validate-authorization-url! authorizationUrl state (:redirect-uri binding))
          {:authorizationUrl authorizationUrl})
        (catch Exception error
          (if (:attempt @(:data context))
            (invalidate! context)
            (release! {:state state}))
          (throw error))))))

(defn- same-state? [expected supplied]
  (and (string? supplied) (<= (count supplied) 1024)
       (MessageDigest/isEqual (.getBytes ^String expected StandardCharsets/UTF_8)
                              (.getBytes ^String supplied StandardCharsets/UTF_8))))

(defn claim! [context {:keys [provider state]}]
  (when-not context (throw (invalid-state)))
  (locking (:lock context)
    (let [attempt (:attempt @(:data context))]
      (when (and attempt (<= (:expires attempt) (now)))
        (invalidate! context)
        (throw (invalid-state)))
      (when-not (and (= :pending (:phase attempt))
                     (< (now) (:expires attempt))
                     (string? provider) (= provider (:provider attempt))
                     (same-state? (:state attempt) state))
        (throw (invalid-state)))
      (swap! (:data context) assoc-in [:attempt :phase] :exchanging)
      attempt)))

(defn- failed! [context attempt]
  (locking (:lock context)
    (when (owned? context attempt)
      (let [message (try (invalidate! context)
                         "OAuth sign-in failed. Please start again."
                         (catch Exception _
                           "Sign-in was canceled locally, but remote cancellation is unconfirmed."))]
        (swap! (:data context) assoc :notice
               {:mine-path (:mine-path attempt) :message message})))))

(defn finish! [context attempt {:keys [code error]}]
  (try
    (when (or error (not (string? code)) (str/blank? code) (> (count code) 8192))
      (throw (invalid-state)))
    (mine/request! (:service attempt) "exchange" {:handle (:handle attempt) :code code})
    (locking (:lock context)
      (when (owned? context attempt)
        (when (<= (:expires attempt) (now)) (throw (invalid-state)))
        (let [{:keys [user token renamedLists]}
              (mine/request! (:service attempt) "complete" {:handle (:handle attempt)})]
          (when-not (and (map? user) (string? token) (not (str/blank? token)))
            (throw (ex-info "The mine returned an invalid identity" {:status 502})))
          (swap! (:data context) update :attempt assoc :phase :ready
                 :identity (assoc user :token token :login-method :oauth2)
                 :renamedLists renamedLists))))
    (catch Exception _ (failed! context attempt))))

(defn- same-mine? [request binding]
  (let [path (:mine-path binding)]
    (and path (or (= (:uri request) path)
                  (str/starts-with? (:uri request) (str path "/"))))))

(defn- notice! [context request]
  (locking (:lock context)
    (when-let [notice (:notice @(:data context))]
      (when (same-mine? request notice)
        (swap! (:data context) dissoc :notice)
        {:events [[:messages/add {:style "warning" :markup (:message notice)}]]}))))

(defn render-init!
  "Render outside the lock, then confirm ownership before acknowledging delivery."
  [context request render]
  (let [attempt (locking (:lock context)
                  (let [attempt (:attempt @(:data context))]
                    (when (and (= :ready (:phase attempt)) (same-mine? request attempt))
                      (swap! (:data context) assoc-in [:attempt :phase] :rendering)
                      attempt)))]
    (if-not attempt
      (render (notice! context request))
      (try
        (when (<= (:expires attempt) (now)) (throw (invalid-state)))
        (let [html (render {:identity (:identity attempt) :renamedLists (:renamedLists attempt)
                            :auth-request {:mine (:mine-id attempt) :root (:public-root attempt)
                                           :id (:request-id attempt)}})
              delivered? (locking (:lock context)
                           (when (owned? context attempt)
                             (when (<= (:expires attempt) (now)) (throw (invalid-state)))
                             (mine/request! (:service attempt) "acknowledge" {:handle (:handle attempt)})
                             (swap! (:data context) assoc-in [:attempt :phase] :delivered)
                             (release! attempt)
                             true))]
          (if delivered? html (render (notice! context request))))
        (catch Exception _
          (failed! context attempt)
          (render (notice! context request)))))))
