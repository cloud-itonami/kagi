#!/usr/bin/env nbb
;; The Kotoba guest against the ClojureScript oracle, input by input.
;;
;; ADR-2607279200 / the Q9 rule: a `.cljc` oracle stays until the slice's gates
;; are in place, and it is a copy kept for comparison — not the meaning. This
;; script is the comparison. It runs every input either side can answer through
;; BOTH implementations and fails on the first disagreement, so the cutover is
;; a measurement rather than a claim.
;;
;; The oracle is `kagitaba.field` and `kagi.phase` themselves — the engine
;; libraries the deleted ClojureScript Worker consumed. Comparing against them
;; rather than against the deleted wrapper is the stronger check: it asks
;; whether the Kotoba guest agrees with the VAULT, which is the property the
;; mount is supposed to have.
;;
;; Fuel: `instantiateKotoba` opens a budget that is spent, never replenished.
;; Measured 2026-08-31: one instance answers 256 `classification` calls and
;; then traps. So instances are made in advance and rotated every 200 calls —
;; the same fuel model the Worker uses, which takes a fresh instance per
;; request.
(ns parity
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [kagi.phase :as phase]
            [kagi.vault :as vault]
            [kagitaba.field :as field]
            [pay.x402 :as x402]))

(def root (js/process.cwd))
(def host-path (path/resolve root "../../kotoba-lang/amu/runtime/browser-host.mjs"))
(def calls-per-instance
  ;; Fuel is spent per UNIT of work, not per call, so "how many calls fit" is a
  ;; property of the function being called rather than a constant. Measured
  ;; 2026-08-31: `classification` gets 256 calls out of one instance and the
  ;; phase gate — which walks `phase-disposition` twice inside `relaxed?` —
  ;; trapped `unreachable` well before 200. Ten is chosen to sit under the
  ;; heaviest export here with room to spare; instances are cheap.
  10)

(def failures (atom []))
(def checks (atom 0))

(defn- guard
  ;; A guest trap arrives as `RuntimeError: unreachable`, which says nothing
  ;; about which input produced it. Naming the comparison turns a wasm trap
  ;; into a located failure.
  [label f]
  (try (f)
       (catch :default e
         (swap! failures conj (str label ": guest trapped — " (str e)))
         ::trapped)))

(defn- fx
  ;; Kotoba export names reach JavaScript verbatim — `type-known?` stays
  ;; `type-known?`, hyphen and question mark included. `(.-type_known_QMARK_ …)`
  ;; is ClojureScript munging applied to a name that was never munged, and it
  ;; reads as undefined rather than as an error at the point of the mistake.
  [g nm] (aget g nm))

(defn- check! [label expected actual]
  (swap! checks inc)
  (when-not (= expected actual)
    (swap! failures conj (str label ": oracle " (pr-str expected)
                              " but guest " (pr-str actual)))))

(defn- runner
  "A function of no arguments returning a guest with fuel left in it."
  [instances]
  (let [used (atom 0) idx (atom 0)]
    (fn []
      (when (>= @used calls-per-instance)
        (reset! used 0)
        (swap! idx inc))
      (swap! used inc)
      (when (>= @idx (count instances))
        (throw (js/Error. "parity: ran out of pre-fuelled instances")))
      (nth instances @idx))))

