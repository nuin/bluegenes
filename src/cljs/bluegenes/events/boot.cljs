(ns bluegenes.events.boot
  (:require [re-frame.core :refer [reg-event-db reg-event-fx inject-cofx reg-fx]]
            [bluegenes.db :as db]
            [imcljs.fetch :as fetch]
            [bluegenes.route :as route]
            [bluegenes.utils :as utils]
            [bluegenes.pages.lists.events :as lists]
            [bluegenes.config :refer [server-vars init-vars read-default-ns]]
            [clojure.string :as str]
            [bluegenes.events.auth :refer [renamedLists->message clear-private-assets]]
            [bluegenes.auth.requests :as requests]
            [bluegenes.auth.storage :as storage]))

(defn boot-flow
  "Produces a set of re-frame instructions that load all of InterMine's assets into BlueGenes
  See https://github.com/Day8/re-frame-async-flow-fx
  The idea is that any URL routing (such as entering BlueGenes at the home page or a subsection)
  is queued until all of the assets (data model, lists, templates etc) are fetched.
  When finished, an event called :finished-loading-assets is dispatch which tells BlueGenes
  it can continue routing."
  [& {:keys [wait-registry?]}]
  ;; wait-registry? is to indicate that the current mine requries data from the
  ;; registry, which isn't present (usually because it is a fresh boot).
  {:id :async/custom-flow
   :db-path [:boot-flow]
   :rules (filterv
           some?
           [(when wait-registry?
              {:when :seen?
               :events :bluegenes.events.registry/success-fetch-registry
               :dispatch [:authentication/init]})
            ;; Wait for token as some assets need it for private data (eg. lists, queries).
            {:when :seen?
             :events :authentication/ready
             :dispatch-n [[:assets/fetch-web-properties]
                          [:assets/fetch-bg-properties]
                          [:assets/fetch-model]
                          [:assets/fetch-lists]
                          [:assets/fetch-class-keys]
                          [:assets/fetch-templates]
                          [:assets/fetch-widgets]
                          [:assets/fetch-summary-fields]
                          [:assets/fetch-intermine-version]
                          [:assets/fetch-web-service-version]
                          [:assets/fetch-release-version]
                          [:assets/fetch-branding]
                          ;; Errors for tool fetching are handled separately.
                          [:bluegenes.components.tools.events/fetch-tools]]}
            ;; Wait for all events that indicate our assets have been fetched successfully.
            {:when :seen-all-of?
             :events [:assets/success-fetch-model
                      :assets/success-fetch-web-properties
                      :assets/success-fetch-bg-properties
                      :assets/success-fetch-lists
                      :assets/success-fetch-class-keys
                      :assets/success-fetch-templates
                      :assets/success-fetch-summary-fields
                      :assets/success-fetch-widgets
                      :assets/success-fetch-intermine-version
                      :assets/success-fetch-web-service-version
                      :assets/success-fetch-release-version
                      :assets/success-fetch-branding]
             :dispatch [:boot/finalize]
             :halt? true}
            ;; Handle the case where one or more assets fail to fetch.
            {:when :seen?
             :events :assets/failure
             :dispatch [:boot/finalize :failed-assets? true]
             :halt? true}])})

;; It's not really optimal that `:assets/failure` starts `:boot/finalize`
;; immediately. This may also (through a race condition) log errors that the
;; `:async/custom-flow` event handler is missing, since it would have been
;; deregistered. It's very unlikely that this will actually break something,
;; and we shouldn't optimize for faulty webservices. Just to note, the proper
;; way would be to wait for all `:assets/*` to either succeed or fail, then
;; halt and run `:boot/finalize`. Right now any failure will result in
;; `:boot/finalize` running, even with other `:assets/*` still in progress.

