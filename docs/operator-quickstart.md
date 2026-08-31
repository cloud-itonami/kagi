# Operator quickstart

Every command below was run end to end on 2026-08-31, in the order given, from
a clean checkout. Where a step has a precondition that is easy to get wrong,
the failure it produces is written down next to it — a quickstart whose failure
modes are undocumented sends you to read the source at the worst moment.

## 0. Where this has to sit

The build and the parity check both reach **out of the repository** with
relative paths, so the checkout has to keep its place in the superproject:

```
orgs/cloud-itonami/kagi          <- here
orgs/kotoba-lang/amu             <- compiler + browser host    (build)
orgs/kotoba-lang/jp-go-digital-design-system                   (build)
orgs/kotoba-lang/kagi            <- vault engine               (parity oracle)
orgs/kotoba-lang/kagitaba        <- item model                 (parity oracle)
orgs/kotoba-lang/pay             <- x402 codec                 (parity oracle)
```

All five are west projects. Fetch any that are missing before you start:

```bash
west update --fetch smart amu jp-go-digital-design-system kagi kagitaba pay
```

A missing sibling does not produce a tidy message. The build reports
`ENOENT ... jp_go_dds/dds.css` from inside `copy-pinned!`, which reads as a
broken build script rather than as an absent checkout.

A copy of this repository somewhere else — `/tmp`, a scratch clone — cannot
build or run parity, because `../../kotoba-lang/...` resolves to nothing there.

## 1. Install

```bash
npm ci
```

`npm ci` (not `npm install`) is the reproducible path: it installs exactly the
lockfile and fails rather than quietly rewriting it. 38 packages, a few
seconds. `nbb` and `wrangler` are the only direct dependencies; nothing here
needs a JVM.

## 2. Build the guest

```bash
node ../../../scripts/resource-guard.mjs run build -- npm run build
```

The guard is the workspace-wide rule that only one heavy build runs at a time
(CLAUDE.md, resource governor). It exits **2** without starting yours when
another build holds the lock — that is not your build failing, it is your build
not having begun. Wait and repeat. Bare `npm run build` works too and is what
the guard ultimately runs.

Expect:

```
{:ok true, :target :wasm32-browser-kotoba-v1, ...}
build-guest: vendored browser-host.mjs (146660 bytes, sha256 fe84082ab7024760…) from kotoba-lang/amu (west pin)
build-guest: vendored dds.css (72114 bytes, sha256 3e4788bf64f57e9b…) from kotoba-lang/jp-go-digital-design-system (west pin)
build-guest: 7332 bytes, sha256 efebf06a58e30300…
build-guest: wrote guest/decisions.wasm.mjs
build-guest: wrote worker/vendor/assets.mjs (blueprint 4403 chars, dds.css 72098 chars)
```

**The build is reproducible, and that is a check you can run.** `guest/decisions.wasm`
is committed, so a build that changes it shows up as a dirty tree:

```bash
git status --porcelain guest/decisions.wasm     # silent = the commit matches the source
```

If that prints anything, the committed guest and `guest/decisions.kotoba`
disagree, and the answers being served are not the ones in the source you are
reading. Do not go past this.

## 3. Check the guest against the vault

```bash
npm run parity
```

```
parity: 322 comparisons across 64 pre-fuelled instances
parity: guest and oracle agree on every input
```

This is the test suite. It runs every input both implementations can answer
through **both** of them — the Kotoba guest and the `.cljc` engine libraries
the vault itself decides with — and fails on the first disagreement. It reads
the committed `guest/decisions.wasm`, so it does not need step 2 first.

It exits 0 on agreement and **1** on disagreement, naming the input and both
answers. Measured 2026-08-31, by removing `:totp` from `sensitive-types` in a
throwaway copy of the oracle and pointing the classpath at it:

```
FAIL classification "totp": oracle "internal" but guest "restricted"
parity: 1 disagreements
```

Worth knowing that it can do that. A parity check that has only ever printed
"agree on every input" is indistinguishable from one that compares nothing.

