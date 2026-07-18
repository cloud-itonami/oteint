(ns oteint.identity
  "CACAO / did:key identity for the oteint actor — minted and held in the actor's
  OWN runtime (CLAUDE.md kotoba-server: 'actor が自分の鍵で CACAO を自己発行'),
  never a shared operator secret. Persists the raw Ed25519 seed at
  .oteint/identity.edn (gitignored, NEVER committed) and derives the did:key
  (z6Mk…) + the key-derived IPNS graph name (k51qzi…).

  The actor's graph IS its key (AUTHORITY = signature over the IPNS name, not a
  server): holding the seed makes oteint the owner of its attribution graph, with
  depth-1 self-mint structurally authorized — no token hand-off needed.

  Adapted from cloud-itonami/identity.clj (identity-gen subset; CACAO *mint* for
  kotobase.net auth is a separate runtime step needing kotoba-lang/cacao — not
  included here). JVM-only (java.security.SecureRandom / java.nio.file)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ed25519.core :as ed]
            [ipns.core :as ipns])
  (:import (java.security SecureRandom)
           (java.nio.file Files StandardOpenOption FileAlreadyExistsException)))

(def ^:private actor-pattern
  "actor must be a simple identifier (letters/digits/hyphens/underscores). Without
  this, an actor containing `..`/`/` could resolve the identity path outside the
  .{actor}/ gitignored sandbox — and load-or-create-identity! writes a fresh
  private signing key wherever the path points. Pure tightening."
  #"^[a-zA-Z0-9_-]+$")

(defn- identity-path ^java.io.File [actor]
  (when-not (and (string? actor) (re-matches actor-pattern actor))
    (throw (ex-info "invalid actor identifier" {:actor actor})))
  (io/file (str "." actor) "identity.edn"))

(defn- random-seed ^bytes []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    b))

(defn- read-identity-with-retry
  "slurp+parse with a short bounded retry — Files/write's CREATE_NEW is atomic
  for WHO creates the file, but the open(CREATE)-then-write(bytes) is two
  syscalls, so a concurrent reader between them sees empty (edn/read-string \"\")
  => nil, not an error. The winner's content lands microseconds later."
  [path]
  (loop [attempts 0]
    (let [parsed (try
                   (let [content (slurp path)]
                     (when-not (str/blank? content)
                       (edn/read-string content)))
                   (catch Exception _ nil))]
      (cond
        parsed parsed
        (< attempts 50) (do (Thread/sleep 5) (recur (inc attempts)))
        :else (throw (ex-info "identity file unreadable"
                              {:path (str path) :attempts attempts}))))))

(defn- graph-name
  "The actor's own graph: the key-derived libp2p-key IPNS name ('k51…') of its
  Ed25519 public key. Pure function of seed-hex — never persisted (every load
  recomputes it, so it can't diverge from the on-disk seed)."
  [seed-hex]
  (ipns/pubkey->name (ed/pubkey-from-seed (ed/unhex seed-hex))))

(defn load-or-create-identity!
  "Load the actor's persisted identity, or generate + persist a new one.
  Returns {:actor :seed-hex :did :graph}. :seed-hex is the private signing key —
  the .{actor}/ directory MUST stay gitignored. :graph is derived, not persisted.

  First-time write uses Files/write CREATE_NEW (OS-atomic): concurrent first
  callers converge on the winner's identity instead of each generating a
  distinct orphan key."
  [actor]
  (let [path (identity-path actor)
        identity (if (.exists path)
                   (read-identity-with-retry path)
                   (let [seed     (random-seed)
                         seed-hex (ed/hexify seed)
                         did      (ed/did-key-from-seed seed)
                         identity {:actor actor :seed-hex seed-hex :did did}]
                     (io/make-parents path)
                     (try
                       (Files/write (.toPath path)
                                    (.getBytes (pr-str identity) "UTF-8")
                                    (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW]))
                       identity
                       (catch FileAlreadyExistsException _
                         (read-identity-with-retry path)))))]
    (assoc identity :graph (graph-name (:seed-hex identity)))))

(defn -main
  "usage: clojure -M:identity show <actor>
  Prints the actor's did:key + IPNS graph name. The private seed stays in
  .{actor}/identity.edn (gitignored) — never printed."
  [& args]
  (let [[cmd actor] args]
    (cond
      (and (= cmd "show") actor)
      (let [id (load-or-create-identity! actor)]
        (println "actor:" (:actor id))
        (println "did:  " (:did id))
        (println "graph:" (:graph id))
        (println (str "(private seed stays in ." actor "/identity.edn, gitignored — never printed)")))
      :else
      (do (println "usage: clojure -M:identity show <actor>")
          (System/exit 1)))))
