(ns oteint.sim-test
  (:require [clojure.test :refer [deftest is testing]]
            [oteint.operation :as op]
            [oteint.sim :as sim]
            [oteint.registry :as reg]
            [oteint.advisor :as advisor]
            [oteint.phase :as phase]
            [oteint.store :as store]))

(defn ev [entity kind] (op/->ActivityEvent entity kind nil 1.0 1 nil))

(def malicious-events
  "A genuine high-impact intrusion pattern (per tick): credential stuffing +
  C2 beaconing + ransomware-style mass encryption. These raise BOTH suspicion
  and exposure-cost, so the B1 冤罪-guard must NOT fire."
  [(ev "m" :auth/credential-stuffing)
   (ev "m" :net/c2-beacon)
   (ev "m" :file/mass-encrypt)])

(def benign-events
  "Normal daily activity: valid MFA, ordinary file/api access."
  [(ev "b" :auth/valid-mfa)
   (ev "b" :file/normal-open)
   (ev "b" :api/normal-read)])

(defn run-ticks
  "Feed the same event batch each tick for n ticks. Pure."
  [events n]
  (let [entity (:entity (first events))]
    (loop [stk (sim/init-stock entity) i 0]
      (if (>= i n) stk
          (recur (sim/tick stk events) (inc i))))))

(deftest malicious-actor-converges-to-attributable
  (let [final (run-ticks malicious-events 20)]
    (testing "suspicion climbs high (R1 reinforcement, bounded by B2)"
      (is (> (:suspicion final) 0.6)))
    (testing "coherence matches a known TTP profile"
      (is (> (:coherence final) 0.5)))
    (testing "the engine CONVERGES: ready-to-attribute? becomes true"
      (is (reg/ready-to-attribute? final)))
    (testing "the advisor proposes an :attribute (closed-allowlist kind)"
      (let [p (advisor/propose final)]
        (is (= :attribute (:kind p)))
        (is (some? (:actor-cluster p)))))))

(deftest benign-actor-stays-below-attribution
  (let [final (run-ticks benign-events 20)]
    (testing "low-severity daily activity keeps suspicion near zero"
      (is (< (:suspicion final) 0.3)))
    (testing "never attributable"
      (is (not (reg/ready-to-attribute? final))))
    (testing "advisor only proposes observing more (no accusation)"
      (is (#{:observe-deeper :release-focus} (:kind (advisor/propose final)))))))

(deftest suspicion-is-monotonic-rising-under-sustained-malice
  (let [trajectory (take 8 (iterate #(sim/tick % malicious-events)
                                    (sim/init-stock "m")))
        suspicions (map :suspicion trajectory)]
    (testing "suspicion is non-decreasing early on (inflow > decay)"
      (is (apply <= suspicions)))))

(deftest b3-evidence-expires-without-recurrence
  (testing "太陽は恨みを持たない: after buildup, many idle ticks decay
  suspicion + evidence → the entity is released from focus (not accused forever)"
    (let [built (run-ticks malicious-events 15)
          idle  (loop [stk built i 0]
                  (if (>= i 400) stk
                      (recur (sim/tick stk []) (inc i))))]
      (is (not (reg/evidence-sufficient? idle)))
      (is (reg/release-focus? idle))
      (is (< (:suspicion idle) 0.05)))))

(deftest run-tick-orchestrates-the-full-node-sequence
  (testing "ingest → simulate → analyze → govern → commit, all pure"
    (let [st (store/mem-store)
          stock-of (fn [e] (or (store/get-stock st e) (sim/init-stock e)))
          result (phase/run-tick malicious-events stock-of
                                  (advisor/mock-advisor)
                                  (store/append-ledger-fn st))]
      (testing "stocks advanced"
        (is (contains? (:stocks result) "m"))
        (is (> (:suspicion (get (:stocks result) "m")) 0.0)))
      (testing "on tick 1 the entity is not yet attributable ⇒ observe-deeper approved"
        (let [approved (map first (:approved (:governor result)))]
          (is (seq approved))
          (is (some #(= :observe-deeper (:kind %)) approved))))
      (testing "the audit ledger recorded the governor pass (太陽はすべてを見ている)"
        (is (pos? (count (store/ledger st))))))))