## 4. Run it locally

```bash
npx wrangler dev --port 8799 --local
```

`--local` runs in workerd on your machine and needs no Cloudflare credentials.
In another shell:

```bash
curl -s localhost:8799/health
```

```json
{"ok":true,"actor":"kagi","mount":"/kagi",
 "guest":{"language":"kotoba","target":"wasm32-browser-kotoba-v1",
          "bytes":7332,"sha256":"efebf06a58e30300..."},
 "runtime":{"jvm":false,"clojurescript":false}}
```

**The `sha256` here is the one step 2 printed.** That is the cheapest way to
tell whether the thing answering you was built from the source in front of you.
It is provenance, not admission — the Worker reports the digest, it cannot
recompute it, because wrangler hands it a `WebAssembly.Module` whose bytes are
already gone.

## 5. Confirm it refuses secrets

The claim this mount exists to make is that it never receives one. Check it
rather than trust it — and note the shape, because it is easy to get wrong in
a way that looks like a pass.

```bash
curl -s -X POST localhost:8799/item/classify -H 'content-type: application/json' -d '{
  "item": {"item/category":"login",
           "item/sections":[{"section/fields":[
             {"field/title":"password","field/type":"concealed","field/value":"hunter2"}]}]}}'
```

```json
{"error":"plaintext-value-received","keys":["field/value"],
 "detail":"this surface takes shapes, never values. ..."}
```

HTTP **400**. Refused, not stripped. `item/username`, `item/notes` and
`item/password-history` at the top level of the item are refused the same way.

> **A wrong shape passes quietly.** Fields are found at
> `item -> "item/sections"[] -> "section/fields"[]`, and *only* there. Send
> `{"item":{"fields":[...]}}` and you get **HTTP 200** with `"fields":[]` — no
> field was seen, so no value was seen either. That is a correct answer to the
> question asked, and it looks exactly like the refusal not working. Measured
> 2026-08-31: this cost a reviewer a false defect report. If you are testing
> the refusal, first confirm the same body *without* `field/value` returns a
> non-empty `fields` array.

Now the same shape with the value removed:

```bash
curl -s -X POST localhost:8799/item/classify -H 'content-type: application/json' -d '{
  "item": {"item/category":"login",
           "item/sections":[{"section/fields":[
             {"field/title":"password","field/type":"concealed"},
             {"field/title":"email","field/type":"email"}]}]}}'
```

```json
{"category":"login","category-known?":true,
 "fields":[{"title":"password","type":"concealed","classification":"restricted","must-seal?":true,"type-known?":true},
           {"title":"email","type":"email","classification":"internal","must-seal?":false,"type-known?":true}],
 "restricted-count":1,"must-seal?":true}
```

HTTP 200, and `fields` has two entries — so the walk really did reach them.

## 6. The paid endpoint

```bash
curl -s -X POST localhost:8799/x402/phase/decide -H 'content-type: application/json' \
     -d '{"phase":2,"op":"item-update"}'
```

HTTP **402** with an x402 challenge naming the network, the amount in USDC
micros (`1000` = USD 0.001), the asset and the facilitator. That is the
expected answer without an `X-PAYMENT` header, not a failure. The challenge
body is written by the guest and checked against `pay.x402` key by key in step
3, so the two cannot drift apart unnoticed.

`GET /blueprint.edn` and `GET /health` are free and unmetered.

## 7. Publish

```bash
npm run ship
```

This uploads into the `ai-gftd-repository-dispatch` namespace. The Worker has
no URL of its own; `itonami-fleet-dispatch` routes `app.itonami.cloud/kagi/*`
to it by script name and strips the first segment. The script name must stay
`kagi` — the repository name, the dispatch key and `blueprint.edn`'s
`:itonami.blueprint/mount` agree by being the same string, not by a table.

This needs Cloudflare credentials and changes a shared live surface. Steps 2, 3
and 5 should all be green first — in particular step 2's dirty-tree check,
since publishing a guest that does not match its source publishes answers
nobody has read.
