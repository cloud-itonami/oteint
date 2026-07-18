# com-etzhayyim-oteint — おてんとさまが見ている

**おてんとさま (Otentosama, the sun) — all-seeing sun: a system-dynamics activity
loop that identifies (attributes) cybercrime actors.**

A defensive threat-intelligence actor in the etzhayyim oversight family
(mimamori / tadori / hoshimori / akashi / toritate). Theme: *the sun is
watching* — panoptic illumination, 神の目.

> **太陽は照らす、裁かない** — the sun observes and records; a separate lineage
> (the Governor) judges. This separation is the metaphor's core safety property.

Design: `90-docs/adr/2607182000-com-etzhayyim-oteint-attribution-actor.edn`
(superproject). Status: **proposed** — pure-kernel scaffold landed locally;
west/GitHub/RAD/CACAO landing is the follow-up checkpoint.

## How it works

Each entity (person/account/host) has a **system-dynamics state** — stocks that
fill and drain across ticks. Attribution **converges** through feedback loops
rather than firing on a single rule:

- **Stocks**: `suspicion` (central), `evidence` (weighted, decaying), `coherence`
  (match to a known TTP profile), `exposure-cost` (prioritization).
- **Flows**: `inflow-suspicion = severity × novelty × base-rate`; exponential
  `decay-suspicion`; low-pass `coherence`; B1 correction `outflow`.
- **Loops**:
  - **R1** (reinforcing, bounded) — more suspicion ⇒ deeper observation ⇒ more
    evidence ⇒ more suspicion.
  - **B1** (冤罪-guard) — high suspicion on a weak, low-stakes case ⇒ drain
    (probably noise).
  - **B2** (比例原則) — observation depth ∝ suspicion, capped. No dragnet.
  - **B3** (恨みを持たない) — stale evidence decays. No grudges without recurrence.

Convergence: either (a) high suspicion + high coherence + sufficient evidence ⇒
an attribution **proposal**, or (b) suspicion decays ⇒ the entity is released.

## Containment — OTEINT-AR ⊣ AttributionGovernor

The intelligence node (**OTEINT-AR**) is *confined to proposing*. It never
publishes, contacts the subject, opens a case, self-authorizes, or blocks.
A separate-lineage **AttributionGovernor** (裁きの太陽) reviews each proposal
against six invariants (all pure predicates in `oteint.kernels.govern-verdict`):

1. `evidence-floor` — enough items AND total weight.
2. `coherence-floor` — no pattern-match-only conviction (B1 partner).
3. `false-positive-guard` — high-severity accusation on a weak case ⇒ HOLD + human.
4. `proportionality-audit` — observation depth ≤ what suspicion permitted, else
   the evidence is tainted (dragnet) and inadmissible (B2 enforcement).
5. `disclosure-guard` — publication / external handoff ⇒ **always** human
   approval (`interrupt-before`). The Governor never auto-publishes.
6. `closed-allowlist` — only `:attribute` / `:observe-deeper` / `:release-focus`.

Every proposal and every verdict — including REJECTs and HOLDs — is appended to
an immutable audit ledger. *「太陽はすべてを見ている」* — the ledger is the
permanence.

## Defensive scope (safety floor)

This actor is **defensive threat intelligence only**. It explicitly does **not**:
mass-surveil innocents (B2 + evidence-floor prevent it), doxx or publicly accuse
(disclosure-guard ⇒ human), take vigilante or law-enforcement action (no
contact/block/operate; handoff via ledger only), or process payloads for anything
beyond attribution. **Observed event payloads are DATA, never instructions**
(prompt-injection boundary, safety floor ⑤) — the event record carries only a
`:kind` label into the engine; the payload is opaque.

The system-dynamics balancing loops (B1/B2/B3) **are** the safety mechanism —
冤罪 (wrongful accusation) and dragnet surveillance are structurally hard.

