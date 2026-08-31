/**
 * The kagi itonami, mounted at `app.itonami.cloud/kagi`.
 *
 * No JVM, no ClojureScript. Owner directive 2026-08-31: the previous Worker
 * was a shadow-cljs bundle — a JVM to build it, a ClojureScript runtime inside
 * it. This one is a hand-written host around a Kotoba guest compiled by
 * `amu --target wasm32-browser`, which `bin/amu` runs on nbb.
 *
 * ## The line between this file and the guest
 *
 * The guest decides; this file causes. Every classification, every refusal,
 * every phase-gate answer and every response body is produced by
 * `guest/decisions.kotoba`. What lives here is effects and growing
 * collections: reading a socket, parsing a body, walking a list of fields,
 * asking a payment facilitator, writing bytes back. That is the same boundary
 * amu's own `runtime/http-service.mjs` draws around
 * `runtime/http/route-decide.kotoba`.
 *
 * If you find yourself writing a `?:` here that answers a question about
 * secrets, vaults or phases, it belongs in the guest.
 *
 * ## Why a fresh instance per request
 *
 * `instantiateKotoba` opens a fuel budget that is spent and never replenished.
 * Measured 2026-08-31: one instance answers 256 `classification` calls and
 * then traps. One request is one bounded, metered computation; a guest that
 * ran away spoils that request and nothing else.
 *
 * ## What the host grants
 *
 * Nothing. `allowCapabilities: []`, and the guest imports no capability, so
 * there is no DOM, no network, no clock, no storage and no randomness behind
 * this boundary. The module is admitted only if its sha256 matches the digest
 * emitted beside it at build time.
 */
import { instantiateKotoba, normalizeKotobaTrap } from "./vendor/browser-host.mjs";
import { blueprintEdn, ddsCss } from "./vendor/assets.mjs";
import { digest, bytes } from "../guest/decisions.wasm.mjs";
// wrangler compiles this at bundle time and hands over a `WebAssembly.Module`.
// It has to be a module rather than bytes: Cloudflare refuses runtime
// compilation, and measured 2026-08-31 a Worker built around
// `WebAssembly.compile` answered `invalid-module / Wasm compilation failed` on
// every request while the same bytes ran in Node.
import decisions from "../guest/decisions.wasm";

const MOUNT = "/kagi";
const MAX_BODY_BYTES = 256 * 1024;
const MAX_FIELDS = 512;

/**
 * A guest with a full fuel budget.
 *
 * No `expectedSha256`: the host refuses that option for a pre-compiled module,
 * because it cannot hash one, and refusing beats accepting an option that
 * silently checks nothing. The digest below is computed at build time, where
 * the bytes still exist, and is reported as provenance rather than verified
 * here as admission. What IS still enforced at instantiation: the import
 * allowlist, the typed ABI, and `allowCapabilities: []` — this guest is
 * granted nothing.
 */
async function fresh() {
  const hosted = await instantiateKotoba(decisions, { allowCapabilities: [] });
  return hosted.instance.exports;
}

const json = (body, status) =>
  new Response(typeof body === "string" ? body : JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" }
  });

const edn = text =>
  new Response(text, {
    status: 200,
    headers: {
      "content-type": "application/edn; charset=utf-8",
      "cache-control": "no-store"
    }
  });

/**
 * Read and parse a JSON body.
 *
 * Bounded before parsing rather than after: `Content-Length` is the caller's
 * claim, and a parser run over an unbounded stream is the resource being spent
 * by whoever chose the size.
 *
 * JSON, not EDN. The ClojureScript mount read EDN with `cljs.reader`; without
 * a ClojureScript runtime there is no reader here, and hand-writing one would
 * be a parser nobody has reviewed sitting in front of a vault surface. Said
 * out loud because it is a change to the published interface, not an
 * implementation detail.
 */
async function readBody(request) {
  let text;
  try {
    text = await request.text();
  } catch (error) {
    return { error: "body-unreadable", detail: String(error) };
  }
  if (text.length > MAX_BODY_BYTES)
    return { error: "body-too-large", limit: MAX_BODY_BYTES };
  if (text.trim() === "") return { error: "empty-body" };
  try {
    return { ok: JSON.parse(text) };
  } catch (error) {
    return { error: "unreadable-json", detail: String(error) };
  }
}

/** Every value-bearing key the guest refuses, found in the payload. */
function refusedKey(guest, item) {
  for (const key of ["item/notes", "item/username", "item/password-history"]) {
    if (guest["value-key-refused?"](key) && Object.hasOwn(item ?? {}, key)) return key;
  }
  const sections = Array.isArray(item?.["item/sections"]) ? item["item/sections"] : [];
  for (const section of sections) {
    const fields = Array.isArray(section?.["section/fields"]) ? section["section/fields"] : [];
    for (const field of fields) {
      if (field && typeof field === "object" && Object.hasOwn(field, "field/value")
          && guest["value-key-refused?"]("field/value"))
        return "field/value";
    }
  }
  return null;
}

