(ns bluegenes.sections.reportpage.views
  (:require [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [reagent.core :as r]
            [bluegenes.sections.reportpage.components.summary :as summary]
            [bluegenes.components.table :as table]
            [bluegenes.components.lighttable :as lighttable]
            [bluegenes.components.loader :refer [loader]]
            [bluegenes.sections.reportpage.components.minelinks :as minelinks]
            [bluegenes.sections.reportpage.components.tools :as tools]
            [accountant.core :refer [navigate!]]
            [bluegenes.sections.reportpage.subs :as subs]
            [im-tables.views.core :as im-table]
            [imcljs.path :as im-path]))

(defn tbl [{:keys [loc]}]
  (let [data (subscribe [::subs/a-table loc])]
    (r/create-class
     {:component-did-mount (fn [this]
                             (let [{:keys [loc path constraint] :as data} (r/props this)]
                               (dispatch [:im-tables/load loc (dissoc data :loc)])))
      :reagent-render (fn [{:keys [loc title all]}]
                        (fn []
                          (let [result-count (get-in @data [:response :iTotalRecords])]
                            [:div
                             [:h3 (str title (when result-count (str " (" result-count ")")))]
                             (if (= 0 result-count)
                               [:div "No Results"]
                               [:div {:style {:background-color "white"}}
                                [im-table/main loc]])])))})))

(defn strip-class [s]
  (clojure.string/join "." (drop 1 (clojure.string/split s "."))))

(def clj-name name)

(defn collections-and-references [service current-mine-name object-type id]
  (let [summary-fields (subscribe [:current-summary-fields])
        nonempty-collections-references (subscribe [::subs/non-empty-collections-and-references])]
    (into [:div]
          (map (fn [{:keys [name referencedType displayName] :as x}]
                 ^{:key (str id (:name x))}
                 [tbl {:loc [:report-page id (:name x)]
                       :service (:service @service)
                       :title displayName
                       :query {:from object-type
                               :select (map (partial str object-type "." (:name x) ".") (map strip-class (get @summary-fields (keyword referencedType))))
                               :where [{:path (str object-type ".id")
                                        :op "="
                                        :value id}]}
                       :settings {:pagination {:limit 5}
                                  :links {:vocab {:mine (clj-name (or @current-mine-name ""))}
                                          :url (fn [vocab]
                                                 (str "#/reportpage/"
                                                      (:mine vocab) "/"
                                                      (:class vocab) "/"
                                                      (:objectId vocab)))}}}])
               @nonempty-collections-references))))

(defn templates-for-entity [service current-mine-name id]
  (let [runnable-templates (subscribe [::subs/runnable-templates])]
    (into [:div]
          (map (fn [{:keys [name title] :as t}]
                 (let [key nil]
                   [tbl {:loc [:report-page id (:name t)]
                         :service (:service @service)
                         :title title
                         :query t
                         :settings {:pagination {:limit 5}
                                    :links {:vocab {:mine (clj-name (or @current-mine-name ""))}
                                            :url (fn [vocab]
                                                   (str "#/reportpage/"
                                                        (:mine vocab) "/"
                                                        (:class vocab) "/"
                                                        (:objectId vocab)))}}}]))
               @runnable-templates))))

(defn main []
  (let [params (subscribe [:panel-params])
        report (subscribe [:report])
        categories (subscribe [:template-chooser-categories])
        templates (subscribe [:runnable-templates])
        service (subscribe [:current-mine])
        model (subscribe [:current-model])
        current-mine-name (subscribe [:current-mine-name])
        fetching-report? (subscribe [:fetching-report?])]
    (fn []
      [:div.container.report
       (let [{:keys [type id]} @params]
         [:div
            ; Only show the body of the report when the summary has loaded
          (if @fetching-report?
            [loader (str (:type @params) " Report")]
            [:div
             [summary/main (:summary @report)]
             (when (= "Gene" (:type @params)) [minelinks/main (:id @params)])
             (when (:summary @report)
               [:div.report-body
                [collections-and-references service current-mine-name type id]
                [templates-for-entity service current-mine-name id]])])])])))
