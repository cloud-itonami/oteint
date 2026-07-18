(ns oteint.advisor-llm
  "murakumo-main LLM advisor — the real-LLM Advisor (Phase 2). Implements the
  SAME oteint.advisor/Advisor protocol as MockAdvisor, and is still PROPOSE-ONLY
  (the AttributionGovernor decides — tsukuroi).

  Resolves the fleet `murakumo-main` alias (ADR-2607173100: GET
  api.murakumo.cloud/infer/models/murakumo-main → {endpoint, alias-for}) and
  POSTs an OpenAI chat-completions request. The LLM enriches actor-cluster +
  confidence for entities the registry already deems attributable; it does NOT
  bypass the Governor's evidence/coherence/disclosure floors.

  Injectable `call-fn` with signature (fn [messages] -> response-body-string):
  the real one closes over the resolved endpoint/model/auth; tests inject a
  plain mock so the logic is exercised offline. The MockAdvisor stays
  cljs/WASM-portable; this namespace is JVM-only.

  Observed activity is DATA only — the prompt sends :kind labels + stock
  numbers, never raw payloads interpreted as instructions (safety floor ⑤).

  Requires clojure.data.json (deps.edn :dev / :phase2). JVM-only."
  (:require [clojure.data.json :as json]
            [oteint.advisor :refer [Advisor]]
            [oteint.operation :as op]
            [oteint.registry :as reg]
            [oteint.facts :as facts])
  (:import [java.net URL HttpURLConnection]
           [java.io BufferedReader InputStreamReader]))

(def ^:const alias-url "https://api.murakumo.cloud/infer/models/murakumo-main")

;; endpoint-only fallback (ADR-2607173100: model name not hardcoded — follows
;; whatever the endpoint is serving). murakumo-main is accepted as the model.
(def ^:const fallback {:endpoint "https://infer.murakumo.cloud/v1/chat/completions"
                       :model    "murakumo-main"})

;; ---------- alias resolution + real call-fn (JVM) ----------

(defn- slurp-url
  ^String [^String url-str ^long timeout-ms]
  (let [conn ^HttpURLConnection (.openConnection (URL. url-str))]
    (try
      (.setRequestProperty conn "Accept" "application/json")
      (.setConnectTimeout conn (int timeout-ms))
      (.setReadTimeout conn (int timeout-ms))
      (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream conn)))]
        (apply str (line-seq r)))
      (finally (.disconnect conn)))))

(defn resolve-main-model
  "Resolve the murakumo-main alias → {:endpoint :model}. Falls back to the
  endpoint-only default on any failure (ADR-2607173100). One GET; JVM."
  ([] (resolve-main-model 20000))
  ([timeout-ms]
   (try
     (let [d (json/read-str (slurp-url alias-url timeout-ms) :key-fn keyword)]
       (if (:endpoint d)
         {:endpoint (:endpoint d) :model (or (:alias-for d) "murakumo-main")}
         fallback))
     (catch Exception _ fallback))))

(defn real-call-fn
  "Build the real LLM call-fn (fn [messages] -> body-string) by closing over a
  resolved endpoint/model. Reads GFTD_LLM_TOKEN from env for auth (safety floor
  ① — credential via env, never hand-entered). JVM."
  ([] (let [m (resolve-main-model)] (real-call-fn (:endpoint m) (:model m))))
  ([endpoint model]
   (fn [messages]
     (let [token (System/getenv "GFTD_LLM_TOKEN")
           body  (json/write-str {:model model :max_tokens 4000 :stream false
                                  :messages messages})
           conn  ^HttpURLConnection (.openConnection (URL. endpoint))]
       (try
         (.setRequestMethod conn "POST")
         (.setRequestProperty conn "Content-Type" "application/json")
         (.setRequestProperty conn "Accept" "application/json")
         (when (seq token)
           (.setRequestProperty conn "Authorization" (str "Bearer " token)))
         (.setDoOutput conn true)
         (.setConnectTimeout conn 60000) (.setReadTimeout conn 120000)
         (let [os (.getOutputStream conn)]
           (.write os (.getBytes body "UTF-8")) (.flush os) (.close os))
         (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream conn)))]
           (apply str (line-seq r)))
         (finally (.disconnect conn)))))))

;; ---------- prompt + parse ----------

