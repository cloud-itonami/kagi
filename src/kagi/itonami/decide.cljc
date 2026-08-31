(ns kagi.itonami.decide
  "The paid resource: one staged-rollout gate decision.

  This namespace holds no policy. `kagi.phase/gate` is the same function the
  vault applies to a governor's verdict before anything is committed, and the
  request is shaped into its arguments and its answer shaped back — nothing in
  between. That is the point of selling it: a second gate at the edge would be
  a copy that drifts from the one that actually runs, and a gate that
  disagrees with the vault is worse than no gate.

  ## What the gate is for

  A vault is adopted in stages, and the gate's single rule is that a phase may
  only ADD caution — it can turn `:commit` into `:escalate` or `:hold`, and it
  can never turn `:hold` into `:commit`. That direction is the whole safety
  property.

  What the four phases actually do, read off `kagi.phase/phases` rather than
  off their labels: phase 0 permits no writes at all, and phases 1, 2 and 3
  all permit every write. They differ only in which writes may commit
  UNATTENDED — 1 adds create and update, 2 adds share/grant, 3 adds the rest.
  So `:phase-disabled` is phase 0's answer and `:phase-approval` is every
  other phase's answer for a write outside its automatic set. (The labels
  suggest otherwise: `self-vault` sounds like it excludes sharing, and it does
  not — sharing at phase 1 escalates to a human rather than being refused.)

  The answer names which of the two reasons applied rather than collapsing
  both into 'denied'. They call for different actions: one is a phase change,
  the other is a person.

  ## Why the disposition vocabulary is checked here

  `gate` returns a read operation's disposition unchanged. Hand it a
  disposition it has never heard of and it hands that back, which would let a
  caller put any keyword in and read the echo as a decision. The three
  dispositions are the three branches of `kagi.governor/verdict->disposition`;
  anything else is refused before the gate sees it."
  (:require [kagi.phase :as phase]
            [kagi.vault :as vault]))

(def dispositions
  "The disposition vocabulary, from `kagi.governor/verdict->disposition`."
  #{:commit :escalate :hold})

(def caution
  "How much caution each disposition carries. The gate's rule is that its
  answer never sits BELOW its input on this scale."
  {:commit 0 :escalate 1 :hold 2})

(def operations
  "Every vault operation the gate knows, read and write."
  (into vault/read-ops vault/write-ops))

(defn problems
  "Structural problems with a decision request, as a vector.

  Returned in full rather than one at a time: each round trip past a 402 is a
  payment."
  [{:keys [phase op disposition]}]
  (cond-> []
    (not (integer? phase)) (conj {:problem :phase-not-an-integer})
    (and (integer? phase) (not (contains? phase/phases phase)))
    (conj {:problem :phase-unknown :known (vec (sort (keys phase/phases)))})
    (not (keyword? op)) (conj {:problem :op-not-a-keyword})
    (and (keyword? op) (not (contains? operations op)))
    (conj {:problem :op-unknown :known (vec (sort operations))})
    (not (contains? dispositions disposition))
    (conj {:problem :disposition-unknown :known (vec (sort dispositions))})))

(defn decide
  "-> the gated decision, or `{:error :invalid-request :problems [...]}`."
  [{:keys [phase op disposition] :as request}]
  (let [problems (problems request)]
    (if (seq problems)
      {:error :invalid-request :problems problems}
      (let [gated (phase/gate phase {:op op} disposition)
            label (:label (get phase/phases phase))]
        {:phase phase
         :phase-label label
         :op op
         :requested disposition
         :disposition (:disposition gated)
         :reason (:reason gated)
         ;; Computed, not asserted. The gate's contract is that it never
         ;; returns less caution than it was given, and this is that contract
         ;; evaluated on the actual answer rather than a constant `false`
         ;; restating the docstring. If it is ever true the caller sees it in
         ;; the same response as the decision it describes.
         :relaxed? (< (get caution (:disposition gated) 2)
                      (get caution disposition 2))}))))
