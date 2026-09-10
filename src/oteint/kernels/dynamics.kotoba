(ns oteint.kernels.dynamics
  "おてんとさま — system-dynamics primitives. Pure, no I/O, portable .cljc.

  The attribution engine models each entity's state as STOCKS that fill via
  inflows and drain via outflows across discrete ticks. Three feedback loops
  bound the dynamics so attribution CONVERGES and false-positives / dragnet
  surveillance are structurally hard:

    R1 (reinforcing, bounded): suspicion↑ → deeper observation → more evidence
                                → suspicion↑. Bounded by B2.
    B1 (balancing, 冤罪-guard): low exposure-cost + low coherence + high suspicion
                                → Governor injects a correction outflow.
    B2 (balancing, 比例原則):   observation depth ∝ min(suspicion, warrant-threshold).
                                No dragnet. The sun watches more closely only those
                                already glowing.
    B3 (balancing, evidence-    stale evidence decays. Old sins don't convict
         expiry):               forever without recurrence. 太陽は恨みを持たない.

  See ADR-2607182000 §3. Pure functions only — no atom, no I/O, portable across
  JVM / ClojureScript / WASM. Math uses `Math/exp` (portable across :clj/:cljs).")

(defn- exp
  "Portable e^x. `Math/exp` resolves in both :clj (JVM java.lang.Math) and
  :cljs (CLJS maps Math to js/Math), so no reader-conditional is needed."
  ^double [x]
  (Math/exp (double x)))

;; ---------- bounds & clamps ----------

(defn clamp01 ^double [x]
  (let [x (double x)]
    (cond (> x 1.0) 1.0 (< x 0.0) 0.0 :else x)))

(defn clamp-lo ^double [lo x]
  (let [x (double x)]
    (if (< x lo) (double lo) x)))

;; ---------- B3: evidence-expiry / suspicion decay ----------

(defn decay-factor
  "Multiplicative per-tick decay = exp(-dt/tau). tau = suspicion time-constant.
  Returned in (0,1]. Larger tau ⇒ slower decay (longer memory)."
  (^double [dt tau] (exp (- (/ (double dt) (double tau))))))

(defn decay-suspicion
  "Apply one decay step to a suspicion value. Pure."
  (^double [suspicion dt tau]
   (* (double suspicion) (decay-factor dt tau))))

(defn evidence-weight-at
  "Weight of an evidence item `age` ticks after it was added, given half-life.
  weight(t) = w0 * 2^(-age/half-life). Pure."
  (^double [^double w0 age ^double half-life]
   (if (pos? half-life)
     (* w0 (Math/pow 2.0 (- (/ (double age) half-life))))
     w0)))

(defn live-evidence-weight
  "Sum of current (decayed) weights of evidence items, dropping items below eps."
  ([evidence now-tick eps]
   (->> evidence
        (keep (fn [it]
                (let [age (- now-tick (:added-tick it))
                      w  (evidence-weight-at (:weight it) age (:decay-half-life it))]
                  (when (>= w eps) w))))
        (reduce + 0.0))))

;; ---------- B2: proportionality — bounded observation depth ----------

(defn observation-depth
  "How deeply an entity is observed given its suspicion, per B2 (比例原則).
  Ramps from `floor` (minimal baseline) up to 1.0 as suspicion rises from
  `observe-onset` to `warrant-threshold`. Capped at 1.0 — no dragnet beyond.
  Pure. Returned ∈ [floor, 1.0].

  This is the safety mechanism: observation scales with PRIOR suspicion, never
  exceeding the proportionate bound. The Governor's proportionality-audit
  (govern_verdict ④) checks that the depth actually used stayed within this bound."
  ^double [suspicion {:keys [floor observe-onset warrant-threshold]
                      :or {floor 0.1 observe-onset 0.3 warrant-threshold 0.7}}]
  (let [s (double suspicion)
        lo (double observe-onset)
        hi (double warrant-threshold)]
    (cond
      (>= s hi)                     1.0
      (<= s lo)                     (double floor)
      :else (let [frac (/ (- s lo) (- hi lo))]
              (clamp01 (+ floor (* (- 1.0 floor) frac)))))))

(defn allowed-depth-for
  "Maximum observation depth permitted at a given suspicion level (B2 ceiling).
  The Governor compares `max-obs-depth-used` against this."
  ^double [suspicion params]
  (observation-depth suspicion params))

;; ---------- inflows ----------

(defn inflow-suspicion
  "Raw suspicion inflow contributed by one activity event, before B2 scaling.
  = severity × novelty × base-rate. `severity`/`novelty` are looked-up values
  ∈ [0,1] (event carries them after severity-table lookup). Pure."
  ^double [{:keys [severity novelty]} ^double base-rate]
  (* (double severity)
     (double novelty)
     base-rate))

(defn effective-inflow
  "Actual suspicion inflow after B2 observation-depth scaling. R1 reinforcement,
  bounded: inflow × observation-depth. Pure."
  ^double [event suspicion params base-rate]
  (* (inflow-suspicion event base-rate)
     (observation-depth suspicion params)))

;; ---------- coherence (TTP-profile match) ----------

(defn coherence-after
  "Advance coherence one step toward the profile-match signal ∈ [0,1].
  `match-signal` ∈ [0,1] is how strongly recent events match a known TTP cluster.
  Coherence eases toward match-signal (low-pass) so single hits don't convict.
  Pure."
  ^double [coherence match-signal ^double easing]
  (let [easing (clamp01 easing)]
    (clamp01 (+ (* (- 1.0 easing) (double coherence))
                (* easing (double match-signal))))))

;; ---------- B1: false-positive correction (冤罪-guard) ----------

(defn fp-correction-outflow
  "B1 balancing outflow. When suspicion is high but the case is weak on both
  coherence AND exposure-cost (i.e. scary-looking but probably noise / coincidence
  with low stakes), return a POSITIVE outflow to drain suspicion. Otherwise 0.
  The Governor's false-positive-guard gates attribution on the same condition;
  this outflow is the dynamical version that lets weak high-suspicion cases cool.

  Thresholds intentionally conservative (冤証寄り — favor not-accusing).
  Pure; returns ∈ [0, suspicion]."
  ^double [suspicion coherence exposure-cost params]
  (let [{:keys [suspect-hi coherence-lo exposure-lo drain]
         :or {suspect-hi 0.6 coherence-lo 0.35 exposure-lo 1.0 drain 0.15}}
        params
        weak-case? (and (>= (double suspicion) (double suspect-hi))
                        (<  (double coherence) (double coherence-lo))
                        (<  (double exposure-cost) (double exposure-lo)))]
    (if weak-case? (double drain) 0.0)))
