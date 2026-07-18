(ns oteint.sim
  "おてんとさま — the system-dynamics tick engine. Pure: (stock, events, params)
  -> stock'. No I/O, no internal loop. One tick advances all stocks one step.

  This is the OBSERVE+RECORD half of the actor (太陽は照らす). It never proposes,
  never discloses, never contacts anyone — it only advances the dynamical state.
  The advisor (oteint.advisor) reads the resulting stock to form proposals; the
  Governor (oteint.governor) decides them. See ADR-2607182000 §3/§5.

  Portable .cljc. The durable OUTER loop that drives repeated ticks for
  continuous surveillance lives outside the engine (phase.cljc / agent loop);
  this namespace is the pure single-tick transition."
  (:refer-clojure :exclude [dec])
  (:require [oteint.operation :as op]
            [oteint.kernels.dynamics :as dyn]
            [oteint.facts :as facts]))

(defn init-stock
  "Fresh per-entity stock state."
  ([entity] (op/->StockSnapshot entity 0.0 0.0 [] 0.0 0.0 0))
  ([entity tick] (op/->StockSnapshot entity 0.0 0.0 [] 0.0 0.0 tick)))

(defn- annotate-event
  "Attach looked-up severity to an event (input gain, not an accusation)."
  [event]
  (assoc event :severity (facts/severity-of (:kind event))))

(defn tick
  "Advance one entity's stock state by ONE tick given a batch of new activity
  events (possibly empty). Pure. Returns a new StockSnapshot.

  Per-tick transitions:
    1. suspicion decays (B3); evidence items age (their effective weight drops)
    2. observation depth is determined by current suspicion (B2 proportionality)
    3. each new event contributes effective inflow (R1, bounded by B2),
       adds a decaying EvidenceItem, adds exposure-cost, and nudges coherence
       toward the best-matching known TTP cluster (low-pass)
    4. B1 false-positive correction drains suspicion on weak high-suspicion cases
    5. max observation depth actually used is recorded for the Governor's
       proportionality-audit (invariant ④)
    6. clamp suspicion/coherence to [0,1]"
  ([stock events] (tick stock events facts/default-params))
  ([stock events params]
   (let [tick-no   (inc (:last-tick stock))
         dt        1
         tau       (:suspicion-tau params)
         sus0      (dyn/decay-suspicion (:suspicion stock) dt tau)
         depth-now (dyn/observation-depth sus0 (:obs-depth params))
         known     (keys facts/ttp-profiles)
         ;; process events into a per-tick delta accumulator
         acc (reduce
              (fn [acc event]
                (let [ev     (annotate-event event)
                      kind   (:kind ev)
                      inflow (dyn/effective-inflow ev sus0 (:obs-depth params)
                                                   (:base-rate params))
                      ;; best TTP match signal across known clusters for this kind
                      signal (reduce #(max %1 (facts/match-signal %2 kind)) 0.0 known)
                      item   (op/->EvidenceItem
                              inflow (:evidence-half-life params) tick-no
                              kind (:ts ev))]
                  (-> acc
                      (update :suspicion  + inflow)
                      (update :evidence    conj item)
                      (update :exposure    + (facts/exposure-of kind))
                      (update :peak-signal max (double signal)))))
              {:suspicion sus0 :evidence [] :exposure 0.0 :peak-signal 0.0}
              events)
         sus-mid   (dyn/clamp01 (:suspicion acc))
         signal    (:peak-signal acc)
         exposure  (+ (:exposure-cost stock) (:exposure acc))
         ;; coherence low-pass toward peak match signal observed this tick
         coh'      (dyn/coherence-after (:coherence stock) signal
                                         (:coherence-easing params))
         ;; B1 false-positive correction drains weak high-suspicion cases
         corr      (dyn/fp-correction-outflow sus-mid coh' exposure
                                               (:fp-correction params))
         sus-final (dyn/clamp01 (- sus-mid corr))
         max-depth (max (:max-obs-depth stock) depth-now)]
     (op/->StockSnapshot
      (:entity stock)
      sus-final
      (double coh')
      (into (:evidence stock) (:evidence acc))
      exposure
      max-depth
      tick-no))))

(defn evidence-summary
  "Compute (count, total-live-weight) for a stock at its current tick, for the
  Governor's evidence-floor. Drops expired evidence (B3)."
  ([stock] (evidence-summary stock facts/default-params))
  ([stock params]
   (let [now (:last-tick stock)
         eps (:evidence-eps params)
         live (filter #(>= (dyn/evidence-weight-at (:weight %)
                                                   (- now (:added-tick %))
                                                   (:decay-half-life %))
                           eps)
                      (:evidence stock))
         total (reduce + 0.0 (map :weight live))]
     {:count (count live)
      :weight (double total)})))
