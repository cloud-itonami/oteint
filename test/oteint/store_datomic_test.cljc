(ns oteint.store-datomic-test
  (:require [clojure.test :refer [deftest is testing]]
            [oteint.store :as store]
            [oteint.store-datomic :as ds]
            [oteint.sim :as sim]))

(deftest datomic-store-round-trips-a-stock
  (testing "put-stock! then get-stock returns the same logical stock (MemStore ≡ DatomicStore)"
    (let [conn  (ds/datomic-store)
          stock (assoc (sim/init-stock "x")
                       :suspicion 0.7 :coherence 0.6 :exposure-cost 12.0)]
      (is (identical? stock (store/put-stock! conn stock)))
      (let [got (store/get-stock conn "x")]
        (is (= "x" (:entity got)))
        (is (= 0.7 (:suspicion got)))
        (is (= 0.6 (:coherence got)))))))

(deftest datomic-store-event-stream-and-ledger
  (testing "append-blob event stream + ledger, filtered by entity"
    (let [conn (ds/datomic-store)]
      (store/append-event! conn {:seq 1 :entity "x" :kind :auth/credential-stuffing})
      (store/append-event! conn {:seq 2 :entity "x" :kind :net/c2-beacon})
      (store/append-event! conn {:seq 3 :entity "y" :kind :auth/valid-mfa})
      (is (= 2 (count (store/stream-events conn "x"))))
      (is (= 1 (count (store/stream-events conn "y"))))
      (store/append-ledger! conn {:seq 1 :event :oteint/govern :decision :human})
      (store/append-ledger! conn {:seq 2 :event :oteint/self-audit :halted? false})
      (is (= 2 (count (store/ledger conn)))))))
