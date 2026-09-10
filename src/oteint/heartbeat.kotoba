(ns oteint.heartbeat
  "silenOteintReview — the ONLY autonomous act oteint may perform. Mirrors
  tadori's silenTadoriReview self-audit (ADR-2606160842 / Charter §1.12
  Transparent Force, G5).

  Because oteint is authorized-investigation-only (G3) and evidence-only /
  no-enforcement (G7), it may NOT autonomously persist case-anchored attribution
  or PII. The charter-permitted autonomous act is this self-audit heartbeat:
    load an OFFLINE operator-staged corpus → run a Phase 0 DRY-RUN tick (no case)
    → recompute the 9 structural zero-counters → G12 guard (any nonzero HALTS,
    persisting nothing) → append ONE content-addressed audit-counter datom to the
    local audit log.

  By construction the log holds ONLY audit counters — never observation, never
  PII, never case data (G3/G6/G10 structurally honored). No live I/O, no LLM
  inference, no enforcement. Deterministic + resume-safe. See ADR-2607182000 §8."
  (:require [oteint.phase :as phase]
            [oteint.kernels.charter :as ch]
            [oteint.facts :as facts]))

(defn self-audit
  "Run ONE autonomous self-audit heartbeat over an offline operator-staged
  corpus (`events`). ALWAYS Phase 0 dry-run — no case is ever supplied, so no
  live attribution can occur.

  Returns the audit record:
    {:event :oteint/self-audit :dry-run true :halted? bool
     :counters <9-counter map> :violations [...] :now n}

  `audit-append-fn` is injected (mock in tests; the content-addressed datom log
  in prod). It is called exactly once with the record — BUT ONLY when G12 is
  clear. If any counter is nonzero, NOTHING is appended (halt). Pure except
  audit-append-fn."
  ([events stock-of advisor audit-append-fn]
   (self-audit events stock-of advisor audit-append-fn facts/default-params
               {:now 0 :store-kind :mem :inference-gateway nil}))
  ([events stock-of advisor audit-append-fn params opts]
   (let [now       (:now opts 0)
         ;; no case ⇒ Phase 0 dry-run, unconditionally
         chartered (phase/run-tick-chartered events stock-of advisor (fn [_])
                                             params nil now opts)
         counters  (:counters chartered)
         halted?   (:halted? chartered)
         rec       {:event      :oteint/self-audit
                    :dry-run    true
                    :halted?    halted?
                    :counters   counters
                    :violations (ch/violations counters)
                    :now        now}]
     (when-not halted? (audit-append-fn rec))
     rec)))