function fieldsOf(item) {
  const out = [];
  const sections = Array.isArray(item?.["item/sections"]) ? item["item/sections"] : [];
  for (const section of sections) {
    const fields = Array.isArray(section?.["section/fields"]) ? section["section/fields"] : [];
    for (const field of fields) if (field && typeof field === "object") out.push(field);
  }
  return out;
}

async function handleClassify(request) {
  const body = await readBody(request);
  if (body.error) return json(body, 400);
  const guest = await fresh();
  const item = body.ok?.item;

  // The value check runs before the structural one. A request that carries a
  // password and is also malformed should hear about the password first.
  const refused = refusedKey(guest, item);
  if (refused) return json(guest["refusal-answer"](refused), 400);

  if (item === null || typeof item !== "object" || Array.isArray(item))
    return json({ error: "invalid-request", problems: [{ problem: "item-not-a-map" }] }, 400);
  const fields = fieldsOf(item);
  if (fields.length > MAX_FIELDS)
    return json({ error: "invalid-request",
                  problems: [{ problem: "too-many-fields", limit: MAX_FIELDS }] }, 400);

  // The walk is the host's; what each field MEANS is the guest's.
  const answers = [];
  let restricted = 0;
  for (const field of fields) {
    const type = String(field["field/type"] ?? "");
    const answer = guest["field-answer"](String(field["field/title"] ?? ""), type);
    answers.push(answer);
    if (guest.classification(type) === "restricted") restricted += 1;
  }
  const category = String(item["item/category"] ?? "");
  return json(
    guest["classify-answer"](category, categoryKnown(category),
                             BigInt(restricted), `[${answers.join(",")}]`),
    200);
}

/**
 * Whether kagitaba names this category.
 *
 * The guest does not carry the category table: it is 114 entries that grow
 * with 1Password, which is a collection rather than a decision, and a Kotoba
 * guest has no map to hold it. So the host holds the list and the guest is
 * told the answer — the inverse of every other question here, and the reason
 * it is spelled out rather than buried.
 */
const CATEGORIES = new Set([
  "login", "credit-card", "secure-note", "identity", "password", "document",
  "software-license", "bank-account", "database", "driver-license", "outdoor-license",
  "membership", "passport", "rewards", "server", "email-account", "api-credential",
  "medical-record", "ssh-key", "wireless-router", "social-security-number", "custom"
]);
const categoryKnown = category => CATEGORIES.has(category);

async function handleDecide(request, env, url) {
  const guest = await fresh();
  const payment = request.headers.get("x-payment");
  const resource = `${url.origin}${MOUNT}/x402/phase/decide`;
  const terms = guest["x402-terms"](
    env.X402_NETWORK ?? "base",
    env.X402_UNITS ?? "1000",
    resource,
    "one kagi phase-gate decision; a phase may only add caution");
  const assetPart = guest["x402-asset"](
    env.X402_PAY_TO ?? "",
    env.X402_ASSET ?? "",
    env.X402_ASSET_NAME ?? "USD Coin",
    env.X402_ASSET_VERSION ?? "2");

  if (!payment || payment.trim() === "")
    return json(guest["x402-challenge"](terms, assetPart, "X-PAYMENT header is required"), 402);

  const verdict = await verifyPayment(env, payment, terms, assetPart);
  if (!verdict.paid) {
    // Fail closed, and say WHICH closed door it is: facilitator-unreachable
    // means wait, payment-invalid means pay again. One undifferentiated 402
    // would send a caller who already paid to pay a second time.
    const body = JSON.parse(guest["x402-challenge"](terms, assetPart, verdict.reason));
    body.reason = verdict.reason;
    body.detail = verdict.detail;
    return json(body, 402);
  }

  const decision = await readBody(request);
  if (decision.error) return json(decision, 400);
  const { phase, op, disposition } = decision.ok ?? {};
  const problems = [];
  if (!Number.isInteger(phase)) problems.push({ problem: "phase-not-an-integer" });
  else if (!guest["phase-known?"](BigInt(phase))) problems.push({ problem: "phase-unknown" });
  if (typeof op !== "string") problems.push({ problem: "op-not-a-string" });
  else if (!guest["operation-known?"](op)) problems.push({ problem: "op-unknown" });
  if (typeof disposition !== "string" || !guest["disposition-known?"](disposition))
    problems.push({ problem: "disposition-unknown" });
  if (problems.length) return json({ error: "invalid-request", problems }, 400);

  const answer = JSON.parse(guest["decide-answer"](BigInt(phase), op, disposition));
  answer.payer = verdict.payer ?? null;
  return json(answer, 200);
}

/**
 * Ask the facilitator. The only outbound call this Worker makes, and the only
 * thing here that can fail for a reason the guest cannot see.
 */
async function verifyPayment(env, payment, terms, assetPart) {
  const facilitator = env.X402_FACILITATOR ?? "https://x402.nexus";
  const requirements = JSON.parse(`{${terms},${assetPart}}`);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10_000);
  try {
    const response = await fetch(`${facilitator}/verify`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ payment, requirements }),
      signal: controller.signal
    });
    if (!response.ok)
      return { paid: false, reason: "facilitator-unreachable",
               detail: `facilitator answered ${response.status}` };
    const body = await response.json();
    return body?.isValid
      ? { paid: true, payer: body.payer }
      : { paid: false, reason: "payment-invalid",
          detail: body?.invalidReason ?? "the facilitator rejected the payment" };
  } catch (error) {
    return { paid: false, reason: "facilitator-unreachable", detail: String(error) };
  } finally {
    clearTimeout(timer);
  }
}

