(ns oteint.dynamics-test
  (:require [clojure.test :refer [deftest is testing]]
            [oteint.kernels.dynamics :as dyn]
            [oteint.facts :as facts]))

(deftest decay-is-bounded-and-positive
  (testing "decay-factor ∈ (0,1], larger τ ⇒ slower decay"
    (is (< 0.95 (dyn/decay-factor 1 24) 0.97))      ; ≈0.959
    (is (< (dyn/decay-factor 1 4) (dyn/decay-factor 1 24))))
  (testing "decay-suspicion never goes negative and shrinks"
    (is (< (dyn/decay-suspicion 0.5 1 24) 0.5))
    (is (>= (dyn/decay-suspicion 0.0 1 24) 0.0))))

(deftest evidence-weight-halves-over-one-half-life
  (testing "weight at age 0 == w0; at age==half-life == w0/2"
    (is (= 0.5 (dyn/evidence-weight-at 0.5 0 36)))
    (is (< 0.248 (dyn/evidence-weight-at 0.5 36 36) 0.252)))
  (testing "zero half-life ⇒ no decay (permanent marker)"
    (is (= 1.0 (dyn/evidence-weight-at 1.0 9999 0)))))

(deftest b2-observation-depth-ramps-and-caps
  (let [p (:obs-depth facts/default-params)] ; floor .1, onset .3, warrant .7
    (testing "below observe-onset ⇒ floor (no dragnet on the unsuspicious)"
      (is (< (dyn/observation-depth 0.0 p) 0.11))
      (is (< (dyn/observation-depth 0.29 p) 0.11)))
    (testing "at/above warrant-threshold ⇒ capped at 1.0"
      (is (= 1.0 (dyn/observation-depth 0.7 p)))
      (is (= 1.0 (dyn/observation-depth 0.99 p))))
    (testing "monotonic non-decreasing in suspicion"
      (is (apply <= (map #(dyn/observation-depth % p) [0.0 0.3 0.5 0.7 1.0]))))))

(deftest inflow-is-severity-times-novelty-times-base-rate
  (is (= 0.6 (dyn/inflow-suspicion {:severity 1.0 :novelty 1.0} 0.6)))
  (is (= 0.0 (dyn/inflow-suspicion {:severity 0.0 :novelty 1.0} 0.6))))

(deftest coherence-eases-toward-signal-low-pass
  (testing "single signal spike steps only `easing` of the way (low-pass)"
    (is (= 0.25 (dyn/coherence-after 0.0 1.0 0.25)))        ; 0*0.75 + 1*0.25
    (is (< (dyn/coherence-after 0.0 1.0 0.25) 0.5)))        ; below the 0.5 floor
  (testing "repeated signal converges toward 1.0"
    (let [final (nth (iterate #(dyn/coherence-after % 1.0 0.25) 0.0) 20)]
      (is (> final 0.99)))))

(deftest b1-correction-only-on-weak-high-suspicion-cases
  (let [p (:fp-correction facts/default-params)] ; suspect-hi .6, coh-lo .35, exp-lo 1.0
    (testing "high suspicion + low coherence + low exposure ⇒ positive drain (冤罪 guard)"
      (is (pos? (dyn/fp-correction-outflow 0.8 0.1 0.5 p))))
    (testing "high-impact case (exposure high) ⇒ NO drain even if low coherence"
      (is (zero? (dyn/fp-correction-outflow 0.8 0.1 50.0 p))))
    (testing "coherent case ⇒ NO drain"
      (is (zero? (dyn/fp-correction-outflow 0.8 0.9 0.5 p))))
    (testing "low suspicion ⇒ NO drain"
      (is (zero? (dyn/fp-correction-outflow 0.2 0.1 0.5 p))))))
