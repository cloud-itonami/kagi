(ns kagi.itonami.view
  "The public page. jp-go-dds (デジタル庁デザインシステム) — the workspace's base
  design system — with app CSS written only against the `--hig-*` token
  contract that `jp-go-dds.tokens/bridge-css` redefines on top of DADS
  primitives.

  Measured 2026-08-31 rather than assumed: the bridge carries 71 tokens —
  `--hig-text-*` 22, `--hig-color-*` 18, `--hig-spacing-*` 11,
  `--hig-palette-*` 9, `--hig-radius-*` 7, `--hig-font-*` 3, `--hig-hairline`.
  Spacing, type scale and radius are all in the contract, so this file writes
  them rather than re-deriving `em` values. (An older sibling's docstring says
  27 and 'no spacing' — that was true before the upstream commit that bridged
  the rest of the contract. Do not copy the number; count it.)

  The gap that remains is `--hig-palette-*`: six hues have no DADS equivalent
  and resolve to nothing under a DADS page, so nothing here is coloured by
  them."
  (:require [jp-go-dds.core :as dds]))

(def app-css
  "Small and unlayered, which is the contract: library CSS ships inside
   `@layer`, so app CSS always wins without compound selectors."
  "
.k-lede { max-width: 46em; }
.k-endpoint { font-family: var(--hig-font-mono, ui-monospace, monospace);
              font-size: var(--hig-text-footnote-font-size, 0.9em); }
.k-price { color: var(--hig-color-label-secondary, inherit); }
.k-free { color: var(--hig-color-label-secondary, inherit); }
.k-invariant { border-radius: var(--hig-radius-md, 8px);
               padding: var(--hig-spacing-4, 1rem);
               background: var(--hig-color-fill-quaternary, transparent); }
")

(defn- endpoint-rows [{:keys [mount]}]
  [["GET" (str mount "/health") "liveness" "free"]
   ["GET" (str mount "/blueprint.edn") "this actor's Open Business Blueprint" "free"]
   ["POST" (str mount "/item/classify") "which fields must be sealed" "free"]
   ["POST" (str mount "/ledger/verify") "hash-chain check of an audit ledger" "free"]
   ["POST" (str mount "/x402/phase/decide") "one staged-rollout gate decision" "USD 0.001"]])

(defn body
  [{:keys [price] :as opts}]
  [(dds/container
    (dds/section
     {}
     (dds/heading 1 "kagi — 鍵")
     [:p.k-lede
      "主権的な secrets vault の制御面を、営みとして公開したもの。"
      "エンジン本体は "
      [:a {:href "https://github.com/kotoba-lang/kagi"} "kotoba-lang/kagi"]
      "（vault actor: hybrid PQC 暗号・AccessGovernor・改竄検知台帳）と "
      [:a {:href "https://github.com/kotoba-lang/kagitaba"} "kotoba-lang/kagitaba"]
      "（1Password 互換の item モデル）。ここが公開しているのは、そのうち"
      [:strong "鍵を必要とせず、秘密を受け取らない部分"]
      "だけ。"]

     [:p.k-invariant.k-lede
      [:strong "この面は秘密を受け取らない。"]
      "passphrase も、VMK も、DEK も、field の平文も渡らない。"
      [:code "/item/classify"] " は "
      [:code ":field/value"] " を含む payload を"
      [:strong "黙って捨てずに拒否する"]
      " — 公開 URL に値を送ってしまった呼び出し側は、"
      "そのことを知る必要があるから（200 で静かに落とすと知らないままになる）。"
      "ユーザ名も同じ理由で拒否する。値を一部だけ受け入れる面は"
      "「どれが秘密か」を毎回正しく判定し続けなければならないが、"
      "「値は一切受け取らない」なら間違えようがない。"]

     (dds/heading 2 "何に課金し、何に課金しないか")
     [:p.k-lede
      "課金するのは" [:strong "判断"] "の側 —— 段階導入ゲート（phase gate）。"
      "phase は caution を" [:strong "足すことしかできない"]
      "（"[:code ":commit"]"→"[:code ":escalate"]"/"[:code ":hold"]" はあっても、"
      [:code ":hold"]"→"[:code ":commit"]" は無い）。"
      "その一方向性がこの gate の安全性そのものなので、"
      "拒否の理由は " [:code ":phase-disabled"] "（その phase では無効）と "
      [:code ":phase-approval"] "（有効だが無人では通さない）に分けて返す —— "
      "前者は phase の変更、後者は人間の承認で、要る行動が違う。"]
     [:p.k-lede
      [:strong "分類と台帳検証は無料。"]
      "「この項目は封緘してから保存せよ」は vault を使う全員が守るべき規則で、"
      "そこに値段を付けると、払いたくなかった誰かが credential を平文で置く。"
      "自分の監査台帳が壊れていないかの確認も同じ —— "
      "確認に金を取る台帳は、誰も確認しない台帳になる。"]

     (dds/heading 2 "エンドポイント")
     (dds/table
      {:caption "app.itonami.cloud/kagi"
       :headers ["method" "path" "何をするか" "価格"]
       :rows (mapv (fn [[m p d c]]
                     [m [:span.k-endpoint p] d
                      [:span {:class (if (= "free" c) "k-free" "k-price")} c]])
                   (endpoint-rows opts))})

     (dds/heading 2 "答えないこと")
     [:p.k-lede
      "署名済みの台帳は " [:strong "検証せずに拒否する"] "（"
      [:code ":signed-ledger-needs-the-key-registry"] "）。"
      "kagi の署名は Ed25519 と ML-DSA-65 の " [:strong "hybrid"]
      " で、両方が生きている限り安全という設計 —— "
      "Ed25519 側だけ確かめた結果は、全部確かめた結果と"
      [:strong "見分けがつかない"]
      "。だから半分だけ検証して返すより、鍵レジストリのある場所へ送り返す。"]

     (dds/heading 2 "支払い")
     [:p.k-lede
      "x402（HTTP 402 Payment Required）。有料エンドポイントは "
      [:code "X-PAYMENT"] " が無ければ 402 と challenge を返す。"
      "決済の検証は facilitator "
      [:a {:href "https://x402.nexus"} "x402.nexus"]
      " に委譲していて、この Worker は鍵を持たない（"
      [:code "X402_PAY_TO"] " は公開の treasury アドレス）。"
      "USDC / Base、1 判定 " (or price "USD 0.001") "。"]
     [:p.k-lede
      "facilitator に到達できないときは " [:strong "402 を返す"]
      "（fail closed）。ただし理由は " [:code ":facilitator-unreachable"]
      " であって " [:code ":payment-invalid"]
      " ではない — 「払い直せ」と「待て」は違う指示だから。"]))])
