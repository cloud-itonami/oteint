(ns oteint.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [oteint.operation :as op]
            [oteint.kernels.govern-verdict :as gv]
            [oteint.governor :as governor]
            [oteint.facts :as facts]))

(defn stock
  ([entity] (stock entity {}))
  ([entity {:keys [suspicion coherence exposure max-depth]
            :or {suspicion 0.8 coherence 0.9 exposure 10.0 max-depth 1.0}}]
   (op/->StockSnapshot entity suspicion coherence [] exposure max-depth 100)))

(deftest closed-allowlist-rejects-unknown-kinds
  (is (not (:ok? (gv/allowlist-ok? (op/->Proposal :disclose "e" nil 0.9 [] 0.9)))))
  (is (:ok? (gv/allowlist-ok? (op/->Proposal :attribute "e" nil 0.9 [] 0.9))))
  (is (:ok? (gv/allowlist-ok? (op/->Proposal :observe-deeper "e" nil 0.9 [] 0.9)))))

(deftest evidence-floor-rejects-thin-evidence
  (is (:ok? (gv/evidence-floor-ok? nil 5 2.0 {:min-count 3 :min-weight 1.0})))
  (is (not (:ok? (gv/evidence-floor-ok? nil 2 2.0 {:min-count 3 :min-weight 1.0}))))
  (is (not (:ok? (gv/evidence-floor-ok? nil 5 0.5 {:min-count 3 :min-weight 1.0})))))

(deftest coherence-floor-rejects-pattern-only
  (is (:ok? (gv/coherence-floor-ok? 0.9 0.5)))
  (is (not (:ok? (gv/coherence-floor-ok? 0.2 0.5)))))

(deftest proportionality-audit-rejects-tainted-dragnet-evidence
  (let [p facts/default-params]
    (is (:ok? (gv/proportionality-audit-ok? 0.1 0.05 p)))  ; minimal depth on low suspicion
    ;; suspicion 0.05 allows only floor-depth (~0.1); using depth 1.0 is dragnet
    (is (not (:ok? (gv/proportionality-audit-ok? 1.0 0.05 p))))))

(deftest false-positive-guard-holds-on-weak-high-severity-case
  (let [p (:fp facts/default-params)
        weak    (op/->Proposal :attribute "e" nil 0.9 [] 0.9) ; severity 0.9
        strong  (op/->Proposal :attribute "e" nil 0.9 [] 0.3)]
    (is (= :hold (:ok? (gv/false-positive-guard weak 0.1 0.5 p))))   ; low coh + low exposure
    (is (:ok?    (gv/false-positive-guard strong 0.9 50.0 p)))))      ; high coh or high exposure ⇒ pass

(deftest disclosure-guard-human-gates-every-attribution
  (is (= :human (:ok? (gv/disclosure-guard (op/->Proposal :attribute "e" nil 0.9 [] 0.9)))))
  (is (:ok? (gv/disclosure-guard (op/->Proposal :observe-deeper "e" nil 0.9 [] 0.9)))))

(deftest check-precedence
  (let [p facts/default-params
        good-stock (stock "e")]
    (testing "unknown kind ⇒ reject (allowlist beats everything)"
      (is (= :reject (:decision (gv/check (op/->Proposal :block "e" nil 0.9 [] 0.9)
                                          good-stock 5 2.0 p)))))
    (testing "insufficient evidence ⇒ reject even for :attribute"
      (is (= :reject (:decision (gv/check (op/->Proposal :attribute "e" nil 0.9 [] 0.9)
                                          good-stock 1 0.1 p)))))
    (testing "tainted evidence (dragnet) ⇒ reject"
      (is (= :reject (:decision (gv/check (op/->Proposal :attribute "e" nil 0.9 [] 0.9)
                                          (stock "e" {:suspicion 0.05 :max-depth 1.0})
                                          5 2.0 p)))))
    (testing "clean :observe-deeper passing all floors ⇒ approve"
      (is (= :approve (:decision (gv/check (op/->Proposal :observe-deeper "e" nil 0.8 [] 0.8)
                                           good-stock 5 2.0 p)))))
    (testing ":attribute passing all floors ⇒ HUMAN (disclosure-guard, interrupt-before)"
      (is (= :human (:decision (gv/check (op/->Proposal :attribute "e" nil 0.8 [] 0.8)
                                         good-stock 5 2.0 p)))))))

(deftest governor-land-partitions-and-commits-to-ledger
  (testing "every proposal+verdict is appended to the audit ledger (太陽はすべてを見る)"
    (let [ledger (atom [])
          append (fn [e] (swap! ledger conj e))
          ev-item (fn [w] (op/->EvidenceItem w 36.0 100 :auth/credential-stuffing nil))
          rich-stock (op/->StockSnapshot "e" 0.8 0.9
                                         [(ev-item 0.5) (ev-item 0.5) (ev-item 0.5)]
                                         10.0 1.0 100)
          proposal (op/->Proposal :attribute "e" :actor/ransomware-operator 0.85 [] 0.85)
          result (governor/land [proposal] (fn [_] rich-stock) append)]
      (is (= 1 (count @ledger)))                      ; committed even though ⇒ human
      (is (seq (:human result)))
      ;; :human is a list of [proposal verdict] pairs
      (is (= :human (:decision (second (first (:human result)))))))))
