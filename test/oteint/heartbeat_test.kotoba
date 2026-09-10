(ns oteint.heartbeat-test
  (:require [clojure.test :refer [deftest is testing]]
            [oteint.operation :as op]
            [oteint.sim :as sim]
            [oteint.advisor :as advisor]
            [oteint.heartbeat :as hb]))

(defn ev [entity kind] (op/->ActivityEvent entity kind nil 1.0 1 nil))

(def offline-corpus
  "Operator-staged OFFLINE corpus for the self-audit (not live data)."
  [(ev "m" :auth/credential-stuffing)
   (ev "m" :net/c2-beacon)
   (ev "b" :auth/valid-mfa)])

(deftest self-audit-is-always-dry-run-and-clear-on-clean-corpus
  (let [appended (atom [])
        rec (hb/self-audit offline-corpus
                           (fn [e] (sim/init-stock e))
                           (advisor/mock-advisor)
                           (fn [r] (swap! appended conj r)))]
    (testing "always Phase 0 dry-run (no case ever supplied)"
      (is (true? (:dry-run rec))))
    (testing "clean corpus ⇒ all counters zero ⇒ not halted"
      (is (false? (:halted? rec)))
      (is (empty? (:violations rec))))
    (testing "exactly ONE audit record appended, holding ONLY counters"
      (is (= 1 (count @appended)))
      (let [r (first @appended)]
        (is (:counters r))
        ;; the audit log holds counters ONLY — never observation/PII/case data
        (is (not (contains? r :stocks)))
        (is (not (contains? r :events)))
        (is (not (contains? r :proposals)))))))

(deftest self-audit-never-attempts-live-attribution
  (testing "even a malicious-pattern corpus stays dry-run (no live attribution)"
    (let [appended (atom [])
          rec (hb/self-audit (repeat 25 (ev "m" :file/mass-encrypt))
                             (fn [e] (sim/init-stock e))
                             (advisor/mock-advisor)
                             (fn [r] (swap! appended conj r)))]
      (is (true? (:dry-run rec)))
      ;; dry-run ⇒ no live write ⇒ :noncase-write stays 0; the audit log is the
      ;; only thing written, and it holds counters only
      (is (zero? (get-in rec [:counters :noncase-write] 1))))))

(deftest empty-corpus-still-audits-cleanly
  (let [appended (atom [])
        rec (hb/self-audit [] (fn [e] (sim/init-stock e))
                           (advisor/mock-advisor)
                           (fn [r] (swap! appended conj r)))]
    (is (false? (:halted? rec)))
    (is (= 1 (count @appended)))))
