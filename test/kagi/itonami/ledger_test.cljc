(ns kagi.itonami.ledger-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kagi.itonami.ledger :as mount]
            [kagi.ledger :as ledger]))

(defn- chain
  "An unsigned chain of `n` facts, built by the engine itself. Building it any
  other way would test this test's idea of the canonicalisation rather than
  kagi's."
  [n]
  (reduce (fn [acc i]
            (conj acc (ledger/make-entry acc {:actor "did:key:zTest"
                                              :fact/n i} nil nil)))
          []
          (range n)))

(deftest an-intact-chain-verifies
  (let [r (mount/verify {:ledger (chain 4)})]
    (is (true? (:verified? r)))
    (is (= 4 (:entries r)))
    (is (= {:present 0 :checked 0
            :why "this surface verifies hash links only"}
           (:signatures r))
        "a green answer says how many signatures it checked, every time")))

(deftest a-tampered-fact-breaks-the-chain-at-that-entry
  (let [c (vec (chain 4))
        tampered (assoc-in c [2 :fact/n] 99)
        r (mount/verify {:ledger tampered})]
    (is (false? (:verified? r)))
    (is (= 2 (:broken-at r)))
    (is (= :hash (:why r)))))

(deftest a-relinked-entry-is-caught-as-a-link-break
  (let [c (vec (chain 4))
        cut (assoc-in c [2 :ledger/prev-hash] (:ledger/hash (first c)))
        r (mount/verify {:ledger cut})]
    (is (false? (:verified? r)))
    (is (= 2 (:broken-at r)))))

(deftest a-signed-chain-is-refused-by-name-rather-than-half-checked
  ;; The control for the refusal this namespace exists to make. kagi's
  ;; signature is Ed25519 AND ML-DSA-65; checking one half looks exactly like
  ;; checking both, so the answer must be a refusal that names itself and the
  ;; entries responsible.
  (let [c (vec (chain 3))
        signed (assoc-in c [1 :ledger/sig] {:ed "AA==" :mldsa "AA=="})
        r (mount/verify {:ledger signed})]
    (is (= :signed-ledger-needs-the-key-registry (:error r)))
    (is (= [1] (:signed-entries r)))
    (is (nil? (:verified? r))
        "no verdict is offered alongside the refusal — that is the point")))

(deftest an-empty-or-malformed-ledger-is-a-request-problem
  (is (some #(= :ledger-empty (:problem %)) (:problems (mount/verify {:ledger []}))))
  (is (some #(= :ledger-not-sequential (:problem %))
            (:problems (mount/verify {:ledger {}}))))
  (is (some #(= :entry-not-a-map (:problem %))
            (:problems (mount/verify {:ledger [42]})))))

(deftest an-oversized-ledger-is-bounded
  (let [r (mount/verify {:ledger (vec (repeat (inc mount/max-entries) {:a 1}))})]
    (is (some #(= :ledger-too-long (:problem %)) (:problems r)))))
