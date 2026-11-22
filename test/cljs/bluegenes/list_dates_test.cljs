(ns bluegenes.list-dates-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [bluegenes.pages.lists.views :as views]))

(deftest lists-without-creation-dates-still-render
  (testing "Older public lists may have no creation timestamp"
    (is (= "Date unavailable" (last (views/readable-time nil)))))
  (testing "Invalid timestamps are shown as unavailable"
    (doseq [timestamp [js/NaN js/Infinity 1e20 "invalid"]]
      (is (= "Date unavailable" (last (views/readable-time timestamp))))))
  (testing "List result headers also handle missing or invalid creation dates"
    (doseq [timestamp [nil js/NaN js/Infinity 1e20 "invalid"]]
      (is (= "Date unavailable" (views/pretty-timestamp timestamp)))))
  (testing "The Unix epoch remains a valid date"
    (is (= "1970-01-01 00:00:00" (:data (second (views/readable-time 0)))))))