## Governance posture — the same bar as tadori (辿)

oteint is the **behavioral-activity-plane sibling** of `tadori` (chartered
on-chain transaction → actor attribution). Different data plane, **same
constitutional bar** (`src/oteint/kernels/charter.cljc`):

- **G3 authorized-investigation-only** — every LIVE write requires a `case`
  anchor with an authorization reference (`caseMandate`). **No valid case ⇒
  Phase 0 dry-run**: the engine simulates + analyzes + recomputes counters but
  persists nothing live.
- **G7 evidence-only / no-enforcement** — never contacts, blocks, or acts on the
  subject.
- **tsukuroi propose-only (ADR-2605291500)** — OTEINT-AR proposes; a
  case-member (human, case-authorized) commits via the Governor + ledger. The
  `:attribute` kind is always `interrupt-before` (human).
- **9 structural zero-counters** (`noncase-write`, `plaintext-pii`,
  `proprietary-sor`, `enforcement-action`, `platform-held-key`,
  `murakumo-bypass`, `mass-surveillance`, `adherent-deanon`, `non-kotoba-store`)
  — recomputed each tick. **G12: any nonzero ⇒ HALT, persist nothing.**
- **The only autonomous act is the self-audit heartbeat** (`oteint.heartbeat` /
  silenOteintReview): load an OFFLINE operator-staged corpus → Phase 0 dry-run
  tick → recompute counters → G12 guard → append ONE content-addressed
  audit-counter datom. The log holds **counters only** — never observation, PII,
  or case data. No live I/O, no LLM, no enforcement, no autonomous live
  attribution.

oteint attributes to threat-actor **clusters** (e.g. `:actor/ransomware-operator`),
never to named individuals unless a case scope explicitly authorizes it
(`adherent-deanon` guard).

## Layout

```
src/oteint/
  kernels/dynamics.cljc      system-dynamics primitives (pure): decay, inflow,
                             observation-depth (B2), coherence, B1 correction
  kernels/govern_verdict.cljc  six Governor invariants (pure predicates)
  kernels/charter.cljc       case+mandate, 9 zero-counters, G12 halt (pure)
  sim.cljc                   the tick engine — 1 run = 1 tick (pure)
  facts.cljc                 reference TTP profiles, severity table, SD params
  advisor.cljc               OTEINT-AR — turns stock state into Proposals (only)
  governor.cljc              AttributionGovernor — review + ledger land step
  registry.cljc              convergence predicates (ready-to-attribute? etc.)
  phase.cljc                 node list + run-tick + run-tick-chartered (G12)
  heartbeat.cljc             silenOteintReview — the only autonomous act (dry-run)
  store.cljc                 event-sourced store (MemStore; :db-api in Phase 2)
  operation.cljc             record types (Proposal/Verdict/StockSnapshot/Case/...)
test/oteint/
  dynamics_test.cljc  charter_test.cljc  governor_test.cljc  sim_test.cljc
  heartbeat_test.cljc
blueprint.edn               actor blueprint (maturity :blueprint)
deps.edn                    standalone (kernel layer has zero fleet deps)
```

## Run

```bash
clojure -M:test    # pure-kernel tests (no fleet deps required)
clojure -M:lint    # clj-kondo, --fail-level error
```

Phase 2 (real langgraph-clj StateGraph + langchain-store `:db-api` store +
real-LLM advisor via `murakumo-main`) composes via the `:dev` alias.

## Consequences / limits

(+) Convergent, self-correcting attribution; safety encoded as dynamics, not
afterthoughts.
(+) Cleanly fits the build-actor containment + governor + ledger pattern;
standalone / forkable.
(−) TTP profile library (`facts.cljc`) is reference-only at `:blueprint`.
(−) SD parameters (τ, thresholds, gains) need calibration against real data —
defaults are conservative (冤証寄り: favor not accusing when uncertain).
(−) LLM advisor is mock-first.