(reg-event-fx
 :boot/finalize
 (fn [_ [_ & {:keys [failed-assets?]}]]
   {:dispatch-n
    [[::start-router]
     [:start-analytics]
     ;; Set a flag indicating all assets are fetched.
     [:finished-loading-assets]
     ;; Save the current state to local storage.
     (when-not failed-assets? [:save-state])
     [:cache/fetch-organisms]
     [:regions/select-all-feature-types]
     [:clear-init-vars]]}))

(defn im-tables-events-forwarder
  "Creates instructions for listening in on im-tables events.
  Why? im-tables is its own re-frame application and it can save query results.
  When its save-list-success event is seen, fire a BlueGenes event to re-fetch lists"
  []
  {:register :im-tables-events
   :events #{:imt.io/save-list-success :imt.io/save-list-failure}
   :dispatch-to [:intercept-save-list]})

; When a list is saved from im-tables, intercept the message
; and show an alert while also refreshing the user's lists
(reg-event-fx
 :intercept-save-list
 (fn [{db :db} [_ [evt-id {:keys [listName listSize] :as evt}]]]
   (case evt-id
     :imt.io/save-list-success
     {:dispatch-n [[:assets/fetch-lists]
                   [:messages/add
                    {:markup [:span "Saved list: "
                              [:a {:href (route/href ::route/results {:title listName})}
                               listName]]
                     :style "success"}]]}

     :imt.io/save-list-failure
     {:dispatch [:messages/add
                 {:markup [:span (str "Failed to save list: " listName)]
                  :style "danger"}]}

     (.error js/console (str "Unhandled forwarded im-tables event " evt-id)))))

(defn init-configured-mine
  "Parse configured mine data passed from BlueGenes config."
  [{:keys [root name namespace external]}]
  {:id (keyword namespace)
   :name name
   :service {:root root}
   :external? external})

(defn init-mine-defaults
  "Load default mine data, as passed from a coupled InterMine or defined
  BlueGenes config."
  []
  {:id (read-default-ns)
   :name (:bluegenes-default-mine-name @server-vars)
   :service {:root (:bluegenes-default-service-root @server-vars)}})

(defn init-config-mines []
  (->> (:bluegenes-additional-mines @server-vars)
       (map init-configured-mine)
       (into [(init-mine-defaults)])
       (reduce (fn [mines {:keys [id] :as mine}]
                 (assoc mines id mine))
               {})))

;; Boot the application.
(reg-event-fx
 :boot
 [(inject-cofx :local-store :bluegenes/state)]
 (fn [cofx _]
   (let [default-ns (read-default-ns)
         ;; We have to set the db current-mine using `window.location` as the
         ;; router won't have dispatched `:set-current-mine` before later on.
         current-mine (or (keyword (utils/mine-from-pathname)) default-ns)
         ;; These could be passed from the Bluegenes backend and result in
         ;; events being dispatched.
         linkIn       (some-> @init-vars :linkIn not-empty)
         init-events (-> (some-> @init-vars :events not-empty)
                         (cond->
                           linkIn       ((fnil conj []) [:handle-link-in linkIn])))
         ;; Configured mines are also passed from the Bluegenes backend,
         ;; usually defined in config.edn.
         config-mines (init-config-mines)
         mine (get config-mines current-mine)
         init-db (-> db/default-db
                     (assoc-in [:env :mines] config-mines)
                     (assoc-in [:mines current-mine] mine)
                     (assoc :current-mine current-mine)
                     (cond->
                       init-events (assoc :dispatch-after-boot init-events)))
         ;; Load data previously persisted to local storage.
         {:keys [assets version] :as state} (storage/public-cache (:local-store cofx))
         db (cond-> init-db
              ;; Only use data from local storage if it's present and the
              ;; client version matches.
              (and (seq state)
                   (= version (:version @server-vars)))
              (assoc :assets assets))
         no-registry? (:hide-registry-mines @server-vars)
         wait-registry? (if no-registry? false (empty? mine))]
     (merge
      {:db db
       :persist [:bluegenes/state state]
       ;; Start the timer for showing the loading dialog.
       :mine-loader true
       ;; Boot the application asynchronously
       :async-flow (boot-flow :wait-registry? wait-registry?)
       ;; Register an event sniffer for im-tables
       :forward-events (im-tables-events-forwarder)}
      (cond
        (and (empty? mine) no-registry?) ; Nonexistent mine.
        {:db (-> db
                 (assoc :current-mine default-ns)
                 (assoc-in [:mines default-ns] (get config-mines default-ns)))
         :dispatch-n [[:authentication/init]
                      [:messages/add
                       {:markup [:span "Your mine has been changed to the default as your selected mine " [:em (name current-mine)] " has not been configured."]
                        :style "warning"
                        :timeout 0}]]
         :change-route (name default-ns)}
        no-registry? ; Valid mine.
        {:dispatch [:authentication/init]}
        wait-registry? ; Registry mine.
        {:dispatch [:bluegenes.events.registry/load-other-mines]}
        :else ; Valid mine and registry active.
        {:dispatch-n [[:authentication/init]
                      [:bluegenes.events.registry/load-other-mines]]})))))

