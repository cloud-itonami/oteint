(ns oteint.identity-test
  "CACAO identity round-trip: load-or-create-identity! generates a valid did:key
  + IPNS graph, persists the seed gitignored, and is idempotent (re-load yields
  the same did — no regeneration). Uses a unique temp actor + cleans up in
  finally so no private key ever lingers in the working tree."
  (:require [clojure.test :refer [deftest is testing]]
            [oteint.identity :as id]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]))

(defn- cov-actor [] (str "oteint-cov-" (System/nanoTime)))
(defn- cov-path [actor] (io/file (str "." actor) "identity.edn"))

(deftest load-or-create-identity-derives-valid-did-and-graph
  (let [actor (cov-actor)
        path  (cov-path actor)]
    (try
      (let [id (id/load-or-create-identity! actor)]
        (is (= actor (:actor id)))
        (is (re-matches #"^did:key:z6Mk.+" (:did id)))
        (is (re-matches #"^k51qzi.+" (:graph id)))
        (is (.exists path) "seed persisted to .{actor}/identity.edn")
        (is (= (:did id) (:did (id/load-or-create-identity! actor)))
            "idempotent — re-load yields the same did (no regeneration)"))
      (testing ":graph is derived not persisted — the on-disk file has no :graph"
        (let [on-disk (read-string (slurp path))]
          (is (not (contains? on-disk :graph)))))
      (finally
        (when (.exists path) (Files/delete (.toPath path)))
        (let [d (io/file (str "." actor))] (when (.exists d) (.delete d)))))))
