(ns oteint.store
  "Event-sourced store for oteint. The store holds (a) the raw activity stream
  and (b) the per-entity stock snapshots and (c) the append-only audit ledger
  of every proposal + Governor verdict (『太陽はすべてを見ている』— the ledger is
  the permanence).

  The store talks to its backend EXCLUSIVELY through the `:db-api` map
  {:q :transact! :db :pull :entid} (langchain.db shape), so the same record runs
  against in-process langchain.db / real Datomic / kotoba-server XRPC unchanged
  (build-actor, contract-tested MemStore ≡ DatomicStore). Common event-sourcing
  mechanics (EDN-blob codec, identity schema, seq-keyed append) come from
  kotoba-lang/langchain-store — do NOT hand-roll them (ADR-2607141600).

  ⚠ langchain-store + langchain.db are :dev deps (deps.edn :dev override). They
  are NOT required to load this namespace's MemStore, which is a pure in-memory
  impl used for blueprint maturity + tests. Phase 2 wires the real :db-api
  backend by providing a Store that delegates to langchain.db/kotoba-db."
  (:require [oteint.sim :as sim]))

(defprotocol Store
  "The store contract. Same operations regardless of backend
  (in-process MemStore / Datomic / kotoba-server via :db-api)."
  (get-stock [this entity]      "Current StockSnapshot for entity, or nil.")
  (put-stock! [this stock]      "Upsert a stock snapshot.")
  (append-event! [this event]   "Append an ActivityEvent to the raw stream.")
  (append-ledger! [this entry]  "Append a {:event :proposal :verdict} entry to the
                                 append-only audit ledger. Returns the entry.")
  (stream-events [this entity]  "ActivityEvents observed for entity, in order.")
  (ledger [this]                "Snapshot of the audit ledger (seq)."))

(defrecord MemStore [state-atom]
  Store
  (get-stock [_ entity] (get (:stocks @state-atom) entity))
  (put-stock! [_ stock]
    (swap! state-atom assoc-in [:stocks (:entity stock)] stock)
    stock)
  (append-event! [_ event]
    (swap! state-atom update-in [:events (:entity event)] (fnil conj []) event)
    event)
  (append-ledger! [_ entry]
    (let [e (assoc entry :seq (count (:ledger @state-atom)))]
      (swap! state-atom update :ledger conj e)
      e))
  (stream-events [_ entity] (get-in @state-atom [:events entity] []))
  (ledger [_] (seq (:ledger @state-atom))))

(defn mem-store
  "Pure in-memory store for tests/blueprint. Backend swap target (Phase 2):
  a Store that delegates to langchain.db `:db-api` (in-process) or
  langchain.kotoba-db/kotoba-api (kotoba-server XRPC)."
  ([] (->MemStore (atom {:stocks {} :events {} :ledger []}))))

;; ---------- convenience: drive the store into the phase/run-tick shape ----------

(defn stock-of-fn
  "Build the entity->StockSnapshot lookup a tick needs, defaulting new entities
  to a fresh stock."
  [store]
  (fn [entity] (or (get-stock store entity) (sim/init-stock entity))))

(defn append-ledger-fn
  "Build the ledger-append fn a tick needs, bound to this store."
  [store]
  (fn [entry] (append-ledger! store entry)))