(defn remove-stateful-keys-from-db
  "Any pages / components that have mine-specific state should lose that state
  if we switch mines. For example, in list upload (ID Resolver), drosophila IDs
  are no longer valid when using humanmine. This is also used when logging out."
  [db]
  ;; Perhaps we should consider settings `:assets` to `{}` here as well?
  (-> db
      ;; Other mines might have a different model, so we reset the querybuilder.
      (assoc :qb (:qb db/default-db))
      ;; The below were undocumented; please add a comment for each of them
      ;; when you learn why they're there.
      (dissoc :regions :idresolver :results
              :suggestion-results ; Avoid showing old results belonging to previous mine.
              :invalid-token?     ; Clear invalid-token-alert.
              :failed-auth?       ; Clear flag for failing to auth with mine.
              :failed-assets      ; Failed asset events per mine, shown in one message.
              :profile)           ; All profile page data should be cleared.
      ;; Clear chosen template category as it may not be present in new mine.
      (update :home dissoc :active-template-category)
      (update :cache dissoc :organisms)
      ;; Set lists page number back to 1.
      (assoc-in [:lists :pagination :current-page] 1)
      ;; Clear lists page selected lists.
      (update-in [:lists :selected-lists] empty)
      ;; The old by-id map is used to detect newly added lists.
      ;; During a mine change, this will mean all lists, which we don't want.
      (update-in [:lists :by-id] empty)
      ;; Clear any chosen template and filters
      (update :components dissoc :template-chooser)
      ;; Clear admin page manage templates panel.
      (assoc-in [:admin :manage-templates] nil)))

(defn- remove-assets-failure-messages
  "Drop the timeout-less asset failure warnings so a clean reboot does not keep a stale one."
  [messages]
  (into {} (remove (fn [[id _]] (= "assets-failure" (some-> id namespace)))) messages))

(reg-event-fx
 :reboot
 (fn [{db :db}]
   {:db (-> (remove-stateful-keys-from-db db)
            (assoc :fetching-assets? true)
            (update :messages remove-assets-failure-messages))
    :dispatch [:authentication/init]
    :async-flow (boot-flow :wait-registry? false)}))

(reg-fx
 ::start-router-fx
 (fn [_]
   (route/init-routes!)))

(reg-event-fx
 ::start-router
 (fn [_ _]
   {::start-router-fx {}}))

;; Note that failed-auth? can mean there's no connection to the mine.
(reg-event-fx
 :finished-loading-assets
 (fn [{db :db}]
   (let [dispatch-after-boot (:dispatch-after-boot db)
         ;; We want to block navigation on failed auth, as this could cause a
         ;; crash when opening a page that expects a mine to be connected.
         failed-auth? (:failed-auth? db)]
     (cond-> {:db (-> db
                      (dissoc :dispatch-after-boot)
                      (assoc :fetching-assets? false))
              :mine-loader false}
       (and (some? dispatch-after-boot)
            (not failed-auth?)) (assoc :dispatch-n dispatch-after-boot)
       ;; Even if we're supposed to show a different panel, we'll show the
       ;; homepage instead on failed auth (navigation will be blocked).
       failed-auth? (assoc :dispatch [:do-active-panel :home-panel])))))

