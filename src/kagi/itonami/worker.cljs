(ns kagi.itonami.worker
  "The kagi itonami, mounted at `app.itonami.cloud/kagi`.

  Uploaded into `ai-gftd-repository-dispatch`, so this Worker has no URL of its
  own — `itonami-fleet-dispatch` strips the first path segment and this handler
  sees `/health` rather than `/kagi/health`. The script name, the repository
  name and `blueprint.edn`'s `:itonami.blueprint/mount` are the same string, by
  being the same string rather than through a table.

  What it deliberately does not do: hold a vault. There is no unlock, no VMK,
  no compartment key, no DEK and no plaintext field value anywhere in this
  process. A vault whose custody could be reached from the public internet is
  not a vault, so what is published here is the half that decides — a phase
  gate, a hash-chain check, and the taxonomy that says what must be sealed."
  (:require [cljs.reader :as reader]
            [goog.object :as gobj]
            [clojure.string :as str]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [kagi.itonami.classify :as classify]
            [kagi.itonami.decide :as decide]
            [kagi.itonami.gate :as gate]
            [kagi.itonami.ledger :as ledger]
            [kagi.itonami.view :as view])
  (:require-macros [kagi.itonami.inline :refer [inline-file inline-resource]]))

(def dds-css (inline-resource "jp_go_dds/dds.css"))
(def blueprint-edn (inline-file "blueprint.edn"))

(def mount "/kagi")
(def max-body-bytes (* 256 1024))

(defn- json-response [body status]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status
                     :headers #js {"content-type" "application/json"
                                   "cache-control" "no-store"}}))

(defn- edn-response [text status]
  (js/Response. text
                #js {:status status
                     :headers #js {"content-type" "application/edn; charset=utf-8"
                                   "cache-control" "no-store"}}))

(defn- html-response [html]
  (js/Response. html
                #js {:status 200
                     :headers #js {"content-type" "text/html; charset=utf-8"
                                   "cache-control" "no-store"}}))

(defn- read-body
  "-> Promise of {:ok body} | {:error …}.

  Bounded before parsing rather than after: `Content-Length` is the caller's
  claim, and a reader run over an unbounded stream is the resource being spent
  by whoever chose the size."
  [request]
  (-> (.text request)
      (.then (fn [text]
               (cond
                 (> (.-length text) max-body-bytes)
                 {:error :body-too-large :limit max-body-bytes}

                 (str/blank? text)
                 {:error :empty-body}

                 :else
                 (let [parsed (try (reader/read-string text)
                                   (catch :default e {::unreadable (str e)}))]
                   (if (::unreadable parsed)
                     {:error :unreadable-edn :detail (::unreadable parsed)}
                     {:ok parsed})))))
      (.catch (fn [e] {:error :body-unreadable :detail (str e)}))))

(defn- page-html []
  (page/->page
   {:title "kagi — 鍵 | itonami"
    :description (str "主権的 secrets vault の制御面の営み。封緘すべき項目の判定、"
                      "台帳のハッシュ鎖検証、段階導入ゲート。秘密は受け取らない。")
    :lang "ja"
    :css dds-css
    :app-css (str tokens/bridge-css "\n" view/app-css)}
   (view/body {:mount mount :price "USD 0.001"})))

;; ── the free half ───────────────────────────────────────────────────────────

(defn- with-body
  "Read, parse and hand the body to `f`, or answer the parse failure.

  One place, so a body that is too large or unreadable is reported the same way
  whichever resource was asked for."
  [request f]
  (.then (read-body request)
         (fn [{:keys [ok error] :as body}]
           (if error
             (json-response (assoc (dissoc body :ok) :error error) 400)
             (f ok)))))

(defn- handle-classify [request]
  (with-body request
    (fn [body]
      (let [result (classify/classify body)]
        (json-response result (if (:error result) 400 200))))))

