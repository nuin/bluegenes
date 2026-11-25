(ns bluegenes.primary-ui
  (:require [clojure.string :as str]
            [imcljs.path :as path]
            [imcljs.query :as query]
            [re-frame.core :refer [reg-event-fx reg-sub dispatch]]
            [bluegenes.route :as route]
            [bluegenes.utils :as utils]))

(defn- query-element [text]
  (when (or (not (string? text)) (str/blank? text) (> (count text) 8192)
            (str/includes? (str/upper-case text) "<!DOCTYPE"))
    (throw (js/Error. "Invalid query link")))
  (let [document (.parseFromString (js/DOMParser.) text "application/xml")
        element (.-documentElement document)]
    (when (or (pos? (.-length (.getElementsByTagName document "parsererror")))
              (not= "query" (.-tagName element)))
      (throw (js/Error. "Invalid query XML")))
    element))

(defn- valid-path? [model value]
  (and (string? value)
       (not (str/blank? value))
       (let [walked (path/walk model value)]
         (and (seq walked) (every? some? walked)))))

(defn- supported-xml [element]
  (doseq [constraint (array-seq (.querySelectorAll element "constraint"))]
    (when (or (some #(.hasAttribute constraint %) ["extraValue" "loopPath" "ids"])
              (some #(re-find #"[&<>]" (.-textContent %))
                    (array-seq (.querySelectorAll constraint "value"))))
      (throw (js/Error. "This query constraint cannot be imported without changing it"))))
  (doseq [join (array-seq (.querySelectorAll element "join"))]
    (let [style (str/upper-case (or (.getAttribute join "style") ""))]
      (when-not (contains? #{"INNER" "OUTER"} style)
        (throw (js/Error. "Invalid query join style")))
      (.setAttribute join "style" style)))
  (let [joins (map #(.getAttribute % "path") (array-seq (.querySelectorAll element "join")))
        order (or (not-empty (.getAttribute element "sortOrder"))
                  (not-empty (.getAttribute element "orderBy")))]
    (when (not= (count joins) (count (distinct joins)))
      (throw (js/Error. "Repeated join definitions cannot be imported")))
    (when-not (str/blank? order)
      (let [words (str/split (str/trim order) #"\s+")
            words (if (= 1 (count words)) (conj words "ASC") words)]
        (when (or (odd? (count words))
                  (some #(not (contains? #{"ASC" "DESC"} (str/upper-case %)))
                        (take-nth 2 (rest words))))
          (throw (js/Error. "Invalid query sort order")))
        (.setAttribute element "sortOrder" (str/join " " words)))))
  (.serializeToString (js/XMLSerializer.) element))

(defn parse-query
  "Read a bounded query link and validate its model paths before opening it."
  [model text]
  (let [element (query-element text)
        model-name (.getAttribute element "model")
        views (str/split (str/trim (or (.getAttribute element "view") "")) #"\s+")
        parsed (-> (utils/read-xml-query (supported-xml element))
                   (assoc :from (first (str/split (first views) #"\."))
                          :select views
                          :title (or (not-empty (.getAttribute element "name")) "Linked query"))
                   query/sterilize-query)
        typed-model (assoc model :type-constraints (:where parsed))
        paths (concat (:select parsed) (map :path (:where parsed))
                      (:joins parsed) (map :path (:sortOrder parsed)))]
    (when (or (and (not (str/blank? model-name)) (not= model-name (:name model)))
              (not-every? #(valid-path? typed-model %) paths)
              (some #(and (:type %) (not (contains? (:classes model) (keyword (:type %)))))
                    (:where parsed)))
      (throw (js/Error. "Query does not match this mine's model")))
    parsed))

(def organism-paths
  (mapv #(str "Organism." %) ["name" "taxonId" "species" "shortName" "genus" "commonName"]))

(def region-paths
  ["SequenceFeature.id" "SequenceFeature.name" "SequenceFeature.primaryIdentifier"
   "SequenceFeature.symbol" "SequenceFeature.chromosomeLocation.start"
   "SequenceFeature.chromosomeLocation.end" "SequenceFeature.chromosomeLocation.strand"
   "SequenceFeature.chromosomeLocation.locatedOn.primaryIdentifier"
   "SequenceFeature.organism.shortName" "SequenceFeature.organism.name"
   "Chromosome.organism.shortName" "Chromosome.organism.name"])

(defn capabilities [db mine]
  (let [value (get-in db [:assets :web-service-version mine])
        raw-version (if (string? value) (str/trim value) value)
        version (when (or (number? raw-version)
                          (and (string? raw-version) (re-matches #"[0-9]+" raw-version)))
                  (js/Number raw-version))
        model (get-in db [:mines mine :service :model])
        failed (set (get-in db [:failed-assets mine]))
        status (cond
                 (some failed [:assets/fetch-model :assets/fetch-web-service-version]) :metadata-failed
                 (seq (get-in db [:primary-ui/metadata-pending mine])) :loading
                 (or (nil? raw-version) (= "" raw-version)) :missing-version
                 (not (and (js/Number.isSafeInteger version) (pos? version))) :invalid-version
                 (< version 27) :unsupported-version
                 (not (and (map? (:classes model)) (seq (:classes model)))) :missing-model
                 :else :ready)
        ready? (= status :ready)]
    {:status status :version version
     :oauth? (and ready? (>= version 36))
     :organisms? (and ready? (every? #(valid-path? model %) organism-paths))
     :regions? (and ready? (every? #(valid-path? model %) (concat organism-paths region-paths)))}))

(defn- route-key [db]
  (select-keys (:current-route db) [:path :query-params]))

(reg-event-fx
 :primary-ui/import-query
 (fn [{db :db} [_ mine xml run]]
   (when (= (keyword mine) (:current-mine db))
     {:db (-> db
              (update :messages dissoc :primary-ui/query-link)
              (dissoc :primary-ui/query-error)
              (assoc :primary-ui/pending-query {:mine (:current-mine db) :xml xml :run run
                                                :route (route-key db)}))
      :dispatch [:primary-ui/resume-query]})))

(defn- import-ready-query [db xml run]
  (if (nil? xml)
    {:db db :dispatch [:bluegenes.pages.querybuilder.events/load-querybuilder]}
    (try
      (let [model (get-in db [:mines (:current-mine db) :service :model])
            parsed (parse-query model xml)]
        (if (= "true" run)
          {:db db
           :dispatch-n (route/result-panel-events
                        [:results/history+ {:source (:current-mine db) :type :query
                                            :intent :query :value parsed} true])}
          {:db db
           :dispatch-n [[:bluegenes.pages.querybuilder.events/load-querybuilder]
                        [:qb/load-query parsed]]}))
      (catch js/Error _
        {:db (assoc db :primary-ui/query-error {:mine (:current-mine db)})
         :dispatch [:messages/add
                    {:id :primary-ui/query-link
                     :markup [:span "Unable to open this query link. It is invalid or uses a query feature this version of BlueGenes cannot preserve."]
                     :style "warning" :timeout 0}]}))))

(reg-event-fx
 :primary-ui/resume-query
 (fn [{db :db}]
   (when-let [{:keys [mine xml run route]} (:primary-ui/pending-query db)]
     (cond
       (or (not= mine (:current-mine db)) (not= route (route-key db)))
       {:db (dissoc db :primary-ui/pending-query)}

       (= :ready (:status (capabilities db mine)))
       (import-ready-query (dissoc db :primary-ui/pending-query) xml run)))))

(reg-sub
 :primary-ui/query-error
 (fn [db]
   (= (:current-mine db) (get-in db [:primary-ui/query-error :mine]))))

(defn query-error-panel []
  [:section.container
   [:h1 "Query link unavailable"]
   [:p "The linked query was not loaded. Your existing query draft is still available."]
   [:a {:href (route/href ::route/querybuilder)} "Open query builder"]])

(reg-sub
 :primary-ui/capabilities
 (fn [db] (capabilities db (:current-mine db))))

(defn compatibility-panel [{:keys [status version]}]
  [:section.container {:role "alert"}
   [:h1 "Mine unavailable"]
   [:p (case status
         :loading "The mine's version and data model are still loading."
         :unsupported-version (str "This mine uses API " version ". BlueGenes requires API 27 or later.")
         :missing-version "The mine did not provide its API version."
         :invalid-version "The mine returned an invalid API version."
         :missing-model "The mine's data model is unavailable."
         :metadata-failed "The mine's version or data model could not be loaded. Cached metadata has not been used."
         "This mine is not ready.")]
   [:p "Try again or select another mine. If this continues, contact the mine's administrator."]
   [:button.btn.btn-primary {:type "button" :on-click #(dispatch [:reboot])} "Try again"]])

(defn regions-unavailable-panel []
  [:section.container {:role "status"}
   [:h1 "Regions unavailable"]
   [:p "This mine does not provide the genomic features needed for region search."]
   [:a {:href (route/href ::route/querybuilder)} "Open query builder"]])