(reg-event-fx
 :start-analytics
 (fn [{db :db}]
   (let [analytics-id (:google-analytics @server-vars)
         analytics-enabled? (not (clojure.string/blank? analytics-id))]
     (if analytics-enabled?
        ;;set tracker up if we have a tracking id
       (do
         (try
           (js/gtag "js" (js/Date.))
           (js/gtag "config" analytics-id)
           (catch js/Error _))
         (.info js/console
                "Google Analytics enabled. Tracking ID:"
                analytics-id))
        ;;inobtrusive console message if there's no id
       (.info js/console "Google Analytics disabled. No tracking ID."))
     {:db (assoc db :google-analytics
                 {:enabled? analytics-enabled?
                  :analytics-id analytics-id})})))

;; init-vars is sent from the Bluegenes backend and intended to be consumed
;; during boot. Here we clear init-vars to ensure it cannot be used again.
(reg-event-fx
 :clear-init-vars
 (fn [_]
   {:clear-init-vars-fx {}}))

(reg-fx
 :clear-init-vars-fx
 (fn [_]
   (set! init-vars (delay nil))
   (set! js/initVars nil)))

(defn- oauth-handoff [request persisted]
  (let [{:keys [identity auth-request renamedLists]} @init-vars]
    (when (and (seq identity) (string? (:id auth-request))
               (= (:mine auth-request) (some-> (:mine request) name))
               (= (:root auth-request) (:root request))
               (= (:id auth-request) (get persisted (requests/key-for request))))
      {:identity identity :renamedLists renamedLists})))

(reg-event-fx
 :authentication/init requests/interceptors
 (fn [{:keys [db auth-requests local-store]} [evt]]
   (let [mine (:current-mine db)
         fresh (requests/create db mine)
         request (assoc fresh :id (or (get auth-requests (requests/key-for fresh)) (:id fresh)))
         {:keys [identity renamedLists]} (oauth-handoff request auth-requests)
         saved (storage/login-for-root local-store mine (:root request))
         db (cond-> (requests/attach db request)
              (or identity saved) (assoc-in [:mines mine :auth :identity] (or identity saved)))
         auth-token (get-in db [:mines mine :auth :identity :token])
         service (get-in db [:mines mine :service])
         token (or auth-token (:token service))
         rejected (when-not identity (:identity @init-vars))
         binding (some-> (:auth-request @init-vars) (update :mine keyword))]
     (merge (when (and rejected binding) (requests/discard db binding (:token rejected)))
            {:db db
             :persist-many [(requests/persistence auth-requests request)
                            [:bluegenes/login (cond-> local-store
                                                (nil? saved) (dissoc mine))]]
             :im-chan (if token
                        {:chan (storage/validate-token service token)
                         :on-success [:authentication/store-token request token identity renamedLists]
                         :on-unauthorised [:authentication/invalid-token request (boolean auth-token)]
                         :on-failure [:authentication/failure request evt]}
                        {:chan (fetch/session service)
                         :on-success [:authentication/store-token request]
                         :on-failure [:authentication/failure request evt]})}))))

(reg-event-fx
 :authentication/invalid-token requests/interceptors
 (fn [{:keys [db auth-requests local-store]} [evt {:keys [mine] :as request} clear-login?]]
   (when (requests/current? db auth-requests request)
     (cond-> {:im-chan {:chan (fetch/session (get-in db [:mines mine :service]))
                        :on-success [:authentication/store-token request]
                        :on-failure [:authentication/failure request evt]}}
       clear-login? (assoc :db (-> db
                                   (assoc-in [:mines mine :auth :identity] nil)
                                   (assoc-in [:mines mine :service :token] nil)
                                   (clear-private-assets mine))
                           :persist [:bluegenes/login (dissoc local-store mine)])))))