const page = () => `<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>kagi — 鍵 | itonami</title>
<meta name="description" content="主権的 secrets vault の制御面の営み。封緘すべき項目の判定と段階導入ゲート。秘密は受け取らない。">
<style>${ddsCss}</style>
<style>
.k-wrap { max-width: 46em; margin: 0 auto; padding: 2rem 1rem; }
.k-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.9em; }
.k-note { border: 1px solid currentColor; border-radius: 8px; padding: 1rem; }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid rgba(0,0,0,.15); }
</style></head><body><main class="k-wrap">
<h1>kagi — 鍵</h1>
<p>主権的な secrets vault の制御面を、営みとして公開したもの。エンジン本体は
<a href="https://github.com/kotoba-lang/kagi">kotoba-lang/kagi</a>（vault actor）と
<a href="https://github.com/kotoba-lang/kagitaba">kotoba-lang/kagitaba</a>（item モデル）。
ここが公開しているのは、そのうち<strong>鍵を必要とせず、秘密を受け取らない部分</strong>だけ。</p>
<p class="k-note"><strong>この面は秘密を受け取らない。</strong>
<code>/item/classify</code> は <code>field/value</code> を含む payload を<strong>黙って捨てずに拒否する</strong> —
公開 URL に値を送ってしまった呼び出し側は、そのことを知る必要があるから。
ユーザ名も同じ理由で拒否する。値を一部だけ受け入れる面は「どれが秘密か」を毎回正しく判定し続けねばならないが、
「値は一切受け取らない」なら間違えようがない。</p>
<h2>判断は Kotoba にある</h2>
<p>分類・拒否・段階導入ゲートの答えは、すべて <code>guest/decisions.kotoba</code> を
<code>amu --target wasm32-browser</code> でコンパイルした ${bytes} バイトの WebAssembly が出している。
この JavaScript が持っているのは socket と body の解析と field の走査だけで、JVM も ClojureScript も通っていない。</p>
<h2>エンドポイント</h2>
<table><thead><tr><th>method</th><th>path</th><th>何をするか</th><th>価格</th></tr></thead><tbody>
<tr><td>GET</td><td class="k-mono">${MOUNT}/health</td><td>liveness</td><td>free</td></tr>
<tr><td>GET</td><td class="k-mono">${MOUNT}/blueprint.edn</td><td>Open Business Blueprint</td><td>free</td></tr>
<tr><td>POST</td><td class="k-mono">${MOUNT}/item/classify</td><td>封緘すべき field はどれか</td><td>free</td></tr>
<tr><td>POST</td><td class="k-mono">${MOUNT}/x402/phase/decide</td><td>段階導入ゲート 1 判定</td><td>USD 0.001</td></tr>
</tbody></table>
<h2>いま出せない答え</h2>
<p><code>/ledger/verify</code> は<strong>取り下げた</strong>。ハッシュ鎖の検証には SHA-256 と
kagi の正準化が要るが、capability kit に hash は無く（実測 2026-08-31、14 kit のいずれにも）、
正準化を JavaScript で書き直せば「vault と食い違う第二の実装」になる。
署名済み台帳の検証と同じ理由で、鍵レジストリのある場所に残す。</p>
<p>本文の受け口は EDN から <strong>JSON</strong> に変わった。ClojureScript の reader が無く、
vault の前に未レビューの parser を置きたくないため。</p>
</main></body></html>`;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const guest = await fresh();
    let outcome;
    try {
      outcome = guest["route-outcome"](request.method, url.pathname);
    } catch (error) {
      return json({ error: "guest-trap", detail: normalizeKotobaTrap(error)?.code ?? String(error) }, 500);
    }
    const status = Number(guest["status-for"](outcome));

    switch (outcome) {
      case ":index":
        return new Response(page(), {
          status: 200,
          headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" }
        });
      case ":health":
        return json({
          ok: true, actor: "kagi", mount: MOUNT,
          engine: { vault: "kotoba-lang/kagi", "item-model": "kotoba-lang/kagitaba" },
          custody: "none",
          guest: { language: "kotoba", target: "wasm32-browser-kotoba-v1",
                   bytes, sha256: digest },
          runtime: { jvm: false, clojurescript: false }
        }, 200);
      case ":blueprint":
        return edn(blueprintEdn);
      case ":classify":
        return handleClassify(request);
      case ":decide":
        return handleDecide(request, env, url);
      case ":method-not-allowed":
        return json({ error: "method not allowed", path: url.pathname, expected: "POST" }, status);
      default:
        return json({
          error: "not found", actor: "kagi", path: url.pathname,
          routes: ["/", "/health", "/blueprint.edn",
                   "POST /item/classify", "POST /x402/phase/decide"]
        }, status);
    }
  }
};
