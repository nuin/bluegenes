(ns bluegenes.primary-ui-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame.test :refer-macros [run-test-sync]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [imcljs.fetch :as fetch]
            [bluegenes.route :as route]
            [bluegenes.events]
            [bluegenes.primary-ui :as primary]
            [bluegenes.test-utils :as utils]))

(use-fixtures :each utils/fixtures)

(def employee-model
  {:name "testmodel"
   :classes {:Employee {:name "Employee"
                        :attributes {:id {:name "id" :type "java.lang.Integer"}
                                     :name {:name "name" :type "java.lang.String"}}
                        :references {} :collections {}}}})

(def query-xml
  "<query model=\"testmodel\" view=\"Employee.name\" sortOrder=\"Employee.name DESC\"><constraint path=\"Employee.name\" op=\"=\" value=\"A &amp; β\"/></query>")

(deftest parses-query-semantics-without-losing-encoding-or-order
  (let [query (primary/parse-query employee-model query-xml)]
    (is (= "Employee" (:from query)))
    (is (= ["Employee.name"] (:select query)))
    (is (= "A & β" (get-in query [:where 0 :value])))
    (is (= [{:path "Employee.name" :direction "DESC"}] (:sortOrder query)))))

(deftest rejects-malformed-oversized-and-non-query-xml
  (doseq [xml ["<query" "<other view=\"Employee.name\"/>"
               "<query view=\"Employee.name\"><constraint></query>"
               "<!DOCTYPE query [<!ENTITY value 'expanded'>]><query view=\"Employee.name\"/>"
               (apply str (repeat 8193 "x")) [query-xml query-xml]]]
    (is (thrown? js/Error (primary/parse-query employee-model xml)))))

(deftest rejects-a-different-model-and-unresolvable-paths
  (doseq [xml ["<query model=\"genomic\" view=\"Employee.name\"/>"
               "<query view=\"Gene.symbol\"/>"
               "<query view=\"Employee.missing\"/>"
               "<query view=\"Employee.name\"><constraint path=\"Employee.missing\" op=\"=\" value=\"x\"/></query>"
               "<query view=\"Employee.name\" sortOrder=\"Employee.missing ASC\"/>"]]
    (is (thrown? js/Error (primary/parse-query employee-model xml)))))

(defn setup-query-entry! []
  (reset! rf-db/app-db {:current-mine :a
                        :mines {:a {:service {:root "https://a.example/mine" :model employee-model}}
                                :b {:service {:root "https://b.example/mine" :model employee-model}}}})
  (rf/reg-event-db :qb/load-query (fn [db [_ query]] (assoc db :opened-builder query)))
  (rf/reg-event-db :results/history+ (fn [db [_ query]] (assoc db :opened-results query))))

(deftest query-entry-imports-to-builder-unless-run-is-explicit
  (run-test-sync
   (setup-query-entry!)
   (rf/dispatch [:primary-ui/import-query "a" query-xml nil])
   (is (= "Employee" (get-in @rf-db/app-db [:opened-builder :from])))
   (is (nil? (:opened-results @rf-db/app-db)))
   (rf/dispatch [:primary-ui/import-query "a" query-xml "true"])
   (is (= :a (get-in @rf-db/app-db [:opened-results :source])))
   (is (= "A & β" (get-in @rf-db/app-db [:opened-results :value :where 0 :value])))))

(deftest stale-query-entry-does-not-run-in-a-different-mine
  (run-test-sync
   (setup-query-entry!)
   (swap! rf-db/app-db assoc :current-mine :b)
   (rf/dispatch [:primary-ui/import-query "a" query-xml "true"])
   (is (nil? (:opened-results @rf-db/app-db)))
   (is (nil? (:opened-builder @rf-db/app-db)))))

