(ns kagi.itonami.decide-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kagi.itonami.decide :as decide]
            [kagi.phase :as phase]))

(deftest phase-zero-disables-writes
  (let [r (decide/decide {:phase 0 :op :item/create :disposition :commit})]
    (is (= :hold (:disposition r)))
    (is (= :phase-disabled (:reason r)))
    (is (= :commit (:requested r)) "the request is echoed, not overwritten")))

(deftest phase-one-commits-its-own-vault-writes
  (let [r (decide/decide {:phase 1 :op :item/create :disposition :commit})]
    (is (= :commit (:disposition r)))
    (is (nil? (:reason r)))
    (is (= "self-vault" (:phase-label r)))))

(deftest the-two-refusal-reasons-stay-distinguishable
  ;; Measured, not assumed from the labels. Phase 1 is called "self-vault",
  ;; which sounds like it excludes sharing -- it does not. Its :writes is the
  ;; FULL write set, so :share/grant escalates to a human rather than being
  ;; refused, and :phase-disabled belongs to phase 0 alone. Getting this
  ;; backwards is what this test caught while it was being written.
  (is (= [:hold :phase-disabled]
         ((juxt :disposition :reason)
          (decide/decide {:phase 0 :op :share/grant :disposition :commit})))
      "phase 0 permits no writes at all")
  (is (= [:escalate :phase-approval]
         ((juxt :disposition :reason)
          (decide/decide {:phase 1 :op :share/grant :disposition :commit})))
      "phase 1 permits it, but not unattended")
  (is (= [:commit nil]
         ((juxt :disposition :reason)
          (decide/decide {:phase 2 :op :share/grant :disposition :commit})))
      "phase 2 is where sharing becomes automatic")
  (is (= [:escalate :phase-approval]
         ((juxt :disposition :reason)
          (decide/decide {:phase 1 :op :item/rotate :disposition :commit})))))

(deftest phase-disabled-belongs-to-phase-zero-alone
  ;; The corollary, swept rather than sampled: if any later phase ever starts
  ;; answering :phase-disabled, a write was removed from a phase's :writes and
  ;; callers who were escalating to a person are now silently refused.
  (doseq [phase [1 2 3]
          op decide/operations]
    (is (not= :phase-disabled
              (:reason (decide/decide {:phase phase :op op :disposition :commit})))
        (str "phase " phase " " op))))

(deftest hold-is-never-relaxed-at-any-phase
  (doseq [phase (keys phase/phases)
          op decide/operations]
    (let [r (decide/decide {:phase phase :op op :disposition :hold})]
      (is (= :hold (:disposition r))
          (str "phase " phase " " op " turned :hold into " (:disposition r))))))

(deftest the-gate-never-lowers-caution-anywhere
  ;; The whole sweep: 4 phases x 7 operations x 3 dispositions. `:relaxed?` is
  ;; computed from the answer, so a gate that ever loosened would show up here
  ;; and in the response body of the request that did it.
  (let [combos (for [phase (keys phase/phases)
                     op decide/operations
                     d decide/dispositions]
                 (decide/decide {:phase phase :op op :disposition d}))]
    (is (= 84 (count combos)) "4 phases x 7 ops x 3 dispositions")
    (is (every? #(false? (:relaxed? %)) combos))
    (is (every? #(nil? (:error %)) combos))))

(deftest an-unknown-disposition-is-refused-before-the-gate-sees-it
  ;; The control. `phase/gate` returns a read operation's disposition
  ;; unchanged, so without this check `{:op :item/reveal :disposition :yolo}`
  ;; would come back as `{:disposition :yolo}` and read as a decision.
  (let [r (decide/decide {:phase 1 :op :item/reveal :disposition :yolo})]
    (is (= :invalid-request (:error r)))
    (is (some #(= :disposition-unknown (:problem %)) (:problems r))
        "refused for the disposition, not for something else"))
  (is (= :yolo (:disposition (phase/gate 1 {:op :item/reveal} :yolo)))
      "and this is what the gate would have said on its own"))

(deftest unknown-phases-and-operations-are-refused-by-name
  (let [r (decide/decide {:phase 9 :op :item/create :disposition :commit})]
    (is (some #(= :phase-unknown (:problem %)) (:problems r))))
  (let [r (decide/decide {:phase 1 :op :vault/drop-everything :disposition :commit})]
    (is (some #(= :op-unknown (:problem %)) (:problems r))))
  (let [r (decide/decide {:phase "1" :op "create" :disposition :commit})]
    (is (= 2 (count (filter #(#{:phase-not-an-integer :op-not-a-keyword} (:problem %))
                            (:problems r))))
        "every problem at once: each round trip past a 402 is a payment")))
