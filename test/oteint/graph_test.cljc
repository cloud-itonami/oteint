(ns oteint.graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [oteint.operation :as op]
            [oteint.sim :as sim]
            [oteint.advisor :as advisor]
            [oteint.graph :as graph]))

(defn ev [entity kind] (op/->ActivityEvent entity kind nil 1.0 1 nil))

(def malicious-events
  [(ev "m" :auth/credential-stuffing)
   (ev "m" :net/c2-beacon)
   (ev "m" :file/mass-encrypt)])

(deftest graph-runs-a-full-tick-end-to-end
  (testing "ingest→simulate→analyze→govern→commit produces stocks/governor/charter/audit"
    (let [audit (atom [])
          input {:events    malicious-events
                 :stock-of  (fn [e] (sim/init-stock e))
                 :advisor   (advisor/mock-advisor)
                 :append-fn (fn [entry] (swap! audit conj entry) entry)
                 :case      nil :now 1}
          result (graph/run input {:interrupt-before #{}})]
      (is (contains? (:stocks result) "m"))
      (is (some? (:governor result)))
      (is (some? (:charter result)))
      (is (seq (:audit result)))
      ;; no case ⇒ dry-run ⇒ nothing persisted (G3)
      (is (false? (:persisted? (:charter result)))))))

(deftest graph-interrupt-pauses-before-govern-then-resumes
  (testing "interrupt-before #{:govern} pauses before the Governor; resume completes"
    (let [graph (graph/build {:interrupt-before #{:govern}})
          input {:events    malicious-events
                 :stock-of  (fn [e] (sim/init-stock e))
                 :advisor   (advisor/mock-advisor)
                 :append-fn (fn [_])
                 :case      nil :now 1}
          paused (g/invoke graph input {:thread-id "oteint-interrupt"})]
      ;; paused before :govern ⇒ :governor not yet produced, but stocks/proposals are
      (is (nil? (:governor paused)))
      (is (contains? (:stocks paused) "m"))
      (let [final (g/invoke graph nil {:thread-id "oteint-interrupt" :resume? true})]
        (is (some? (:governor final)))))))
