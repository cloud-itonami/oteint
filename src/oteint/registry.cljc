(ns oteint.registry
  "Verdict predicates that compose the system-dynamics state with the Governor
  thresholds. Pure. These are the convergence tests (ADR-2607182000 §3):
  an entity is READY to be proposed for attribution only when suspicion,
  coherence, and evidence all clear their floors simultaneously."
  (:require [oteint.sim :as sim]
            [oteint.facts :as facts]))

(defn suspicion-threshold?
  "Has suspicion crossed the propose-onset floor?"
  ([stock] (suspicion-threshold? stock facts/default-params))
  ([stock params]
   (let [onset (get-in params [:obs-depth :observe-onset] 0.3)]
     (>= (:suspicion stock) onset))))

(defn coherence-matched?
  "Does coherence clear the Governor's coherence threshold?"
  ([stock] (coherence-matched? stock facts/default-params))
  ([stock params]
   (>= (:coherence stock) (:coherence-threshold params))))

(defn evidence-sufficient?
  "Does the (decayed, live) evidence clear the Governor's evidence floor —
  both count AND weight?"
  ([stock] (evidence-sufficient? stock facts/default-params))
  ([stock params]
   (let [{:keys [count weight]} (sim/evidence-summary stock params)
         {:keys [min-count min-weight]} (:floor params)]
     (and (>= count min-count) (>= weight min-weight)))))

(defn ready-to-attribute?
  "Convergence: suspicion onset AND coherence floor AND evidence floor all clear.
  Only when this is true does the advisor emit an :attribute proposal. Pure."
  ([stock] (ready-to-attribute? stock facts/default-params))
  ([stock params]
   (and (suspicion-threshold? stock params)
        (coherence-matched? stock params)
        (evidence-sufficient? stock params))))

(defn release-focus?
  "Convergence (release branch): suspicion has decayed below a low water mark
  AND evidence has expired below floor. The entity is released from focus
  (B3 — the sun does not hold grudges without fresh evidence). Pure."
  ([stock] (release-focus? stock facts/default-params))
  ([stock params]
   (let [lo (* 0.5 (get-in params [:obs-depth :observe-onset] 0.3))]
     (and (< (:suspicion stock) lo)
          (not (evidence-sufficient? stock params))))))
