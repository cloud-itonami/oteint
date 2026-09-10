(ns oteint.operation
  "おてんとさま (Otentosama) — record types for the attribution actor.
  Pure data definitions. See ADR-2607182000.

  The proposal :kind set is a CLOSED allowlist (Governor invariant ⑥).
  ActivityEvent :payload is DATA only — never interpreted as instructions
  (safety floor ⑤, prompt-injection boundary)."
  (:refer-clojure :exclude [dec]))

;; A single activity observation. :payload is opaque data, never code/instructions.
(defrecord ActivityEvent [entity kind severity novelty ts payload])

;; Per-entity accumulated stock state (the system-dynamics state vector).
;;   suspicion     ∈ [0,1] — central stock
;;   coherence     ∈ [0,1] — match to a known threat-actor TTP profile
;;   evidence      [EvidenceItem] — weighted, decaying
;;   exposure-cost ≥ 0     — damage potential (prioritization)
;;   max-obs-depth ≥ 0     — deepest observation used this entity (B2 audit)
;;   last-tick     int
(defrecord StockSnapshot [entity suspicion coherence evidence exposure-cost max-obs-depth last-tick])

;; One piece of evidence. Weight decays each tick (B3, evidence-expiry).
(defrecord EvidenceItem [weight decay-half-life added-tick kind event-ref])

;; What OTEINT-AR (the contained intelligence) proposes. Proposals ONLY —
;; the actor never publishes, contacts the subject, opens a case, or blocks.
;; :kind ∈ #{:attribute :observe-deeper :release-focus} (closed allowlist).
(defrecord Proposal [kind entity actor-cluster confidence evidence-refs severity])

;; What the AttributionGovernor returns. :decision ∈ #{:approve :reject :hold :human}.
;; :human ⇒ interrupt-before (human-in-the-loop), used for disclosure/high-impact.
(defrecord Verdict [decision reason proposal-id governor-invariant])
