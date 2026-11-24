(ns bluegenes.primary-ui-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame.test :refer-macros [run-test-sync]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
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
