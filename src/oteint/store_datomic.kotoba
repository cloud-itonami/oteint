(ns oteint.store-datomic
  "DatomicStore — a langchain.db `:db-api`-backed Store (in-process Datomic via
  langchain.db, or kotoba-server via langchain.kotoba-db/kotoba-api). Implements
  the SAME oteint.store/Store protocol as MemStore; contract MemStore ≡
  DatomicStore. Uses kotoba-lang/langchain-store for the EDN-blob codec,
  identity schema, and seq-keyed event-stream read/append — no hand-rolled
  duplication (ADR-2607141600). See ADR-2607182000 §7 + build-actor.

  Requires langchain + langchain-store (deps.edn :dev / :phase2). The pure-kernel
  :test path (which uses oteint.store/MemStore) never loads this namespace."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]
            [oteint.store :refer [Store]]))

;; ---------- schema + field specs ----------

(def schema
  "Datomic schema for oteint's three entity kinds:
    stock-snapshot (per-entity, keyed by :oteint.stock/entity),
    event (activity stream, seq-keyed EDN blob),
    ledger (audit counters/verdicts, seq-keyed EDN blob).
  PII would go under :oteint.encrypted/* (never plaintext) — the plaintext-pii
  zero-counter enforces this; blueprint stores no PII."
  {:oteint.stock/entity        {:db/unique :db.unique/identity}
   :oteint.stock/suspicion     {}
   :oteint.stock/coherence     {}
   :oteint.stock/evidence      {}
   :oteint.stock/exposure-cost {}
   :oteint.stock/max-obs-depth {}
   :oteint.stock/last-tick     {}
   :oteint.event/seq           {:db/unique :db.unique/identity}
   :oteint.event/entity        {}
   :oteint.event/edn           {}
   :oteint.ledger/seq          {:db/unique :db.unique/identity}
   :oteint.ledger/edn          {}})

(def stock-spec
  "field-spec for StockSnapshot ↔ datom tx (langchain-store map<->tx)."
  {:entity        {:attr :oteint.stock/entity        :blob? false}
   :suspicion     {:attr :oteint.stock/suspicion     :blob? false :coerce double}
   :coherence     {:attr :oteint.stock/coherence     :blob? false :coerce double}
   :evidence      {:attr :oteint.stock/evidence      :blob? true  :default []}
   :exposure-cost {:attr :oteint.stock/exposure-cost :blob? false :coerce double}
   :max-obs-depth {:attr :oteint.stock/max-obs-depth :blob? false :coerce double}
   :last-tick     {:attr :oteint.stock/last-tick     :blob? false :coerce long}})

;; ---------- DatomicStore (Store protocol, same as MemStore) ----------

(defrecord DatomicStore [conn]
  Store
  (get-stock [_ entity]
    (when-let [pulled (d/pull (d/db conn) (ls/pull-pattern stock-spec)
                              [:oteint.stock/entity entity])]
      (ls/pull->map stock-spec :entity pulled)))
  (put-stock! [_ stock]
    (d/transact! conn [(ls/map->tx stock-spec stock)])
    stock)
  (append-event! [_ event]
    (ls/append-blob! conn :oteint.event/seq :oteint.event/edn (:seq event) event)
    event)
  (append-ledger! [_ entry]
    (ls/append-blob! conn :oteint.ledger/seq :oteint.ledger/edn (:seq entry) entry)
    entry)
  (stream-events [_ entity]
    (filter #(= entity (:entity %))
            (ls/read-stream conn :oteint.event/seq :oteint.event/edn)))
  (ledger [_]
    (ls/read-stream conn :oteint.ledger/seq :oteint.ledger/edn)))

(defn datomic-store
  "In-process Datomic-backed Store (langchain.db/create-conn). For kotoba-server
  (prod), build a DatomicStore over a kotoba-conn + kotoba-api :db-api instead."
  ([] (->DatomicStore (d/create-conn schema))))
