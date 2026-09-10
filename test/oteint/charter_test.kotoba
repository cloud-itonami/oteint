(ns oteint.charter-test
  (:require [clojure.test :refer [deftest is testing]]
            [oteint.kernels.charter :as ch]))

(def valid-case
  (ch/->Case "case-1" "auth-ref-1" {:allow-named-individual false} 1000000))

(def named-case
  (ch/map->Case {:case-id "case-2" :authorization-ref "auth-2"
                 :scope {:allow-named-individual true} :expires 1000000}))

(defn ctx [m]
  (merge {:case valid-case :now 100 :store-kind :kotoba :inference-gateway nil
          :had-live-write false :had-pii-write false :pii-encrypted? true
          :had-enforcement-action false :used-platform-key false
          :max-obs-depth 0.0 :allowed-obs-depth 1.0
          :attributed-named-indiv? false}
         m))

(deftest mandate-validity
  (is (true? (ch/mandate-valid? valid-case 100)))
  (is (false? (ch/mandate-valid? (assoc valid-case :authorization-ref nil) 100)))
  (is (false? (ch/mandate-valid? (assoc valid-case :expires 50) 100))) ; expired
  (is (false? (ch/mandate-valid? nil 100)))
  (is (true? (ch/dry-run? nil 100)))
  (is (false? (ch/dry-run? valid-case 100))))

(deftest adherent-deanon-gate
  (is (false? (ch/adherent-deanon-allowed? valid-case)))
  (is (true?  (ch/adherent-deanon-allowed? named-case))))

(deftest happy-path-all-clear
  (let [c (ch/compute-counters (ctx {:had-live-write true :had-pii-write true
                                     :pii-encrypted? true :store-kind :kotoba}))]
    (is (ch/all-clear? c))
    (is (false? (ch/g12-halt? c)))
    (is (empty? (ch/violations c)))))

(deftest each-counter-fires-independently
  (testing "noncase-write: live write without a valid case"
    (is (some? (some #{:noncase-write}
                     (ch/violations (ch/compute-counters (ctx {:case nil :had-live-write true})))))))
  (testing "plaintext-pii"
    (is (some? (some #{:plaintext-pii}
                     (ch/violations (ch/compute-counters (ctx {:had-pii-write true :pii-encrypted? false})))))))
  (testing "enforcement-action (oteint never enforces)"
    (is (some? (some #{:enforcement-action}
                     (ch/violations (ch/compute-counters (ctx {:had-enforcement-action true})))))))
  (testing "platform-held-key"
    (is (some? (some #{:platform-held-key}
                     (ch/violations (ch/compute-counters (ctx {:used-platform-key true})))))))
  (testing "murakumo-bypass"
    (is (some? (some #{:murakumo-bypass}
                     (ch/violations (ch/compute-counters (ctx {:inference-gateway :vendor})))))))
  (testing "mass-surveillance: obs depth exceeds B2 bound"
    (is (some? (some #{:mass-surveillance}
                     (ch/violations (ch/compute-counters (ctx {:max-obs-depth 1.0 :allowed-obs-depth 0.1})))))))
  (testing "non-kotoba-store: live write to a mem store"
    (is (some? (some #{:non-kotoba-store}
                     (ch/violations (ch/compute-counters (ctx {:had-live-write true :store-kind :mem}))))))))

(deftest adherent-deanon-counter-respects-case-scope
  (testing "naming a real individual WITHOUT scope auth ⇒ violation (G12 halt)"
    (let [c (ch/compute-counters (ctx {:attributed-named-indiv? true}))]
      (is (ch/g12-halt? c))
      (is (some #{:adherent-deanon} (ch/violations c)))))
  (testing "naming a real individual WITH scope auth ⇒ clear"
    (let [c (ch/compute-counters (ctx {:case named-case :attributed-named-indiv? true}))]
      (is (ch/all-clear? c)))))

(deftest g12-halt-on-any-nonzero
  (is (true?  (ch/g12-halt? {:noncase-write 1 :enforcement-action 0})))
  (is (false? (ch/g12-halt? {:noncase-write 0 :enforcement-action 0}))))
