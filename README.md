# kagi — the itonami

**鍵 as an 営み**: the public, key-less half of a sovereign secrets vault,
mounted at [`app.itonami.cloud/kagi`](https://app.itonami.cloud/kagi).

The engines live elsewhere and are not duplicated here:
[`kotoba-lang/kagi`](https://github.com/kotoba-lang/kagi) is the vault actor
(hybrid post-quantum crypto, AccessGovernor, tamper-evident ledger, the staged
rollout gate) and [`kotoba-lang/kagitaba`](https://github.com/kotoba-lang/kagitaba)
is the 1Password-shaped item model. This repository is a mount: it consumes
`kagi.phase`, `kagi.ledger` and `kagitaba.field` — the same pure `.cljc` the
vault itself decides with — and adds an HTTP envelope and a payment gate.

> **Boundary with `kotoba-lang/kagi`.** Same name, different face. That one is
> the vault; this one is the business it is sold as. Nothing is decided here:
> if an answer this surface gives ever differs from the vault's, this surface
> is wrong.

## It never receives a secret

A vault's whole claim is that plaintext and keys do not leave the owner's
machine. This surface holds no unlock material, no VMK, no compartment key and
no DEK, and it refuses to be handed one:

```
POST /item/classify   {:item {... :field/value "hunter2"}}
  -> 400 {:error :plaintext-value-received :keys [:field/value]}
```

Refused rather than stripped, because a caller who has just sent a password to
a public URL needs to know they did — a 200 with the value quietly dropped
leaves them not knowing. A username is refused for the same reason even though
it is not a secret: a surface that accepts some values and not others has to be
right about which one, every time, and *no values, ever* is a rule that cannot
be got subtly wrong.

## What it serves

| | | |
|---|---|---|
| `GET /health` | liveness | free |
| `GET /blueprint.edn` | the Open Business Blueprint | free |
| `POST /item/classify` | which fields must be sealed before storage | **free** |
| `POST /ledger/verify` | hash-chain check of an audit ledger | **free** |
| `POST /x402/phase/decide` | one staged-rollout gate decision | USD 0.001 |

**Classification and verification are free on purpose.** "Seal this before you
store it" is the rule every consumer of the vault must honour; put a price on
it and someone stores a credential in the clear rather than pay to be told not
to. The same argument applies to checking your own audit trail: a ledger nobody
can afford to verify is a ledger nobody verifies.

## Why the caller's own sensitivity flag is not trusted

`kagitaba.item/sensitive-fields` filters on `:field/sensitive?`, a flag the
item *constructor* sets. Read straight off a hand-built shape it answers
"nothing to seal" for an item made entirely of passwords, because the flag was
never set — an absent flag and a considered "no" are the same bytes. So
`/item/classify` derives sensitivity from `:field/type` through
`kagitaba.field/classification`, which fails closed on a type it has no name
for, and reports a caller's contradicting flag as a `:disagreements` entry
rather than obeying or silently overwriting it.

## What it refuses to answer

A signed ledger. kagi's `:ledger/sig` is **hybrid** — Ed25519 *and* ML-DSA-65,
sound only while both hold — and verifying it needs the actor's public bundle
from the key registry. Checking only the Ed25519 half would look exactly like
checking both, so a chain carrying signatures comes back as

```
422 {:error :signed-ledger-needs-the-key-registry :signed-entries [1]}
```

naming the entries responsible. This is not a gap to be closed by loosening the
check; it is where custody is.

## The phase gate is what is sold

A vault is adopted in stages, and the gate's one rule is that a phase may only
**add** caution: `:commit` can become `:escalate` or `:hold`, and `:hold` never
becomes `:commit`. Read off `kagi.phase/phases` rather than off the labels:
phase 0 permits no writes at all, and phases 1–3 permit every write, differing
only in which ones may commit unattended. So `:phase-disabled` is phase 0's
answer, and `:phase-approval` is a later phase's answer for a write outside its
automatic set — two reasons kept apart because one calls for a phase change and
the other for a person.

`:relaxed?` in every response is computed from the answer, not asserted: it is
the gate's contract evaluated on the decision it describes.

## Build, test, ship

```bash
npm install
npm run build     # shadow-cljs release worker -> dist/worker.js
npm run ship      # uploads into the ai-gftd-repository-dispatch namespace
npx nbb --classpath "src:test:../../kotoba-lang/kagi/src:../../kotoba-lang/kagitaba/src" \
    test/run_tests.cljs
```

The tests are `.cljc` and run on both hosts; nbb is the fast path and needs no
dependency resolution (26 tests, 118 assertions). `dds.css` and `blueprint.edn`
are inlined at compile time by `kagi.itonami.inline` — a missing one is a build
failure, not an endpoint that quietly serves nothing.

## ADR

`90-docs/adr/2608312200-kagi-itonami-mount-never-receives-a-secret.edn` in the
superproject.
