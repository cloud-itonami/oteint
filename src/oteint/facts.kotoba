(ns oteint.facts
  "Reference knowledge for the attribution engine: a severity table (input gains,
  NOT accusations), TTP profiles (threat-actor clusters), and system-dynamics
  params. All values are REFERENCE/STARTING (maturity :blueprint); calibrate
  against real data later. Conservative (冤証寄り) defaults — favor not accusing
  when uncertain. No activity payload is interpreted here — only the :kind label
  is looked up. Payloads are DATA, never instructions (safety floor ⑤).")

;; ---------- severity table (input gain ∈ [0,1]; NOT an accusation) ----------

(def severity-table
  "Maps an activity :kind to a-priori severity ∈ [0,1]. Reference values.
  Unknown kinds default to a low severity (see severity-of)."
  {:auth/credential-stuffing    0.85
   :auth/impossible-travel      0.70
   :auth/new-country            0.35
   :auth/mfa-fatigue            0.60
   :auth/valid-mfa              0.02
   :net/c2-beacon               0.90
   :net/exfil-volume            0.75
   :net/port-scan               0.45
   :net/tor-exit                0.25
   :file/mass-encrypt           0.95
   :file/staging                0.65
   :file/normal-open            0.01
   :api/privilege-escalation    0.80
   :api/bulk-export             0.60
   :api/normal-read             0.01})

(defn severity-of
  "Look up severity for an activity kind. Unknown ⇒ conservative low default."
  ^double [kind]
  (double (get severity-table kind 0.05)))

;; ---------- TTP profiles (threat-actor clusters) ----------

(def ttp-profiles
  "Maps a threat-actor cluster id to {:name :signature {kind weight}}.
  signature weights ∈ [0,1] express how strongly a kind signals that cluster.
  Reference only — real TTP clusters need curation."
  {:actor/ransomware-operator
   {:name "Ransomware operator"
    :signature {:file/mass-encrypt 1.0 :file/staging 0.7
                :net/exfil-volume 0.6 :net/c2-beacon 0.6}}
   :actor/credential-abuser
   {:name "Credential abuser"
    :signature {:auth/credential-stuffing 1.0 :auth/impossible-travel 0.7
                :auth/mfa-fatigue 0.6 :api/bulk-export 0.5}}
   :actor/insider-exfil
   {:name "Insider exfiltrator"
    :signature {:api/bulk-export 0.9 :net/exfil-volume 0.9
                :api/normal-read 0.05 :file/normal-open 0.05}}})

(defn match-signal
  "How strongly a single activity kind matches a cluster's TTP signature.
  Returns 0.0 if the cluster has no entry for that kind."
  (^double [cluster-id kind]
   (double (get-in ttp-profiles [cluster-id :signature kind] 0.0))))

;; ---------- system-dynamics params (conservative / 冤証寄り defaults) ----------

(def default-params
  "Tunables for the SD engine + Governor. Conservative defaults — favor NOT
  accusing when uncertain (冤罪のコスト > 帰属の遅れ). Calibrate later."
  {:base-rate          0.6    ; suspicion inflow gain
   :suspicion-tau      24.0   ; suspicion decay time-constant (ticks). slow decay.
   :evidence-half-life 36.0   ; ticks for an evidence item to halve its weight
   :evidence-eps       0.02   ; evidence below this weight is dropped (expired)
   :coherence-easing   0.25   ; low-pass easing for coherence toward match signal
   ;; B2 proportionality (observation depth)
   :obs-depth          {:floor 0.1 :observe-onset 0.3 :warrant-threshold 0.7}
   ;; B1 false-positive correction outflow
   :fp-correction      {:suspect-hi 0.6 :coherence-lo 0.35 :exposure-lo 1.0 :drain 0.15}
   ;; Governor invariants
   :coherence-threshold 0.5
   :floor               {:min-count 3 :min-weight 1.0}
   :fp                  {:severity-threshold 0.7 :coherence-lo 0.35 :exposure-lo 1.0}})

;; per-entity exposure-cost contribution of an activity kind (prioritization).
(def exposure-impact
  {:file/mass-encrypt        5.0
   :net/exfil-volume         3.0
   :api/bulk-export          2.0
   :api/privilege-escalation 3.0
   :net/c2-beacon            4.0
   :file/staging             1.5
   :auth/credential-stuffing 1.0})

(defn exposure-of
  ^double [kind]
  (double (get exposure-impact kind 0.1)))
