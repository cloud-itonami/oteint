(ns oteint.kernels.charter
  "Governance posture for oteint — mirrors tadori (辿), etzhayyim's chartered
  actor-attribution actor, so oteint meets the SAME constitutional bar even
  though it operates on a different data plane (behavioral activity streams, not
  on-chain transactions). See ADR-2607182000 §8.

  oteint is:
    G3  authorized-investigation-only — every LIVE write requires a case anchor
        with an authorization reference. No valid case ⇒ Phase 0 dry-run only.
    G7  evidence-only / no-enforcement — it never contacts, blocks, or acts on
        the subject. (The enforcement-action zero-counter below is always 0.)
    tsukuroi propose-only (ADR-2605291500) — OTEINT-AR proposes; a case-member
        (human, case-authorized) commits via the Governor + ledger. The
        autonomous loop may ONLY run the self-audit heartbeat (oteint.heartbeat).

  NINE structural zero-counters — recomputed each tick/heartbeat. If ANY is
  nonzero, G12 HALTS and nothing is persisted. By construction the audit log
  holds ONLY counters — never observation, PII, or case data (G3/G6/G10).

  Pure predicates only. No I/O, portable .cljc.")

;; ---------- case + mandate (G3 authorized-investigation-only) ----------

(defrecord Case [case-id authorization-ref scope expires])
;; authorization-ref = the caseMandate (e.g. a signed authorization id).
;; scope may carry {:allow-named-individual true} to permit adherent deanon.

(defn mandate-valid?
  "A case authorizes LIVE work iff it has an id, an authorization-ref, has not
  expired (relative to `now`). Pure. nil/missing case ⇒ not valid ⇒ dry-run.
  Returns an explicit boolean."
  ([case now]
   (boolean
     (and (map? case)
          (:case-id case)
          (:authorization-ref case)
          (let [exp (:expires case)]
            (or (nil? exp) (< (long now) (long exp))))))))

(defn dry-run?
  "Phase 0 dry-run: no valid case+mandate ⇒ the engine may simulate + analyze
  and recompute counters, but MUST NOT persist live attribution/PII. Pure."
  ([case now] (not (mandate-valid? case now))))

(defn adherent-deanon-allowed?
  "Naming a real, named individual (vs a threat-actor CLUSTER) requires explicit
  case-scope authorization. Without it, attribution must stay at the cluster
  level — the adherent-deanon zero-counter fires otherwise. Pure."
  ([case] (boolean (get-in case [:scope :allow-named-individual]))))

;; ---------- the 9 structural zero-counters (G12) ----------

(def zero-counters
  "Every counter must be 0 for a tick/heartbeat to persist. Modeled on tadori's
  9 structural zero-counters (ADR-2605192100 §1.12 / ADR-2606160842)."
  [:noncase-write        ; live write without a valid case+mandate
   :plaintext-pii        ; PII persisted in plaintext (must be encrypted envelope)
   :proprietary-sor      ; used a proprietary system-of-record (vendor lock-in)
   :enforcement-action   ; any contact/block/takedown on the subject (oteint NEVER enforces)
   :platform-held-key    ; used a platform key instead of a case-member DID signature
   :murakumo-bypass      ; inference bypassed the Murakumo gateway (ADR-2605215000)
   :mass-surveillance    ; observation depth exceeded the B2 proportionality bound
   :adherent-deanon      ; attributed/deanonymized a NAMED individual without case auth
   :non-kotoba-store])   ; live data written outside the kotoba datom-plane

(defn compute-counters
  "Given a tick-context map describing what a tick did, return the zero-counter
  map {counter int}. Every value must be 0 for the tick to persist (G12).

  Tick-context keys:
    :case                    Case (or nil)
    :now                     tick/heartbeat time
    :store-kind              :kotoba | :mem | :datomic | … (live writes must be :kotoba)
    :inference-gateway       :murakumo | :vendor | nil (no inference yet ⇒ nil)
    :had-live-write          did the tick persist any live (non-audit) data?
    :had-pii-write           did the tick touch PII?
    :pii-encrypted?          was the PII under an encrypted envelope?
    :had-enforcement-action  did the tick contact/block/operate on a subject?
    :used-platform-key       did the tick sign with a platform key?
    :max-obs-depth           deepest observation used this tick
    :allowed-obs-depth       B2 ceiling for the suspicion level (oteint.kernels.dynamics)
    :attributed-named-indiv? did attribution name a real individual (vs a cluster)?

  Pure. Returns a map with all 9 keys."
  [{:keys [case now store-kind inference-gateway
           had-live-write had-pii-write pii-encrypted?
           had-enforcement-action used-platform-key
           max-obs-depth allowed-obs-depth attributed-named-indiv?]}]
  (let [mandate (mandate-valid? case now)]
    {:noncase-write      (if (and had-live-write (not mandate)) 1 0)
     :plaintext-pii      (if (and had-pii-write (not pii-encrypted?)) 1 0)
     :proprietary-sor    0   ; reference: a future store-kind :proprietary would set this
     :enforcement-action (if had-enforcement-action 1 0)
     :platform-held-key  (if used-platform-key 1 0)
     :murakumo-bypass    (if (and inference-gateway (not= inference-gateway :murakumo)) 1 0)
     :mass-surveillance  (if (> (double (or max-obs-depth 0.0))
                                (+ (double (or allowed-obs-depth 0.0)) 1e-9))
                           1 0)
     :adherent-deanon    (if (and attributed-named-indiv?
                                 (not (adherent-deanon-allowed? case)))
                          1 0)
     :non-kotoba-store   (if (and had-live-write
                                  (some? store-kind)
                                  (not= store-kind :kotoba))
                          1 0)}))

(defn g12-halt?
  "G12 guard: any nonzero counter ⇒ HALT, persist nothing. Pure."
  ([counters] (boolean (some (comp pos? int) (vals counters)))))

(defn all-clear?
  "Convenience: the negation of g12-halt?. Pure."
  ([counters] (not (g12-halt? counters))))

(defn violations
  "Return the list of nonzero counter keys (for the audit datom). Pure."
  ([counters] (for [[k v] counters :when (pos? (int v))] k)))
