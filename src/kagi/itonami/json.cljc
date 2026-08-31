(ns kagi.itonami.json
  "Keywords survive the trip into JSON with their namespace attached.

  `clj->js` renders a keyword with `name`, which DROPS the namespace: the
  refusal that names `:field/value` arrives as `\"value\"`, and an echoed
  operation `:item/create` arrives as `\"create\"`. Both read as a different,
  more general thing than what was meant — and `:item/update` and
  `:share/update` would arrive identical.

  Measured 2026-08-31 against the live mount before this existed:
  `POST /item/classify` with a password answered
  `{\"error\":\"plaintext-value-received\",\"keys\":[\"value\"]}`. The key it was
  naming is `:field/value`; a caller grepping their payload for `value` finds
  every field they have.

  So keywords are rendered qualified, in key and value position alike, before
  `clj->js` sees them. Without the leading colon: this is JSON, and a consumer
  splitting on `/` should not also have to strip punctuation."
  (:require [clojure.string :as str]))

(defn qualified-name
  "`:field/value` -> \"field/value\", `:commit` -> \"commit\"."
  [k]
  (if-let [ns' (namespace k)]
    (str ns' "/" (name k))
    (name k)))

(defn jsonable
  "Walk `x`, replacing every keyword with its qualified name.

  Sets become vectors — JSON has no set, and `clj->js` would otherwise render
  one as an object with `true` values. Everything else is left alone: numbers,
  strings, booleans and nil already mean in JSON what they mean here."
  [x]
  (cond
    (keyword? x) (qualified-name x)
    (map? x) (into {} (map (fn [[k v]] [(jsonable k) (jsonable v)])) x)
    (set? x) (mapv jsonable (sort-by str x))
    (sequential? x) (mapv jsonable x)
    :else x))