(defn- compare-all! [g]
  ;; The module's own smoke value first. If this is not 15 the guest is not the
  ;; module this script thinks it is, and every comparison below would be
  ;; measuring the wrong thing.
  (check! "main() smoke" 15 (js/Number ((fx (g) "main"))))

  ;; ── kagitaba's field taxonomy ─────────────────────────────────────────────
  (doseq [t (concat (sort (map name field/value-types))
                    ["not-a-real-type" "" "Concealed" "ssh key" "CONCEALED"])]
    (check! (str "classification " (pr-str t))
            (name (field/classification (keyword t)))
            ((fx (g) "classification") t))
    (check! (str "type-known? " (pr-str t))
            (contains? field/value-types (keyword t))
            ((fx (g) "type-known?") t)))

  ;; ── kagi.phase/gate: every phase x operation x disposition ────────────────
  (doseq [ph (sort (keys phase/phases))
          op (sort (into vault/read-ops vault/write-ops))
          d [:commit :escalate :hold]]
    (let [{:keys [disposition reason]} (phase/gate ph {:op op} d)
          label (str "gate " ph " " op " " d)
          op-s (subs (str op) 1)]
      (check! (str label " disposition")
              (name disposition)
              ((fx (g) "phase-disposition") (js/BigInt ph) op-s (name d)))
      (check! (str label " reason")
              (if reason (name reason) "")
              ((fx (g) "phase-reason") (js/BigInt ph) op-s (name d)))
      ;; The gate's contract, checked on the guest's own answer rather than
      ;; asserted: it never returns less caution than it was given.
      (check! (str label " relaxed?")
              false
              ((fx (g) "relaxed?") (js/BigInt ph) op-s (name d)))))

  ;; ── the operation and disposition vocabularies ────────────────────────────
  (doseq [op (concat (map #(subs (str %) 1) (into vault/read-ops vault/write-ops))
                     ["vault/drop-everything" "item/Reveal" ""])]
    (check! (str "operation-known? " (pr-str op))
            (contains? (into vault/read-ops vault/write-ops) (keyword op))
            ((fx (g) "operation-known?") op)))
  (doseq [d ["commit" "escalate" "hold" "yolo" ""]]
    (check! (str "disposition-known? " (pr-str d))
            (contains? #{"commit" "escalate" "hold"} d)
            ((fx (g) "disposition-known?") d)))
  ;; ── the x402 challenge against pay.x402 ──────────────────────────────────
  ;;
  ;; The guest cannot require a `.cljc` codec, so it writes the JSON itself.
  ;; That is a second implementation of a shared shape, which is exactly the
  ;; thing that drifts — so it is compared here, key by key, against the codec
  ;; the facilitator and every other seller use.
  (let [pay-to "0xA00366234D29d4F882088048c0B2fa0dB7302D4E"
        resource "https://app.itonami.cloud/kagi/x402/phase/decide"
        description "one kagi phase-gate decision; a phase may only add caution"
        oracle (x402/challenge (x402/payment-requirements
                                {:pay-to pay-to :usd "0.001" :resource resource
                                 :description description})
                               "X-PAYMENT header is required")
        reqs (first (:accepts oracle))
        terms ((fx (g) "x402-terms") (:network reqs) (:maxAmountRequired reqs) resource description)
        asset ((fx (g) "x402-asset") pay-to (:asset reqs)
               (get-in reqs [:extra :name]) (get-in reqs [:extra :version]))
        guest-json ((fx (g) "x402-challenge") terms asset (:error oracle))
        guest-body (js->clj (js/JSON.parse guest-json) :keywordize-keys true)]
    (check! "x402 challenge body" oracle guest-body))

  (doseq [ph [-1 0 1 2 3 4 9]]
    (check! (str "phase-known? " ph)
            (contains? phase/phases ph)
            ((fx (g) "phase-known?") (js/BigInt ph)))))

(-> (js/import host-path)
    (.then (fn [host]
             (let [bytes (fs/readFileSync (path/join root "guest" "decisions.wasm"))]
               (js/Promise.all
                (clj->js (repeatedly 64 #((.-instantiateKotoba host)
                                         bytes #js {:allowCapabilities #js []})))))))
    (.then (fn [hosted]
             (let [instances (mapv #(.-exports (.-instance %)) (array-seq hosted))]
               (compare-all! (runner instances))
               (println (str "parity: " @checks " comparisons across "
                             (count instances) " pre-fuelled instances"))
               (if (seq @failures)
                 (do (doseq [f (take 20 @failures)] (println "  FAIL" f))
                     (println (str "parity: " (count @failures) " disagreements"))
                     (js/process.exit 1))
                 (println "parity: guest and oracle agree on every input")))))
    (.catch (fn [e] (println "parity: threw —" (str e)) (js/process.exit 2))))
