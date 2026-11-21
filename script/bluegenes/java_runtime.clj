(ns bluegenes.java-runtime)

(defn require-java21!
  "Reject unsupported JVMs before loading application dependencies."
  ([] (require-java21! (System/getProperty "java.specification.version")))
  ([version]
   (when-not (and (re-matches #"\d+" version)
                  (>= (Long/parseLong version) 21))
     (throw (ex-info (str "BlueGenes requires Java 21 or newer; found Java " version
                          ". Set JAVA_HOME to a supported JDK.")
                     {:java-version version :minimum-version 21})))))

(require-java21!)