(defn- handle-ledger-verify [request]
  (with-body request
    (fn [body]
      (let [result (ledger/verify body)]
        (cond
          ;; A signed chain is not a malformed request — the caller did
          ;; nothing wrong, this surface simply cannot answer it. 422 rather
          ;; than 400 so 'fix your request' and 'ask somewhere else' are
          ;; distinguishable without reading the body.
          (= :signed-ledger-needs-the-key-registry (:error result))
          (json-response result 422)

          (:error result) (json-response result 400)

          ;; A broken chain is a successful verification with a negative
          ;; answer, not a failed request: the caller asked whether it was
          ;; intact and now knows.
          :else (json-response result 200))))))

;; ── the paid half ───────────────────────────────────────────────────────────

(defn- decide-requirements [env url]
  (gate/requirements
   {:pay-to (gobj/get env "X402_PAY_TO")
    :usd (or (gobj/get env "X402_DECIDE_USD") "0.001")
    :network (or (gobj/get env "X402_NETWORK") "base")
    :resource (str (.-origin url) mount "/x402/phase/decide")
    :description "one kagi phase-gate decision; a phase may only add caution"}))

(defn- handle-decide [request env url]
  (let [payment (.get (.-headers request) "x-payment")
        reqs (decide-requirements env url)]
    (if (str/blank? (str payment))
      (js/Promise.resolve (json-response (gate/challenge-body reqs) 402))
      (-> (gate/verify-payment {:facilitator (or (gobj/get env "X402_FACILITATOR")
                                                 "https://x402.nexus")
                                :payment payment
                                :reqs reqs})
          (.then
           (fn [{:keys [paid? reason detail payer]}]
             (if-not paid?
               ;; Fail closed, and say WHICH closed door it is:
               ;; :facilitator-unreachable means wait, :payment-invalid means
               ;; pay again. One undifferentiated 402 would send a caller who
               ;; already paid to pay a second time for the same resource.
               (js/Promise.resolve
                (json-response (assoc (gate/challenge-body reqs (name reason))
                                      :reason reason
                                      :detail detail)
                               402))
               (with-body request
                 (fn [body]
                   (let [result (decide/decide body)]
                     (json-response (assoc result :payer payer)
                                    (if (= :invalid-request (:error result))
                                      400 200)))))))))))) 

;; ── routing ─────────────────────────────────────────────────────────────────

(def post-routes
  #{"/item/classify" "/ledger/verify" "/x402/phase/decide"})

(defn handle [request env]
  (let [url (js/URL. (.-url request))
        path (.-pathname url)
        method (.-method request)]
    (cond
      (and (= "GET" method) (#{"/" ""} path))
      (js/Promise.resolve (html-response (page-html)))

      (and (= "GET" method) (= "/health" path))
      (js/Promise.resolve
       (json-response {:ok true :actor "kagi" :mount mount
                       :engine {:vault "kotoba-lang/kagi"
                                :item-model "kotoba-lang/kagitaba"}
                       :custody :none} 200))

      (and (= "GET" method) (= "/blueprint.edn" path))
      (js/Promise.resolve (edn-response blueprint-edn 200))

      (and (= "POST" method) (= "/item/classify" path))
      (handle-classify request)

      (and (= "POST" method) (= "/ledger/verify" path))
      (handle-ledger-verify request)

      (and (= "POST" method) (= "/x402/phase/decide" path))
      (handle-decide request env url)

      ;; A GET on a POST-only resource is a method error, not a missing one:
      ;; answering 404 would send a caller looking for a typo in a path that is
      ;; correct.
      (post-routes path)
      (js/Promise.resolve
       (json-response {:error "method not allowed" :path path :expected "POST"} 405))

      :else
      (js/Promise.resolve
       (json-response {:error "not found" :actor "kagi" :path path
                       :routes ["/" "/health" "/blueprint.edn"
                                "POST /item/classify" "POST /ledger/verify"
                                "POST /x402/phase/decide"]}
                      404)))))

(def app
  #js {:fetch (fn [request env _ctx] (handle request env))})
