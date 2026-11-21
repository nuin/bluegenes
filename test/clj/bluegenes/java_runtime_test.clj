(ns bluegenes.java-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [bluegenes.java-runtime :as runtime]))

(deftest java21-floor
  (testing "Java 21 and newer are accepted"
    (doseq [version ["21" "25"]]
      (is (nil? (runtime/require-java21! version)))))
  (testing "Older JVMs fail with an actionable message"
    (doseq [version ["1.8" "17" "20"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"BlueGenes requires Java 21 or newer"
                            (runtime/require-java21! version)))))
  (testing "The running JVM is supported"
    (is (nil? (runtime/require-java21!)))))
