(ns kagi.itonami.json-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            [kagi.itonami.json :as json]))

(deftest a-namespaced-keyword-keeps-its-namespace
  ;; The bug this namespace exists for: `clj->js` renders :field/value as
  ;; "value", and a caller grepping their payload for `value` finds every
  ;; field they have.
  (is (= "field/value" (json/qualified-name :field/value)))
  (is (= "commit" (json/qualified-name :commit))))

(deftest keywords-are-rendered-in-key-and-value-position
  (is (= {"error" "plaintext-value-received"
          "keys" ["field/value" "item/notes"]}
         (json/jsonable {:error :plaintext-value-received
                         :keys [:field/value :item/notes]})))
  (is (= {"item/category" "login"}
         (json/jsonable {:item/category :login}))))

(deftest operations-that-differ-only-by-namespace-stay-distinct
  (is (not= (json/jsonable :item/update) (json/jsonable :share/update))))

(deftest sets-become-vectors-deterministically
  (is (= ["commit" "escalate" "hold"]
         (json/jsonable #{:hold :commit :escalate}))))

(deftest ordinary-values-are-left-alone
  (is (= {"n" 1 "ok" true "why" nil "s" "text"}
         (json/jsonable {:n 1 :ok true :why nil :s "text"}))))