(reg-event-fx
 :authentication/store-token requests/interceptors
 (fn [{:keys [db auth-requests local-store]}
      [_ {:keys [mine] :as request} token oauth-identity renamedLists]]
   (if-not (requests/current? db auth-requests request)
     (when oauth-identity (requests/discard db request token))
     (cond-> {:db (assoc-in db [:mines mine :service :token] token)
              :dispatch-n (cond-> []
                            (= mine (:current-mine db)) (conj [:authentication/ready])
                            (seq renamedLists) (conj (renamedLists->message renamedLists)))}
       oauth-identity (assoc :persist [:bluegenes/login (assoc local-store mine (storage/bound-login (:root request) oauth-identity))])
       (nil? token) (assoc :dispatch [:messages/add
                                      {:markup "Could not acquire credentials. Please check the connection to the mine."
                                       :style "danger" :timeout 0}])))))

(reg-event-fx :authentication/ready (fn [_ _] {}))

(reg-event-fx
 :authentication/failure requests/interceptors
 (fn [{:keys [db auth-requests]} [_ {:keys [mine] :as request} evt]]
   (when (and (requests/current? db auth-requests request) (= mine (:current-mine db)))
     {:dispatch [:assets/failure evt]})))

; Fetch model
(def preferred-tag "im:preferredBagType")
(defn preferred-fields
  "extricate preferred fields (e.g. default field types for dropdowns, usually protein and gene) from the model"
  [model]
  (keys (filter (comp #(contains? % preferred-tag)
                      set :tags second) (:classes model))))

