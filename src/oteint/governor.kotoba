(ns oteint.governor
  "AttributionGovernor — the 裁き (judgment) of a SEPARATE lineage from OTEINT-AR.
  Thin orchestration over the pure kernels in oteint.kernels.govern-verdict.

  The Governor reviews each proposal and routes it to :approve / :reject /
  :hold / :human. The actor (OTEINT-AR) NEVER performs any disclosure/contact/
  case-open/handoff/block that the Governor rejects — single invariant
  (ADR-2607182000 §4). 太陽は照らす、裁かない.

  Pure except for the (mock) audit-ledger append hook, which is injected so the
  core stays testable without a store. In production the ledger is the
  append-only audit datom-plane (`:db-api` + kotoba-lang/langchain-store)."
  (:require [oteint.operation :as op]
            [oteint.kernels.govern-verdict :as gv]
            [oteint.sim :as sim]
            [oteint.facts :as facts]))

(defn review
  "Review a single proposal against a stock snapshot. Returns an
  oteint.operation.Verdict. Pure (no ledger side-effect here)."
  ([proposal stock] (review proposal stock facts/default-params))
  ([proposal stock params]
   (let [{:keys [count weight]} (sim/evidence-summary stock params)
         v (gv/check proposal stock count weight params)]
     (op/->Verdict (:decision v) (:reason v)
                   (:entity proposal) (:invariant v)))))

(defn review-all
  "Review a batch of proposals against their respective stocks. `stock-of` is a
  fn entity->StockSnapshot. Returns [proposal verdict] pairs. Pure."
  ([proposals stock-of] (review-all proposals stock-of facts/default-params))
  ([proposals stock-of params]
   (mapv (fn [p]
           (let [stk (stock-of (:entity p))]
             [p (review p stk params)]))
         proposals)))

(defn commit-ledger!
  "Append a (proposal, verdict) pair to the audit ledger via the injected
  `append-fn`. The ledger is the permanence: 『太陽はすべてを見ている』— every
  proposal and every Governor verdict is recorded, including REJECTs and HOLDs.
  Returns whatever append-fn returns. append-fn is injected (mock in tests)."
  [append-fn proposal verdict]
  (append-fn {:event :oteint/govern
              :entity (:entity proposal)
              :proposal proposal
              :verdict verdict}))

(defn land
  "Governor land step for a batch: review each proposal, commit verdicts to the
  ledger, and partition into {:approved :rejected :held :human}. The returned
  :human set is the interrupt-before surface (human-in-the-loop) for disclosure
  and high-impact attributions. OTEINT-AR may only ACT on :approved (and only
  within the closed allowlist); :human awaits owner sign-off."
  ([proposals stock-of append-fn]
   (land proposals stock-of append-fn facts/default-params))
  ([proposals stock-of append-fn params]
   (let [pairs (review-all proposals stock-of params)]
     (doseq [[p v] pairs] (commit-ledger! append-fn p v))
     (reduce (fn [m [p v]]
               (let [k (case (:decision v)
                         :approve :approved :reject :rejected
                         :hold :held :human :human)]
                 (update m k conj [p v])))
             {:approved [] :rejected [] :held [] :human []}
             pairs))))
