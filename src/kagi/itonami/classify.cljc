(ns kagi.itonami.classify
  "The free resource: which fields of an item must be sealed before it is
  stored.

  This namespace holds no taxonomy. `kagitaba.field/classification` is the same
  function the vault's storage path consults, and the request is shaped into
  its arguments and its answer shaped back — nothing in between. A second
  reading of the taxonomy at the edge would be a copy that drifts, and a
  classifier that disagrees with the vault is worse than no classifier: it
  tells a caller a credential is safe to index.

  ## It never receives a secret

  The vault's whole claim is that plaintext and keys do not leave the owner's
  machine. A public endpoint that accepted item VALUES in order to classify
  them would refute that claim on its own, so this one refuses any payload
  carrying a value-bearing key and says which one it found.

  A username is not a secret. It is refused anyway, because a surface that
  accepts some values and not others has to be right about which — and the
  rule 'no values, ever' is one that cannot be got subtly wrong. Types and
  titles are all the taxonomy reads.

  ## Why the caller's own flag is not trusted

  `kagitaba.item/sensitive-fields` filters on `:field/sensitive?`, a flag the
  item constructor sets. Read straight off a hand-built shape it answers
  'nothing to seal' for an item full of `:concealed` fields, because the flag
  was never set — an absent flag and a considered 'no' are the same bytes.
  So sensitivity is DERIVED from `:field/type` here, and a caller's flag that
  contradicts the derivation is reported as a disagreement rather than
  silently overwritten."
  (:require [clojure.string :as str]
            [kagitaba.category :as category]
            [kagitaba.field :as field]))

(def max-fields
  "An upper bound on the fields one request may carry.

  Not a pricing device — this resource is free. A bound exists because the walk
  is CPU the caller chose and the seller pays for."
  512)

(def value-bearing-keys
  "Keys whose presence means the caller sent content rather than shape.

  `:item/password-history` is here for the reason it exists at all: it is a
  list of former passwords, and an endpoint that read one would be the single
  most valuable thing to point at this Worker."
  #{:field/value :item/notes :item/username :item/password-history})

(defn- field-seq
  "Every field map in the item, or nothing.

  Total on purpose: `problems` and `values-present` both walk before the shape
  has been validated, and a walk that throws on a malformed body turns a 400
  into a 500 — which reads as 'the seller is broken' rather than 'your request
  is'."
  [item]
  (let [sections (:item/sections item)]
    (if (sequential? sections)
      (mapcat (fn [s]
                (let [fs (:section/fields s)]
                  (when (sequential? fs) (filter map? fs))))
              (filter map? sections))
      [])))

(defn values-present
  "The value-bearing keys actually found, as a sorted vector of keywords.

  Reported rather than stripped: a caller who sent a password to a public URL
  has a problem that survives this request, and a 200 with the value quietly
  dropped would not tell them they have it."
  [item]
  (let [top (filter #(some? (get item %)) (disj value-bearing-keys :field/value))
        in-fields (when (some #(contains? % :field/value) (field-seq item))
                    [:field/value])]
    ;; `contains?` rather than `some?`: an explicit :field/value of nil is
    ;; still a caller who put the value key in the payload, and telling them
    ;; now is cheaper than telling them after they fill it in.
    (vec (sort (concat top in-fields)))))

(defn problems
  "Structural problems with a classify request, as a vector.

  Returned in full rather than one at a time: a caller fixing a request should
  not have to make one round trip per mistake."
  [{:keys [item]}]
  (let [fields (when (map? item) (field-seq item))]
    (cond-> []
      (not (map? item)) (conj {:problem :item-not-a-map})
      (and (map? item) (not (keyword? (:item/category item))))
      (conj {:problem :category-not-a-keyword})
      (and (map? item) (some? (:item/sections item))
           (not (sequential? (:item/sections item))))
      (conj {:problem :sections-not-sequential})
      (and (map? item) (sequential? (:item/sections item))
           (not (every? map? (:item/sections item))))
      (conj {:problem :section-not-a-map})
      (> (count fields) max-fields)
      (conj {:problem :too-many-fields :limit max-fields}))))

(defn- classify-field [{:keys [field/type field/title field/sensitive?] :as f}]
  (let [known? (contains? field/value-types type)
        label (field/classification type)
        seal? (= :restricted label)]
    (cond-> {:title (some-> title str)
             :type type
             :classification label
             :must-seal? seal?
             ;; `:unknown` is kagitaba's catch-all for field types 1Password
             ;; may add later. It is a KNOWN member of value-types, so it
             ;; classifies :internal — reported here because a caller reading
             ;; 'internal' should be able to tell 'we know this is ordinary'
             ;; from 'we have no name for it yet'.
             :type-known? known?}
      (some? sensitive?)
      (assoc :caller-said-sensitive? (boolean sensitive?))
      (and (some? sensitive?) (not= (boolean sensitive?) seal?))
      (assoc :disagrees? true))))

(defn classify
  "-> the classification, or `{:error :invalid-request :problems [...]}`,
  or `{:error :plaintext-value-received :keys [...]}`.

  The value check runs before the structural one. A request that carries a
  password and is also malformed should hear about the password first."
  [{:keys [item] :as request}]
  (let [found (when (map? item) (values-present item))]
    (cond
      (seq found)
      {:error :plaintext-value-received
       :keys found
       :detail (str "this surface takes shapes, never values. Remove "
                    (str/join ", " (map str found))
                    " and send types and titles only. If one of these reached a "
                    "public URL, treat the credential as disclosed and rotate it.")}

      :else
      (let [problems (problems request)]
        (if (seq problems)
          {:error :invalid-request :problems problems}
          (let [fields (mapv classify-field (field-seq item))
                restricted (filterv :must-seal? fields)]
            {:category (:item/category item)
             :category-known? (category/known? (:item/category item))
             :fields fields
             :restricted-count (count restricted)
             ;; The one bit a storage path needs: may this item go down the
             ;; plaintext-index route, or must it be sealed first.
             :must-seal? (boolean (seq restricted))
             :disagreements (filterv :disagrees? fields)}))))))