(defn extends-hierarchy
  "Subclasses in the model are specified via the extends key. We build a
  first-class hierarchy type using this so we can query whether a class
  is a subclass for another class."
  [model-classes]
  (reduce (fn [h [child {:keys [extends]}]]
            (reduce #(derive %1 child %2) h (map keyword extends)))
          (make-hierarchy)
          model-classes))

(reg-event-db
 :assets/success-fetch-model
 (fn [db [_ mine-kw model]]
   ;; We used to remove empty classes (zero count) from the model here, but
   ;; this turned out to be a very bad idea! This is because the model is used
   ;; to parse paths into classes, which means it has to be complete. This also
   ;; applies to the model hierarchy.
   (let [default-object-types (vec (sort (preferred-fields model)))]
     (-> db
         (assoc-in [:mines mine-kw :service :model] model)
         (assoc-in [:mines mine-kw :default-object-types] default-object-types)
         (assoc-in [:idresolver :stage :options :type] (some-> default-object-types first name))
         (assoc-in [:mines mine-kw :model-hier] (extends-hierarchy (:classes model)))))))

(reg-event-fx
 :assets/fetch-model
 (fn [{db :db} [evt]]
   {:db db
    :im-chan {:chan (fetch/model (get-in db [:mines (:current-mine db) :service]))
              :on-success [:assets/success-fetch-model (:current-mine db)]
              :on-failure [:assets/failure evt]}}))

; Fetch lists

(defn- current-service? [db mine service]
  (= service (select-keys (get-in db [:mines mine :service]) [:root :token])))

(reg-event-fx
 :assets/unauthorised-for-service
 (fn [{:keys [db]} [_ mine service]]
   (when (and (= mine (:current-mine db)) (current-service? db mine service))
     {:dispatch [:flag-invalid-token]})))

(reg-event-fx
 :assets/failure-for-service
 (fn [{:keys [db]} [_ mine service evt]]
   (when (and (= mine (:current-mine db)) (current-service? db mine service))
     {:dispatch [:assets/failure evt]})))

(reg-event-fx
 :assets/success-fetch-lists
 (fn [{:keys [db]} [_ mine service next-evt lists]]
   (when (current-service? db mine service)
     (cond-> {:db (assoc-in db [:assets :lists mine] lists) :dispatch-n []}
       (vector? next-evt) (update :dispatch-n conj next-evt)
       (and (= mine (:current-mine db)) (= :lists-panel (:active-panel db)))
       (update :dispatch-n conj [:lists/initialize])))))

(reg-event-fx
 :assets/fetch-lists
 (fn [{:keys [db]} [evt next-evt origin-mine]]
   (let [mine (or origin-mine (:current-mine db))
         service (get-in db [:mines mine :service])
         snapshot (select-keys service [:root :token])]
     {:db (cond-> db
            (= mine (:current-mine db)) (assoc-in (concat lists/root [:fetching-lists?]) true))
      :im-chan {:chan (fetch/lists service {:showTags true})
                :on-success [:assets/success-fetch-lists mine snapshot next-evt]
                :on-unauthorised [:assets/unauthorised-for-service mine snapshot]
                :on-failure [:assets/failure-for-service mine snapshot evt]}})))

; Fetch class keys

(reg-event-db
 :assets/success-fetch-class-keys
 (fn [db [_ mine-kw class-keys]]
   (assoc-in db [:mines mine-kw :class-keys] class-keys)))

(reg-event-fx
 :assets/fetch-class-keys
 (fn [{db :db} [evt]]
   {:db db
    :im-chan
    {:chan (fetch/class-keys
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-class-keys (:current-mine db)]
     :on-failure [:assets/failure evt]}}))

; Fetch templates

(reg-event-fx
 :assets/success-fetch-templates
 (fn [{:keys [db]} [_ mine service next-evt templates]]
   (when (current-service? db mine service)
     (cond-> {:db (assoc-in db [:assets :templates mine] templates) :dispatch-n []}
       (vector? next-evt) (update :dispatch-n conj next-evt)))))

(reg-event-fx
 :assets/fetch-templates
 (fn [{:keys [db]} [evt next-evt origin-mine]]
   (let [mine (or origin-mine (:current-mine db))
         service (get-in db [:mines mine :service])
         snapshot (select-keys service [:root :token])]
     {:im-chan {:chan (fetch/templates service)
                :on-success [:assets/success-fetch-templates mine snapshot next-evt]
                :on-unauthorised [:assets/unauthorised-for-service mine snapshot]
                :on-failure [:assets/failure-for-service mine snapshot evt]}})))

; Fetch summary fields

(reg-event-db
 :assets/success-fetch-summary-fields
 (fn [db [_ mine-kw lists]]
   (assoc-in db [:assets :summary-fields mine-kw] lists)))

(reg-event-fx
 :assets/fetch-summary-fields
 (fn [{db :db} [evt]]
   {:db db
    :im-chan
    {:chan (fetch/summary-fields
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-summary-fields (:current-mine db)]
     :on-failure [:assets/failure evt]}}))

(reg-event-fx
 :assets/fetch-widgets
 (fn [{db :db} [evt]]
   {:im-chan
    {:chan (fetch/widgets (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-widgets (:current-mine db)]
     :on-failure [:assets/failure evt]}}))

(reg-event-db
 :assets/success-fetch-widgets
 (fn [db [_ mine-kw widgets]]
   (assoc-in db [:assets :widgets mine-kw] widgets)))

(reg-event-fx
 :assets/fetch-intermine-version
 (fn [{db :db} [evt]]
   {:im-chan
    {:chan (fetch/version-intermine
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-intermine-version (:current-mine db)]
     :on-failure [:assets/failure evt]}}))

(reg-event-db
 :assets/success-fetch-intermine-version
 (fn [db [_ mine-kw version]]
   (assoc-in db [:assets :intermine-version mine-kw] version)))

(reg-event-fx
 :assets/fetch-web-service-version
 (fn [{db :db} [evt]]
   {:im-chan
    {:chan (fetch/version-web-service
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-web-service-version (:current-mine db)]
     :on-failure [:assets/failure evt]}}))

(reg-event-db
 :assets/success-fetch-web-service-version
 (fn [db [_ mine-kw version]]
   (assoc-in db [:assets :web-service-version mine-kw] version)))

(reg-event-fx
 :assets/fetch-release-version
 (fn [{db :db} [evt]]
   {:im-chan
    {:chan (fetch/version-release
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-release-version (:current-mine db)]
     :on-failure [:assets/failure evt]}}))

(reg-event-db
 :assets/success-fetch-release-version
 (fn [db [_ mine-kw version]]
   (assoc-in db [:assets :release-version mine-kw] version)))

(reg-event-fx
 :assets/fetch-branding
 (fn [{db :db} [evt]]
   {:im-chan
    {:chan (fetch/branding
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-branding (:current-mine db)]
     :on-failure [:assets/failure evt]}}))

(reg-event-db
 :assets/success-fetch-branding
 (fn [db [_ mine-kw branding-properties]]
   (assoc-in db [:mines mine-kw :branding] branding-properties)))

(reg-event-fx
 :handle-link-in
 (fn [{db :db} [_ {:keys [target data]}]]
   (case target
     :upload (let [classes (get-in db [:mines (:current-mine db) :service :model :classes])
                   identifiers (or (:externalids data) (:externalid data))
                   error (cond
                           (or (str/blank? identifiers) (str/blank? (:class data)))
                           "Class and externalids parameters need to be specified when linking in to upload page. You have been redirected to the home page."
                           (not (contains? classes (keyword (:class data))))
                           "Invalid class specified when linking in to upload page. You have been redirected to the home page.")]
               (if error
                 {:dispatch [:messages/add
                             {:markup [:span error]
                              :style "warning"
                              :timeout 0}]}
                 {:db (update-in db [:idresolver :stage :options] assoc
                                 :type (:class data)
                                 :organism (:extraValue data))
                  :dispatch
                  [:bluegenes.components.idresolver.events/parse-staged-files
                   nil
                   identifiers
                   {:case-sensitive false
                    :type (:class data)
                    :organism (:extraValue data)}]})))))

(defn- asset-label
  "Short name for a failed asset event, e.g. :assets/fetch-lists -> \"lists\"."
  [evt]
  (str/replace (name evt) #"^fetch-" ""))

(defn assets-failure-message
  "One warning message for a mine, listing every asset that failed to load.
  The fixed `:id` makes `:messages/add` replace the previous message."
  [mine-kw mine-name failed-evts]
  {:id (keyword "assets-failure" (if mine-kw (name mine-kw) "unknown"))
   :markup [:span "Failed to load " (str/join ", " (map asset-label failed-evts))
            " from " [:em mine-name] ". "
            "Please contact the maintainers of the InterMine instance. BlueGenes may work in reduced capacity."]
   :style "warning"
   :timeout 0})

(reg-event-fx
 :assets/failure
 (fn [{db :db} [_ evt]]
   (let [mine-kw (:current-mine db)
         mine-name (get-in db [:mines mine-kw :name])
         auth? (contains? #{:authentication/init :authentication/invalid-token} evt)
         failed (cond-> (get-in db [:failed-assets mine-kw] [])
                  (and (not auth?) (not (some #{evt} (get-in db [:failed-assets mine-kw]))))
                  (conj evt))]
     {:db (cond-> db
            auth? (assoc :failed-auth? true)
            (= evt :assets/fetch-lists) (assoc-in (concat lists/root [:fetching-lists?]) false)
            (not auth?) (assoc-in [:failed-assets mine-kw] failed))
      :dispatch (if auth?
                  [:messages/add
                   {:markup [:span "Failed to acquire token. It's likely that you have no connection to the InterMine instance."]
                    :style "danger"
                    :timeout 0}]
                  [:messages/add (assets-failure-message mine-kw mine-name failed)])})))
