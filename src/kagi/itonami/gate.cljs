(ns kagi.itonami.gate
  "The x402 payment gate, in the thin mode: this seller keeps its own gate and
  delegates on-chain verification to the facilitator.

  `pay.x402` builds the challenge, so seller and facilitator agree by sharing a
  codec rather than by two readings of one specification. Nothing here holds a
  key — `X402_PAY_TO` is a public treasury address, and settlement is the
  facilitator's business.

  ## The gate fails closed, including when the facilitator is down

  A verifier that cannot reach `/verify` knows nothing about the payment, and
  'unknown' is not 'paid'. Answering 402 when the facilitator is unreachable
  costs a paying caller a retry; answering 200 costs the seller the resource,
  and does so precisely when something is already wrong. The distinction is
  reported (`:facilitator-unreachable` rather than `:payment-invalid`) so a
  caller can tell 'pay again' from 'wait'."
  (:require [pay.x402 :as x402]))

(def verify-timeout-ms
  "A facilitator call is on the critical path of every paid request. Ten
  seconds is long enough for an on-chain read and short enough that a hung
  facilitator does not hold the Worker's whole budget."
  10000)

(defn requirements
  [{:keys [pay-to usd network resource description]}]
  (x402/payment-requirements
   {:pay-to pay-to :usd usd :network network
    :resource resource :description description}))

(defn challenge-body
  ([reqs] (x402/challenge reqs))
  ([reqs error] (x402/challenge reqs error)))

(defn- with-timeout [promise ms]
  (js/Promise.race
   #js [promise
        (js/Promise. (fn [_ reject]
                       (js/setTimeout #(reject (js/Error. "facilitator timeout")) ms)))]))

(defn verify-payment
  "-> Promise of {:paid? true :payer …} or {:paid? false :reason … :detail …}.

  `payment` is the raw `X-PAYMENT` header value, passed through untouched: the
  facilitator decodes it, and a seller that decoded and re-encoded it would be
  verifying something subtly different from what it forwarded."
  [{:keys [facilitator payment reqs]}]
  (-> (with-timeout
        (js/fetch (str facilitator "/verify")
                  #js {:method "POST"
                       :headers #js {"content-type" "application/json"}
                       :body (js/JSON.stringify
                              (clj->js {:payment payment :requirements reqs}))})
        verify-timeout-ms)
      (.then (fn [response]
               (if-not (.-ok response)
                 (.then (.text response)
                        (fn [body]
                          {:paid? false :reason :facilitator-unreachable
                           :detail (str "facilitator answered " (.-status response)
                                        ": " (subs (str body) 0 200))}))
                 (.then (.json response)
                        (fn [body]
                          (let [{:keys [isValid invalidReason payer]}
                                (js->clj body :keywordize-keys true)]
                            (if isValid
                              {:paid? true :payer payer}
                              {:paid? false :reason :payment-invalid
                               :detail (or invalidReason "the facilitator rejected the payment")})))))))
      (.catch (fn [e]
                {:paid? false :reason :facilitator-unreachable :detail (str e)}))))
