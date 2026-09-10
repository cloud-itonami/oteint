(ns oteint.dev-test-runner
  "Phase-2 test runner: pure kernels + langgraph StateGraph + langchain-store
  :db-api store + murakumo-main advisor + CACAO identity. Run via `clojure -M:phase2`."
  (:require [clojure.test :refer [run-tests]]
            [oteint.dynamics-test]
            [oteint.charter-test]
            [oteint.governor-test]
            [oteint.sim-test]
            [oteint.heartbeat-test]
            [oteint.graph-test]
            [oteint.store-datomic-test]
            [oteint.advisor-llm-test]
            [oteint.identity-test])
  (:gen-class))

(defn -main [& _]
  (let [res (run-tests
              'oteint.dynamics-test
              'oteint.charter-test
              'oteint.governor-test
              'oteint.sim-test
              'oteint.heartbeat-test
              'oteint.graph-test
              'oteint.store-datomic-test
              'oteint.advisor-llm-test
              'oteint.identity-test)]
    (flush)
    (System/exit (if (or (pos? (:fail res 0)) (pos? (:error res 0))) 1 0))))
