(ns oteint.graph
  "langgraph-clj StateGraph composition for oteint. 1 run = 1 analysis tick.
  Nodes reuse phase.cljc's pure node fns (ingest→simulate→analyze→govern→commit);
  :govern is interrupt-before (human-in-the-loop for :attribute disclosure /
  tsukuroi). Checkpointed via mem-checkpointer (Datomic in prod via
  langgraph.checkpoint/datomic-checkpointer). See ADR-2607182000 §5/§8.

  This namespace requires langgraph-clj (deps.edn :dev / :phase2). The pure-kernel
  :test path never loads it."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [oteint.phase :as phase]
            [oteint.facts :as facts]))

(def default-channels
  "Graph state channels. Injected inputs (:events :stock-of :advisor :append-fn
  :params :case :now :opts) come from the invoke input; produced channels
  (:grouped :stocks :proposals :governor :charter) are filled by the nodes.
  :audit accumulates (reducer into) — the append-only audit trail."
  {:events      {:default nil}
   :stock-of    {:default (fn [_] nil)}
   :advisor     {:default nil}
   :append-fn   {:default (fn [_] nil)}
   :params      {:default facts/default-params}
   :case        {:default nil}
   :now         {:default 0}
   :opts        {:default {:store-kind :mem :inference-gateway nil}}
   :grouped     {:default {}}
   :stocks      {:default {}}
   :proposals   {:default []}
   :governor    {:default nil}
   :charter     {:default nil}
   :audit       {:reducer into :default []}})

(defn build
  "Build the oteint analysis-tick StateGraph.
  Opts:
    :checkpointer     langgraph checkpointer (default mem-checkpointer)
    :interrupt-before #{nodes} to pause before (default #{:govern} — the
                        human-in-the-loop gate for :attribute disclosure).
  Each node returns a partial state update (only changed channels)."
  [& [{:keys [checkpointer interrupt-before]
       :or {checkpointer (cp/mem-checkpointer)
            interrupt-before #{:govern}}}]]
  (-> (g/state-graph {:channels default-channels})
      (g/add-node :ingest
        (fn [s] {:grouped (phase/ingest (:events s))}))
      (g/add-node :simulate
        (fn [s] {:stocks (phase/simulate (:grouped s) (:stock-of s) (:params s))}))
      (g/add-node :analyze
        (fn [s] {:proposals (phase/analyze (:advisor s) (:stocks s) (:params s))}))
      (g/add-node :govern
        (fn [s] {:governor (phase/govern (:proposals s) (:stocks s)
                                         (:append-fn s) (:params s))}))
      (g/add-node :commit
        (fn [s] {:charter (phase/summarize-charter (:stocks s) (:governor s)
                                                    (:case s) (:now s)
                                                    (:params s) (:opts s))
                 :audit   [{:t :tick :now (:now s)
                            :halted? (-> s :charter :halted?)}]}))
      (g/set-entry-point :ingest)
      (g/add-edge :ingest :simulate)
      (g/add-edge :simulate :analyze)
      (g/add-edge :analyze :govern)
      (g/add-edge :govern :commit)
      (g/set-finish-point :commit)
      (g/compile-graph {:checkpointer checkpointer
                        :interrupt-before interrupt-before})))

(defn run
  "Convenience: build + invoke one tick. `input` carries :events :stock-of
  :advisor :append-fn (and optionally :params :case :now :opts). Returns the
  final state (incl. :stocks :governor :charter :audit).
  Pass {:interrupt-before #{}} in opts to run straight through (no pause)."
  ([input] (run input {}))
  ([input opts]
   (let [graph (build opts)]
     (g/invoke graph input))))