(deftest invalid-link-has-a-persistent-safe-message
  (run-test-sync
   (setup-query-entry!)
   (rf/dispatch [:primary-ui/import-query "a" "<query password='private-value'" "true"])
   (is (nil? (:opened-results @rf-db/app-db)))
   (let [message (first (vals (:messages @rf-db/app-db)))]
     (is (= "warning" (:style message)))
     (is (= 0 (:timeout message)))
     (is (not (re-find #"private-value" (pr-str message)))))))

(deftest invalid-link-hides-the-previous-draft-without-deleting-it
  (run-test-sync
   (setup-query-entry!)
   (swap! rf-db/app-db assoc :qb {:im-query {:from "Employee" :select ["Employee.id"]}})
   (rf/dispatch [:primary-ui/import-query "a" "<invalid/>" "true"])
   (is (= :a (get-in @rf-db/app-db [:primary-ui/query-error :mine])))
   (is (= ["Employee.id"] (get-in @rf-db/app-db [:qb :im-query :select])))
   (rf/dispatch [:primary-ui/import-query "a" query-xml nil])
   (is (nil? (:primary-ui/query-error @rf-db/app-db)))))

(defn mine-db [version model]
  {:current-mine :a
   :assets {:web-service-version {:a version}}
   :mines {:a {:service {:root "https://a.example/mine" :model model}}}})

(defn model-class [name attributes references]
  {:name name
   :attributes (into {} (map (fn [field] [(keyword field) {:name field :type "java.lang.String"}]) attributes))
   :references (into {} (map (fn [[field type]] [(keyword field) {:name field :referencedType type}]) references))
   :collections {}})

(def genomic-model
  {:name "genomic"
   :classes {:Organism (model-class "Organism" ["name" "taxonId" "species" "shortName" "genus" "commonName"] {})
             :SequenceFeature (model-class "SequenceFeature" ["id" "name" "primaryIdentifier" "symbol"]
                                           {"chromosomeLocation" "Location" "organism" "Organism"})
             :Location (model-class "Location" ["start" "end" "strand"] {"locatedOn" "Chromosome"})
             :Chromosome (model-class "Chromosome" ["primaryIdentifier"] {"organism" "Organism"})}})

(deftest capabilities-distinguish-api-and-metadata-states
  (doseq [[version status] [[26 :unsupported-version] [27 :ready] [36 :ready]
                            ["27" :ready] [nil :missing-version] ["" :missing-version]
                            ["unknown" :invalid-version] [27.5 :invalid-version] [true :invalid-version]]]
    (is (= status (:status (primary/capabilities (mine-db version employee-model) :a)))))
  (is (= :missing-model (:status (primary/capabilities (mine-db 36 nil) :a))))
  (is (= :missing-model (:status (primary/capabilities (mine-db 36 {:classes {}}) :a))))
  (is (false? (:oauth? (primary/capabilities (mine-db 27 employee-model) :a))))
  (is (true? (:oauth? (primary/capabilities (mine-db 36 employee-model) :a))))
  (doseq [failed [:assets/fetch-model :assets/fetch-web-service-version]]
    (is (= :metadata-failed (:status (primary/capabilities
                                      (assoc-in (mine-db 36 employee-model) [:failed-assets :a] [failed]) :a))))))

(deftest biological-capabilities-require-real-model-paths
  (is (false? (:regions? (primary/capabilities (mine-db 36 employee-model) :a))))
  (is (false? (:organisms? (primary/capabilities (mine-db 36 employee-model) :a))))
  (is (true? (:regions? (primary/capabilities (mine-db 36 genomic-model) :a))))
  (is (true? (:organisms? (primary/capabilities (mine-db 36 genomic-model) :a))))
  (is (false? (:regions? (primary/capabilities
                          (mine-db 36 (update-in genomic-model [:classes :Location :attributes] dissoc :start)) :a))))
  (is (false? (:regions? (primary/capabilities (mine-db 26 genomic-model) :a)))))

(deftest generic-mine-has-no-biological-network-requests
  (run-test-sync
   (reset! rf-db/app-db (assoc (mine-db 36 employee-model) :regions {:to-search "chr1:1..20"}))
   (let [requests (atom [])]
     (with-redefs [fetch/rows (fn [& args] (swap! requests conj args) ((utils/stub-fetch-fn {:results []})))
                   fetch/records (fn [& args] (swap! requests conj args) ((utils/stub-fetch-fn {:results []})))]
       (rf/dispatch [:cache/fetch-organisms])
       (rf/dispatch [:regions/fetch-organisms])
       (rf/dispatch [:regions/run-query])
       (is (empty? @requests))))
   (with-redefs [route/href (constantly "/a/querybuilder")]
     (is (re-find #"Regions unavailable" (pr-str (primary/regions-unavailable-panel)))))))

(deftest capabilities-follow-the-selected-mine
  (run-test-sync
   (reset! rf-db/app-db (-> (mine-db 36 genomic-model)
                            (assoc-in [:assets :web-service-version :b] 27)
                            (assoc-in [:mines :b :service :model] employee-model)))
   (is (:regions? @(rf/subscribe [:primary-ui/capabilities])))
   (swap! rf-db/app-db assoc :current-mine :b)
   (is (false? (:regions? @(rf/subscribe [:primary-ui/capabilities]))))
   (is (= :ready (:status @(rf/subscribe [:primary-ui/capabilities]))))))

(deftest genomic-mine-fetches-organisms-and-ignores-stale-delivery
  (run-test-sync
   (reset! rf-db/app-db (mine-db 36 genomic-model))
   (let [queries (atom [])]
     (with-redefs [fetch/rows (fn [_ query & _]
                                (swap! queries conj query)
                                ((utils/stub-fetch-fn {:results []})))]
       (rf/dispatch [:cache/fetch-organisms])
       (rf/dispatch [:regions/fetch-organisms])
       (is (= ["Organism" "Chromosome"] (mapv :from @queries)))))
   (swap! rf-db/app-db assoc :current-mine :b :cache {} :regions {})
   (rf/dispatch [:cache/store-organisms :a "https://a.example/mine" {:results [{:name "old"}]}])
   (rf/dispatch [:regions/fetch-organisms-success :a "https://a.example/mine" {:results [["old"]]}])
   (is (nil? (get-in @rf-db/app-db [:cache :organisms])))
   (is (nil? (get-in @rf-db/app-db [:regions :organisms])))))
