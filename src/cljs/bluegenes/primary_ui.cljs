(ns bluegenes.primary-ui
  (:require [clojure.string :as str]
            [imcljs.path :as path]
            [imcljs.query :as query]
            [re-frame.core :refer [reg-event-fx reg-sub]]
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

(defn parse-query
  "Read a bounded query link and validate its model paths before opening it."
  [model text]
  (let [element (query-element text)
        model-name (.getAttribute element "model")
        views (str/split (str/trim (or (.getAttribute element "view") "")) #"\s+")
        parsed (-> (utils/read-xml-query text)
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

(reg-event-fx
 :primary-ui/import-query
 (fn [{db :db} [_ mine xml run]]
   (when (= (keyword mine) (:current-mine db))
     (let [db (-> db
                  (update :messages dissoc :primary-ui/query-link)
                  (dissoc :primary-ui/query-error))]
       (if (nil? xml)
         {:db db
          :dispatch [:bluegenes.pages.querybuilder.events/load-querybuilder]}
         (try
           (let [model (get-in db [:mines (:current-mine db) :service :model])
                 parsed (parse-query model xml)]
             (if (= "true" run)
               {:db db
                :dispatch [:results/history+ {:source (:current-mine db)
                                              :type :query :intent :query :value parsed}]}
               {:db db
                :dispatch-n [[:bluegenes.pages.querybuilder.events/load-querybuilder]
                             [:qb/load-query parsed]]}))
           (catch js/Error _
             {:db (assoc db :primary-ui/query-error {:mine (:current-mine db)})
              :dispatch [:messages/add
                         {:id :primary-ui/query-link
                          :markup [:span "Unable to open this query link. Check the XML and model for this mine."]
                          :style "warning" :timeout 0}]})))))))

(reg-sub
 :primary-ui/query-error
 (fn [db]
   (= (:current-mine db) (get-in db [:primary-ui/query-error :mine]))))

(defn query-error-panel []
  [:section.container
   [:h1 "Query link unavailable"]
   [:p "The linked query was not loaded. Your existing query draft is still available."]
   [:a {:href (route/href ::route/querybuilder)} "Open query builder"]])
