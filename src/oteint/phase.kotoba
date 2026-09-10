(ns oteint.phase
  "おてんとさま — single-tick orchestration. 1 run = 1 analysis tick (bounded,
  no internal infinite loop), per build-actor (ADR-2607182000 §5).

  Node sequence:
    :ingest    pull new activity events into the store
    :simulate  oteint.sim/tick advances the SD state for each entity
    :analyze   oteint.advisor turns stock state into Proposals (0..n)
    :govern    (interrupt-before) AttributionGovernor reviews each proposal
    :commit    approved/held/human/rejected verdicts appended to the ledger

  This namespace provides:
    - `nodes` — the declarative graph (data), ready to hand to langgraph-clj
      StateGraph in Phase 2 (deps.edn :dev override adds langgraph-clj).
    - `run-tick` — a pure, dependency-free orchestration of the SAME nodes for
      blueprint maturity + tests. It does NOT require langgraph-clj; when the
      real graph is composed (Phase 2), the node fns below plug in unchanged.

  Continuous surveillance (long-duration) is NOT an internal loop here — it is
  a durable OUTER loop (lease / tick / budget / governor / crash-recovery) that
  drives repeated bounded `run-tick` calls. The run-state keys
  (:agent.tick/* :agent.lease/* :agent.budget/* :agent.event/*) live on the
  datom-plane, not inside the StateGraph (build-actor durable-loop policy)."
  (:require [oteint.sim :as sim]
            [oteint.advisor :as advisor]
            [oteint.governor :as gov]
            [oteint.kernels.charter :as ch]
            [oteint.kernels.dynamics :as dyn]
            [oteint.facts :as facts]))

(def nodes
  "Declarative node list for langgraph-clj StateGraph (Phase 2 composition).
  :interrupt-before on :govern is the human-in-the-loop surface for disclosure
  and high-impact attributions."
  [{:node :ingest}
   {:node :simulate}
   {:node :analyze}
   {:node :govern :interrupt-before true}
   {:node :commit}])

;; ---------- node fns (pure; reused by the real StateGraph in Phase 2) ----------

(defn ingest
  "Pull new activity events from the source into a per-entity grouping.
  `events` is a seq of ActivityEvent. Returns {entity [events]}. Pure."
  [events]
  (reduce (fn [m ev] (update m (:entity ev) (fnil conj []) ev))
          {} events))

(defn simulate
  "Advance each entity's stock by one tick over its grouped events.
  `stock-of` is entity->StockSnapshot (current). Returns {entity stock'}.
  Pure."
  ([grouped stock-of] (simulate grouped stock-of facts/default-params))
  ([grouped stock-of params]
   (reduce-kv (fn [m entity evs]
                (assoc m entity (sim/tick (or (stock-of entity)
                                              (sim/init-stock entity))
                                          evs params)))
              {} grouped)))

(defn analyze
  "Turn stock snapshots into Proposals via the (injectable) advisor. Pure."
  ([advisor stocks] (analyze advisor stocks facts/default-params))
  ([advisor stocks params] (advisor/advise advisor (vals stocks) params)))

(defn govern
  "Governor land step: review proposals, commit verdicts to ledger (append-fn),
  partition by decision. Pure except append-fn (injected)."
  ([proposals stocks append-fn] (govern proposals stocks append-fn facts/default-params))
  ([proposals stocks append-fn params]
   (gov/land proposals (fn [e] (get stocks e)) append-fn params)))

;; ---------- pure, dependency-free orchestration (blueprint maturity) ----------

(defn run-tick
  "Orchestrate ONE bounded tick over the full node sequence without requiring
  langgraph-clj. This is the blueprint-runtime path; the Phase-2 StateGraph
  composes the same node fns above with checkpointing.

  Args:
    events     seq of ActivityEvent observed this tick
    stock-of   entity->StockSnapshot (current state)
    advisor    injectable Advisor (mock ‖ real-LLM)
    append-fn  injected ledger-append (mock in tests; datom-plane in prod)
    params     SD/Governor params

  Returns {:stocks {entity stock'} :governor <land partition>}.
  Pure except append-fn."
  ([events stock-of advisor append-fn]
   (run-tick events stock-of advisor append-fn facts/default-params))
  ([events stock-of advisor append-fn params]
   (let [grouped (ingest events)
         stocks  (simulate grouped stock-of params)
         props   (analyze advisor stocks params)
         result  (govern props stocks append-fn params)]
     {:stocks stocks :governor result})))

;; ---------- chartered orchestration (G3/G7/tsukuroi + G12) ----------

(declare summarize-charter)

(defn run-tick-chartered
  "Case-aware governed tick — wraps run-tick with the charter
  (oteint.kernels.charter), mirroring tadori's posture:

    G3  a valid case + caseMandate is REQUIRED for any LIVE :attribute
        attribution. Without one, the tick runs in Phase 0 DRY-RUN: the engine
        still simulates + analyzes + recomputes counters, but NOTHING live is
        persisted (the ledger append-fn is suppressed).
    G7  oteint never enforces — :had-enforcement-action is always false here.
    G12 after the tick, the 9 structural zero-counters are recomputed; if ANY is
        nonzero the tick HALTS and persists nothing (including in dry-run).

  Returns {:result <run-tick result> :counters <map> :halted? bool
           :dry-run? bool :persisted? bool}.

  `opts` carries :store-kind (live writes must be :kotoba) and
  :inference-gateway (:murakumo | :vendor | nil). Blueprint defaults: :mem
  store, no inference yet — so a LIVE run on the :mem store would trip
  :non-kotoba-store (correctly: blueprint must not persist live attribution to
  a non-kotoba store). The autonomous loop uses oteint.heartbeat (dry-run only).

  Pure except append-fn."
  ([events stock-of advisor append-fn case now]
   (run-tick-chartered events stock-of advisor append-fn facts/default-params case now
                       {:store-kind :mem :inference-gateway nil}))
  ([events stock-of advisor append-fn params case now opts]
   (let [dry?   (ch/dry-run? case now)
         ;; dry-run suppresses live ledger writes (Phase 0); only counters persist
         sink   (if dry? (fn [_]) append-fn)
         result (run-tick events stock-of advisor sink params)]
     (assoc (summarize-charter (:stocks result) (:governor result) case now params opts)
            :result result))))

(defn summarize-charter
  "Compute the charter zero-counters + G12 verdict from a tick's resulting stocks
  and governor partition, in context (case, now, params, opts). Shared by
  run-tick-chartered (pure orchestration) and the langgraph StateGraph :commit
  node. Returns {:counters :halted? :dry-run? :persisted?}. Pure."
  [stocks gov case now params opts]
  (let [dry?    (ch/dry-run? case now)
        ;; worst (most-suspicious) entity drives the B2 mass-surveillance check
        worst   (when (seq stocks)
                  (apply max-key (fn [[_ s]] (:suspicion s)) stocks))
        wstk    (if worst (val worst) (sim/init-stock ""))
        allowed (dyn/allowed-depth-for (:suspicion wstk) (:obs-depth params))
        ;; live attribution only when a case authorizes it AND it's not dry-run
        live?   (and (not dry?)
                     (or (some #(= :attribute (:kind (first %))) (:human gov))
                         (some #(= :attribute (:kind (first %))) (:approved gov))))
        ctx     {:case case :now now
                 :store-kind        (:store-kind opts)
                 :inference-gateway (:inference-gateway opts)
                 :had-live-write         live?
                 :had-pii-write          false
                 :pii-encrypted?         true
                 :had-enforcement-action false
                 :used-platform-key      false
                 :max-obs-depth          (:max-obs-depth wstk)
                 :allowed-obs-depth      allowed
                 :attributed-named-indiv? false}   ; clusters only at blueprint
        counters (ch/compute-counters ctx)
        halted?  (ch/g12-halt? counters)]
    {:counters counters :halted? halted? :dry-run? dry?
     :persisted? (and (not halted?) (not dry?))}))
