(ns oteint.advisor-llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [oteint.advisor-llm :as al]
            [oteint.advisor :as advisor]
            [oteint.operation :as op]
            [oteint.sim :as sim]
            [oteint.facts :as facts]))

(defn attributable-stock
  "A stock that clears the registry's ready-to-attribute? floors."
  [entity]
  (-> (sim/init-stock entity)
      (assoc :suspicion 0.9 :coherence 0.9 :last-tick 100
             :evidence (repeat 4 (op/->EvidenceItem 0.5 36.0 100
                                                    :auth/credential-stuffing nil)))))

(defn mock-call-fn
  "Returns a call-fn that ignores messages and returns a canned chat-completions
  body proposing :actor/credential-abuser for entity 'm'."
  []
  (let [canned (json/write-str
                 {:choices
                  [{:message
                    {:content
                     "[{\"entity\":\"m\",\"cluster\":\"actor/credential-abuser\",\"confidence\":0.92}]"}}]})]
    (fn [_messages] canned)))

(deftest llm-advisor-enriches-attributable-stocks-and-stays-propose-only
  (testing "the LLM enriches attributable stocks; proposals are :attribute"
    (let [adv     (al/murakumo-advisor (mock-call-fn))
          stocks  [(attributable-stock "m") (sim/init-stock "b")]
          props   (advisor/advise adv stocks facts/default-params)
          attr    (filter #(= :attribute (:kind %)) props)
          observe (filter #(= :observe-deeper (:kind %)) props)]
      (is (some #(= :actor/credential-abuser (:actor-cluster %)) attr))
      (is (some #(= "m" (:entity %)) attr))
      (is (some #(= "b" (:entity %)) observe)))))

(deftest llm-advisor-falls-back-to-unknown-cluster-on-parse-failure
  (testing "if the LLM body is unparseable, the attributable stock still gets a :attribute proposal with :actor/unknown"
    (let [props (al/llm-advise (fn [_] "not json at all")
                               [(attributable-stock "m")] facts/default-params)
          attr  (filter #(= :attribute (:kind %)) props)]
      (is (some #(= :actor/unknown (:actor-cluster %)) attr)))))
