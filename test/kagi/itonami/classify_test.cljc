(ns kagi.itonami.classify-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kagi.itonami.classify :as classify]))

(defn- shape
  "An item as a caller would hand it over: types and titles, no values."
  [fields]
  {:item/category :login
   :item/title "GitHub"
   :item/sections [{:section/title "Login" :section/fields (vec fields)}]})

(deftest concealed-field-must-be-sealed-without-the-caller-flag
  ;; The reason this endpoint derives instead of reading `:field/sensitive?`.
  ;; `kagitaba.item/sensitive-fields` filters on that flag, so on a hand-built
  ;; shape it answers "nothing to seal" for an item made entirely of
  ;; passwords. If this test ever passes with an empty :fields the derivation
  ;; has been replaced by the flag again.
  (let [r (classify/classify {:item (shape [{:field/title "password"
                                             :field/type :concealed}])})]
    (is (nil? (:error r)))
    (is (true? (:must-seal? r)))
    (is (= 1 (:restricted-count r)))
    (is (= :restricted (-> r :fields first :classification)))
    (is (nil? (-> r :fields first :caller-said-sensitive?))
        "no flag was sent, so none is echoed")))

(deftest a-contradicting-caller-flag-is-reported-not-obeyed
  (let [r (classify/classify {:item (shape [{:field/title "password"
                                             :field/type :concealed
                                             :field/sensitive? false}])})]
    (is (true? (:must-seal? r)) "the type decides")
    (is (= 1 (count (:disagreements r))))
    (is (false? (-> r :fields first :caller-said-sensitive?)))))

(deftest ordinary-fields-need-no-sealing
  (let [r (classify/classify {:item (shape [{:field/title "username"
                                             :field/type :string}])})]
    (is (false? (:must-seal? r)))
    (is (= :internal (-> r :fields first :classification)))))

(deftest an-unknown-type-fails-closed-and-says-it-is-unknown
  (let [r (classify/classify {:item (shape [{:field/title "?"
                                             :field/type :not-a-real-type}])})]
    (is (= :restricted (-> r :fields first :classification))
        "kagitaba.field/classification fails closed on a type it has no name for")
    (is (false? (-> r :fields first :type-known?)))
    (is (true? (:must-seal? r)))))

(deftest a-field-value-is-refused-by-name
  ;; The control for the invariant this actor is built around. It must refuse
  ;; FOR THIS REASON — a request that merely fails is not evidence, since a
  ;; malformed body would fail too.
  (let [r (classify/classify {:item (shape [{:field/title "password"
                                             :field/type :concealed
                                             :field/value "hunter2"}])})]
    (is (= :plaintext-value-received (:error r)))
    (is (= [:field/value] (:keys r)))
    (is (nil? (:fields r)) "no classification is returned alongside a refusal")))

(deftest an-explicit-nil-value-is-still-a-value-key
  (let [r (classify/classify {:item (shape [{:field/title "password"
                                             :field/type :concealed
                                             :field/value nil}])})]
    (is (= :plaintext-value-received (:error r))
        "the key was in the payload; telling the caller now is cheaper than later")))

(deftest every-value-bearing-key-is-refused
  (doseq [[k v] [[:item/notes "my recovery phrase"]
                 [:item/username "jun"]
                 [:item/password-history [{:value "old" :time 1}]]]]
    (testing (str k)
      (let [r (classify/classify
               {:item (assoc (shape [{:field/title "password"
                                      :field/type :concealed}]) k v)})]
        (is (= :plaintext-value-received (:error r)))
        (is (= [k] (:keys r)))))))

(deftest the-value-check-runs-before-the-structural-one
  ;; Both wrong: no category (structural) and a password (fatal). The caller
  ;; needs to hear about the password.
  (let [r (classify/classify {:item {:item/sections
                                     [{:section/fields
                                       [{:field/type :concealed
                                         :field/value "hunter2"}]}]}})]
    (is (= :plaintext-value-received (:error r)))))

(deftest a-malformed-item-is-a-request-problem
  (is (= :invalid-request (:error (classify/classify {:item "not a map"}))))
  (is (= :invalid-request (:error (classify/classify {:item {:item/category "login"}}))))
  (is (some #(= :sections-not-sequential (:problem %))
            (:problems (classify/classify {:item {:item/category :login
                                                  :item/sections {}}})))))

(deftest a-malformed-body-does-not-throw
  ;; `problems` and `values-present` both walk before validation, so the walk
  ;; must be total. A throw here would answer 500 — "the seller is broken" —
  ;; for a request that is merely wrong.
  (doseq [item [{:item/sections [42]}
                {:item/sections [{:section/fields "no"}]}
                {:item/sections [{:section/fields [7]}]}]]
    (is (= :invalid-request (:error (classify/classify {:item item})))
        (str "walked without throwing: " (pr-str item)))))

(deftest an-oversized-item-is-bounded
  (let [many (repeat (inc classify/max-fields) {:field/title "f" :field/type :string})
        r (classify/classify {:item (shape many)})]
    (is (= :invalid-request (:error r)))
    (is (some #(= :too-many-fields (:problem %)) (:problems r)))))

(deftest an-unknown-category-is-reported-not-refused
  ;; kagitaba's policy is that an unknown category never loses the item.
  (let [r (classify/classify {:item (assoc (shape [{:field/title "p"
                                                    :field/type :concealed}])
                                           :item/category :not-a-1password-category)})]
    (is (nil? (:error r)))
    (is (false? (:category-known? r)))
    (is (true? (:must-seal? r)) "classification does not depend on the category")))