(defn- stock-summary
  "DATA-only summary for the prompt: entity, suspicion/coherence, evidence
  :kind labels (no payloads)."
  [stock]
  {:entity         (:entity stock)
   :suspicion      (:suspicion stock)
   :coherence      (:coherence stock)
   :evidence-kinds (mapv :kind (:evidence stock))})

(defn- attribution-prompt
  "Build the user message. Ask the LLM to propose, for each attributable entity,
  a threat-actor cluster id (from the known set) + confidence ∈ [0,1], as a
  strict JSON array. The LLM PROPOSES; it does not adjudicate."
  [attributable]
  (let [clusters (keys facts/ttp-profiles)]
    (str
     "You are a defensive threat-actor attribution ASSISTANT. You PROPOSE only;\n"
     "a separate Governor adjudicates. For each entity, propose the best-matching\n"
     "threat-actor cluster and a confidence in [0,1]. Respond STRICTLY as a JSON\n"
     "array of {\"entity\",\"cluster\",\"confidence\"}. Known clusters: "
     (pr-str clusters) "\n"
     "If unsure, use cluster null and low confidence. Do not invent entities.\n\n"
     "Entities (already cleared the evidence/coherence floors):\n"
     (json/write-str (mapv stock-summary attributable)))))

(defn- parse-llm-proposals
  "Parse a chat-completions response body → seq of {entity cluster confidence}.
  Tolerant: extracts the first JSON array in the message content."
  [body]
  (try
    (let [resp    (json/read-str body :key-fn keyword)
          content (get-in resp [:choices 0 :message :content] "")
          lo      (.indexOf content "[")
          hi      (.lastIndexOf content "]")]
      (if (and (<= 0 lo) (pos? hi) (> hi lo))
        (json/read-str (subs content lo (inc hi)) :key-fn keyword)
        []))
    (catch Exception _ [])))

(defn- proposal-from-llm
  "Build a Proposal from an LLM suggestion + the source stock. Always kind
  :attribute (the Governor still gates + forces human disclosure)."
  [stock {:keys [cluster confidence]}]
  (let [conf (double (or confidence (:suspicion stock) 0.0))]
    (op/->Proposal :attribute
                   (:entity stock)
                   (keyword (or cluster "actor/unknown"))
                   conf
                   (mapv :event-ref (:evidence stock))
                   conf)))

;; ---------- Advisor protocol impl ----------

(defn llm-advise
  "Turn stocks into proposals via the LLM. Attributable stocks (registry-ready)
  are enriched by the LLM (cluster + confidence); the rest get :observe-deeper.
  `call-fn` is (fn [messages] -> body-string); injectable (real or mock)."
  ([call-fn stocks] (llm-advise call-fn stocks facts/default-params))
  ([call-fn stocks params]
   (let [attributable (filter #(reg/ready-to-attribute? % params) stocks)
         others       (remove #(reg/ready-to-attribute? % params) stocks)
         llm-body     (if (seq attributable)
                        (try (call-fn [{:role "system"
                                        :content "Defensive attribution assistant; proposes only."}
                                       {:role "user"
                                        :content (attribution-prompt attributable)}])
                             (catch Exception _ "{}"))
                        "{}")
         suggestions  (parse-llm-proposals llm-body)
         by-entity    (into {} (map (juxt :entity identity) suggestions))]
     (concat
      (for [s attributable]
        (if-let [sgg (get by-entity (:entity s))]
          (proposal-from-llm s sgg)
          (op/->Proposal :attribute (:entity s) :actor/unknown
                         (double (:suspicion s)) (mapv :event-ref (:evidence s))
                         (double (:suspicion s)))))
      (for [s others]
        (op/->Proposal :observe-deeper (:entity s) nil
                       (double (:suspicion s)) [] (double (:suspicion s))))))))

(defrecord MurakumoAdvisor [call-fn]
  Advisor
  (advise [_ stocks params] (llm-advise call-fn stocks params)))

(defn murakumo-advisor
  "Real-LLM advisor. With no args: resolves murakumo-main + builds the real
  HTTP call-fn. Pass an explicit call-fn (fn [messages] -> body-string) for
  testing."
  ([] (->MurakumoAdvisor (real-call-fn)))
  ([call-fn] (->MurakumoAdvisor call-fn)))
