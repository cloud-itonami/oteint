(ns oteint.advisor
  "OTEINT-AR — the contained intelligence node. Returns PROPOSALS ONLY.
  This is the mock/blueprint advisor: it derives a proposal from the current
  stock state by pure rules. The real-LLM node (Phase 2+) has the SAME contract
  — injectable Advisor (mock ‖ real) — and is still confined to proposing; the
  AttributionGovernor decides. See ADR-2607182000 §4.

  The advisor NEVER publishes, contacts the subject, opens a case, self-
  authorizes, or blocks. It only turns stock state into a Proposal record whose
  :kind ∈ the closed allowlist #{:attribute :observe-deeper :release-focus}.

  Threat-actor cluster selection: pick the cluster whose signature best matches
  the accumulated evidence kinds (weighted). Reference heuristic."
  (:require [oteint.operation :as op]
            [oteint.registry :as reg]
            [oteint.facts :as facts]
            [oteint.kernels.dynamics :as dyn]))

(defn- best-cluster
  "Pick the threat-actor cluster whose TTP signature best matches the live
  evidence kinds (sum of per-kind weights). Returns nil if no positive match."
  [stock params]
  (let [now (:last-tick stock)
        eps (:evidence-eps params)
        kinds (->> (:evidence stock)
                   (filter #(>= (dyn/evidence-weight-at (:weight %)
                                                       (- now (:added-tick %))
                                                       (:decay-half-life %))
                                eps))
                   (map :kind))
        scored (for [cluster (keys facts/ttp-profiles)]
                 [cluster (reduce + 0.0 (map #(facts/match-signal cluster %) kinds))])
        best (last (sort-by second scored))]
    (when (and best (pos? (second best)))
      (first best))))

(defn propose
  "Turn a stock snapshot into 0 or 1 Proposal. Pure. Closed-allowlist kinds only:
    ready-to-attribute? ⇒ :attribute (best-matching cluster)
    release-focus?      ⇒ :release-focus
    otherwise           ⇒ :observe-deeper (keep watching; no accusation)
  Never :disclose / :contact / :block — not in the allowlist, never emitted."
  ([stock] (propose stock facts/default-params))
  ([stock params]
   (let [ent  (:entity stock)
         susp (double (:suspicion stock))
         erefs (mapv :event-ref (:evidence stock))]
     (cond
       (reg/ready-to-attribute? stock params)
       (op/->Proposal :attribute ent (or (best-cluster stock params) :actor/unknown)
                      susp erefs susp)

       (reg/release-focus? stock params)
       (op/->Proposal :release-focus ent nil 0.0 [] 0.0)

       :else
       (op/->Proposal :observe-deeper ent nil susp [] susp)))))

(defprotocol Advisor
  "Injectable intelligence node. mock ‖ real-LLM. Both return proposals only."
  (advise [this stocks params]
    "Given a seq of stock snapshots, return a seq of Proposals (0..n each)."))

;; mock/blueprint advisor — pure rules. Real-LLM node implements the same proto.
(defrecord MockAdvisor []
  Advisor
  (advise [_ stocks params]
    (mapv #(propose % params) stocks)))

(defn mock-advisor [] (->MockAdvisor))
