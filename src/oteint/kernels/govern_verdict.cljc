(ns oteint.kernels.govern-verdict
  "AttributionGovernor verdict kernels — the 裁き (judgment) of a separate lineage
  from OTEINT-AR. Pure predicates only. See ADR-2607182000 §4.

  The Governor checks each proposal against SIX invariants. Each returns a
  {:ok? :reason :invariant} map. The actor (OTEINT-AR) NEVER performs any
  disclosure/contact/case-open/handoff/block the Governor rejects — single
  invariant. 太陽は照らす、裁かない (the sun illuminates; a separate lineage judges).

  These kernels are deliberately conservative (冤証寄り): when uncertain, they
  refuse to attribute. The cost of a false accusation (冤罪) exceeds the cost of
  a delayed attribution."
  (:require [oteint.kernels.dynamics :as dyn]))

(def ^:const allowlist #{:attribute :observe-deeper :release-focus})

;; ⑥ closed-allowlist: OTEINT-AR may only propose these kinds.
(defn allowlist-ok? [proposal]
  (if (contains? allowlist (:kind proposal))
    {:ok? true :invariant :closed-allowlist}
    {:ok? false :invariant :closed-allowlist
     :reason (str "unknown proposal kind: " (:kind proposal))}))

;; ① evidence-floor: enough items AND enough total weight.
;; `format` is JVM-only (cljs has no clojure.core/format), so build the reason
;; with str for .cljc portability.
(defn evidence-floor-ok? [_proposal evidence-count evidence-weight floor]
  (let [{:keys [min-count min-weight]
         :or {min-count 3 min-weight 1.0}} floor]
    (if (and (>= evidence-count min-count)
             (>= evidence-weight min-weight))
      {:ok? true :invariant :evidence-floor}
      {:ok? false :invariant :evidence-floor
       :reason (str "insufficient evidence: count=" (int evidence-count)
                    "/" (int min-count) " weight="
                    (double evidence-weight) "/" (double min-weight))})))

;; ② coherence-floor: no pattern-match-only conviction (B1 partner).
(defn coherence-floor-ok? [coherence c-threshold]
  (if (>= (double coherence) (double c-threshold))
    {:ok? true :invariant :coherence-floor}
    {:ok? false :invariant :coherence-floor
     :reason (str "pattern-only: coherence=" (double coherence)
                  " < " (double c-threshold))}))

;; ③ false-positive-guard (冤罪防止): high-severity accusation against a
;; low-exposure-cost, low-coherence target ⇒ HOLD for human review.
;; Returns {:hold? true} when the guard fires; otherwise pass-through.
(defn false-positive-guard
  [proposal coherence exposure-cost fp]
  (let [{:keys [severity-threshold coherence-lo exposure-lo]
         :or {severity-threshold 0.7 coherence-lo 0.35 exposure-lo 1.0}} fp]
    (if (and (>= (double (:severity proposal)) (double severity-threshold))
             (<  (double coherence) (double coherence-lo))
             (<  (double exposure-cost) (double exposure-lo)))
      {:ok? :hold :invariant :false-positive-guard
       :reason "possible false-positive: high-severity attribution on weak case"}
      {:ok? true :invariant :false-positive-guard})))

;; ④ proportionality-audit (B2 enforcement): the observation depth actually
;; used must not exceed what suspicion permitted. If it did, the evidence is
;; tainted (dragnet) and INADMISSIBLE — reject.
(defn proportionality-audit-ok? [max-obs-depth-used suspicion params]
  (let [allowed (dyn/allowed-depth-for suspicion (:obs-depth params))]
    (if (<= (double max-obs-depth-used) (+ allowed 1e-9))
      {:ok? true :invariant :proportionality-audit}
      {:ok? false :invariant :proportionality-audit
       :reason (str "tainted evidence: obs-depth " (double max-obs-depth-used)
                    " > allowed " (double allowed)
                    " for suspicion " (double suspicion))})))

;; ⑤ disclosure-guard: public disclosure / external handoff ALWAYS requires
;; human approval. The Governor never auto-publishes an accusation.
(defn disclosure-guard [proposal]
  ;; Any :attribute proposal whose target reaches outside the ledger
  ;; (publication / external handoff) is human-gated. For the blueprint, every
  ;; :attribute is treated as disclosure-class ⇒ human approval required.
  ;; (A future :internal-flag kind could be auto-approvable; not in allowlist yet.)
  (if (= :attribute (:kind proposal))
    {:ok? :human :invariant :disclosure-guard
     :reason "attribution disclosure requires human approval (interrupt-before)"}
    {:ok? true :invariant :disclosure-guard}))

(defn check
  "Run the invariants against a proposal in context of its stock state.
  Returns a single verdict map:
    {:decision :approve | :reject | :hold | :human
     :reason   string
     :invariant keyword — the invariant that decided the outcome}

  Crucially: evidence/coherence/false-positive/proportionality floors gate
  ACCUSATIONS (:attribute) only. Benign internal proposals — :observe-deeper
  (keep watching) and :release-focus (stop watching a decayed entity) — accuse
  no one, so they pass on the closed-allowlist alone. This keeps the engine
  from being paralyzed (it must always be free to keep watching).

  Precedence for :attribute (first failing/overriding wins):
    reject :closed-allowlist / :evidence-floor / :coherence-floor / :proportionality-audit
    hold   :false-positive-guard
    human  :disclosure-guard   (every attribution ⇒ interrupt-before)
    approve otherwise (unreachable for :attribute — disclosure-guard gates all)

  Pure. No I/O."
  [proposal {:keys [coherence exposure-cost max-obs-depth suspicion]}
   evidence-count evidence-weight params]
  (let [al (allowlist-ok? proposal)]
    (cond
      ;; ⑥ closed-allowlist always applies.
      (not (:ok? al))
      {:decision :reject :reason (:reason al) :invariant :closed-allowlist}

      ;; Non-accusation proposals (observe-deeper / release-focus): allowlist is
      ;; sufficient. They never disclose, contact, or block.
      (not= :attribute (:kind proposal))
      {:decision :approve :reason "non-accusation; allowlist satisfied"
       :invariant :closed-allowlist}

      ;; :attribute (accusation) — run the full floor/guard gauntlet.
      :else
      (let [ef (evidence-floor-ok? proposal evidence-count evidence-weight
                                   (:floor params))
            cf (coherence-floor-ok? coherence (:coherence-threshold params))
            pa (proportionality-audit-ok? max-obs-depth suspicion params)
            fp (false-positive-guard proposal coherence exposure-cost (:fp params))
            dg (disclosure-guard proposal)]
        (cond
          (not (:ok? ef)) {:decision :reject :reason (:reason ef) :invariant :evidence-floor}
          (not (:ok? cf)) {:decision :reject :reason (:reason cf) :invariant :coherence-floor}
          (not (:ok? pa)) {:decision :reject :reason (:reason pa) :invariant :proportionality-audit}
          (= :hold (:ok? fp)) {:decision :hold :reason (:reason fp) :invariant :false-positive-guard}
          (= :human (:ok? dg)) {:decision :human :reason (:reason dg) :invariant :disclosure-guard}
          :else {:decision :approve :reason "all accusation invariants satisfied"
                 :invariant :all})))))
