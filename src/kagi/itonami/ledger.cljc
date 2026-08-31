(ns kagi.itonami.ledger
  "The free resource: is this append-only audit ledger's hash chain intact.

  `kagi.ledger/verify-chain` does the work — the same function the vault checks
  its own ledger with, so a chain this endpoint calls sound is sound by the
  vault's own definition of the word. The canonicalisation that turns a fact
  into bytes lives there too, and re-deriving it here would be a second
  definition of 'the same fact' that drifts.

  ## What it refuses to answer

  A kagi ledger entry may carry `:ledger/sig` — a HYBRID signature, Ed25519
  and ML-DSA-65 together, sound only while both hold. Verifying it needs the
  actor's public bundle from the key registry and a post-quantum verifier this
  Worker does not carry.

  Checking only the Ed25519 half would be the worst available answer: it looks
  exactly like a full verification and is not one. So a signed ledger is
  refused by name (`:signed-ledger-needs-the-key-registry`) rather than
  half-checked, and an unsigned ledger gets an answer that says in the same
  breath how many signatures it checked — zero.

  This is not a gap to be filled by loosening the check. It is where custody
  is: the registry that says which key was valid when lives with the vault."
  (:require [kagi.ledger :as ledger]))

(def max-entries
  "An upper bound on the chain one request may carry. The walk is CPU the
  caller chose and the seller pays for."
  1024)

(defn- entry-map? [e] (map? e))

(defn problems
  [{:keys [ledger]}]
  (cond-> []
    (not (sequential? ledger)) (conj {:problem :ledger-not-sequential})
    (and (sequential? ledger) (empty? ledger)) (conj {:problem :ledger-empty})
    (and (sequential? ledger) (not (every? entry-map? ledger)))
    (conj {:problem :entry-not-a-map})
    (and (sequential? ledger) (> (count ledger) max-entries))
    (conj {:problem :ledger-too-long :limit max-entries})))

(defn signed-entries
  "Indexes of entries carrying a signature. Reported so the refusal names
  WHICH entries put the request out of this surface's reach."
  [ledger]
  (into [] (keep-indexed (fn [i e] (when (:ledger/sig e) i))) ledger))

(defn verify
  "-> `{:verified? …}`, or `{:error …}`.

  `verify-chain` is called with no crypto provider and a `pub-of` that resolves
  nothing. That combination is safe only because every signed chain has already
  been refused above: with no signatures present the provider is never reached,
  and an actor the registry cannot resolve is exactly the unsigned case
  `verify-chain` documents as permitted."
  [{:keys [ledger] :as request}]
  (let [problems (problems request)]
    (if (seq problems)
      {:error :invalid-request :problems problems}
      (let [signed (signed-entries ledger)]
        (if (seq signed)
          {:error :signed-ledger-needs-the-key-registry
           :signed-entries signed
           :detail (str "entries " (pr-str signed) " carry :ledger/sig. That "
                        "signature is Ed25519 AND ML-DSA-65 together; this "
                        "surface can check neither without the actor's public "
                        "bundle, and checking one half would look like "
                        "checking both. Verify a signed chain where the key "
                        "registry is — kotoba-lang/kagi.")}
          (let [{:keys [ok? broken-at why]}
                (ledger/verify-chain ledger nil (constantly nil))]
            (cond-> {:verified? ok?
                     :entries (count ledger)
                     ;; Said out loud, every time, so a green answer can never
                     ;; be read as more than it is.
                     :signatures {:present 0 :checked 0
                                  :why "this surface verifies hash links only"}}
              (not ok?) (assoc :broken-at broken-at :why why))))))))
