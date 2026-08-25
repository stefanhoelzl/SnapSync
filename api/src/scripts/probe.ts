// The post-deploy BOOT PROBE (capability `backend-deployment`). Runs OUT of the Edge Script — a Deno
// program `api-deploy.yml` invokes after `POST /publish`, exactly as `nightly-cleanup.yml` invokes the
// sweep beside it.
//
// WHY IT EXISTS. `POST /code` + `POST /publish` succeed whether or not the deployed bundle can BOOT, so a
// green deploy step is not evidence that the script serves. That is how the previous runtime stayed
// fail-closed for two weeks with CI green throughout. The `migrate-runtime-to-bunny` record declined a
// probe CONDITIONALLY — config in source made that failure class impossible, so prevention replaced
// detection — and wrote the trigger down: "Anything that reintroduces platform-side required config
// reintroduces the silent-corpse failure with nothing watching." Deployment-resolved config plus the
// database credentials landing next reintroduce exactly that, so the condition has fired.
//
// WHAT GREEN PROVES: the script booted (module top-level ran, `readConfig` did not throw), THIS bundle is
// the one answering, and the whole device-facing chain — DNS, certificate, pull zone, script — is intact.
// WHAT IT DOES NOT PROVE: that any configured value is CORRECT. A value that is present but wrong boots
// and probes green. Probe coverage is exactly the set of faults that startup turns into a failure to boot.
//
// WHY THERE IS NO ROLLBACK. Bunny does support re-publishing a previous release — but MEASURED (run
// 32748912239), the script-scoped deploy key returns 401 on both `GET /compute/script/<id>/releases` and
// `GET /compute/script/<id>`. `DeploymentKey` reaches `/code` and `/publish` and nothing else; rollback
// needs the ACCOUNT key, which owns every user's photos and our DNS and which CI is forbidden to hold.
// The same 401 is why the probe cannot discover the script's own hostname either. Expiry trigger:
// re-measure if bunny issues compute-scoped subuser keys.
//
// The decision table below is pure and injected-fetch driven, so `probe.test.ts` covers every cell with
// NO `--allow-net` — the absence that guarantees no test reaches a real host.

import type { FetchLike } from "../storage.ts";

/** How the last attempt was classified. Each cause names a DIFFERENT fault with a different action. */
export type Cause =
  | "match" // this bundle is serving — PASS
  | "unreachable" // connection failed: the script may still be swapping in — retryable
  | "server-error" // 5xx: same
  | "not-found" // 404: an older bundle without the route is still live — retryable
  | "stale-sha" // 200 with a different, well-formed sha: propagating — retryable
  | "unstamped" // 200 with the placeholder sha: CI supplied none — TERMINAL, our bug
  | "store-unreachable" // this bundle, but it cannot reach its relational store — retryable
  | "foreign-keys-off" // this bundle, store reachable, constraints DISABLED — TERMINAL
  | "unparseable"; // 200 that is not our health response: something else is answering — TERMINAL

/** Causes time can fix. Everything else fails at once: retrying a terminal cause only hides it. */
const RETRYABLE: ReadonlySet<Cause> = new Set<Cause>([
  "unreachable",
  "server-error",
  "not-found",
  "stale-sha",
  // A store that is unreachable at this instant may simply be waking; enforcement being OFF is not, so it
  // is deliberately NOT here. Retrying a terminal cause until the deadline turns a specific,
  // one-line-to-fix misprovisioning into an unexplained timeout.
  "store-unreachable",
]);

/** The sha a build carries when CI supplied none — see the resolver's inventory. */
export const UNSTAMPED = "dev";

export type Verdict = {
  ok: boolean;
  cause: Cause;
  /** What the last attempt actually saw, so a red run reads as a diagnosis rather than a timeout. */
  detail: string;
  attempts: number;
};

/**
 * Classify one answer. Pure: status and body in, cause out.
 *
 * Robust to bunny behaviour nobody has measured — an HTML error page returned with 200, a stale cached
 * copy, or the previous deployment surviving all land in a red cell, and the two that should be fast are.
 */
export function classify(status: number, body: string, expectedSha: string): Cause {
  if (status === 404) return "not-found";
  if (status >= 500) return "server-error";
  if (status !== 200) return "unparseable";

  let sha: unknown;
  try {
    sha = (JSON.parse(body) as { sha?: unknown }).sha;
  } catch {
    return "unparseable";
  }
  if (typeof sha !== "string" || sha.length === 0) return "unparseable";
  if (sha === UNSTAMPED) return "unstamped";
  if (sha !== expectedSha) return "stale-sha";

  // This IS the bundle we deployed. Now the second question the probe exists to answer (capability
  // `backend-deployment`): can it reach its relational store, and are FOREIGN KEYS enforced there? A
  // deployment serving with constraints silently disabled is a green deploy over a broken invariant,
  // which is the exact failure shape this probe was added to catch for the bundle identity.
  let database: unknown;
  try {
    database = (JSON.parse(body) as { database?: unknown }).database;
  } catch {
    return "unparseable";
  }
  // An older bundle that predates this field answers without it. Treat that as the sha check already
  // did — it is not this deployment's health response.
  if (typeof database !== "string") return "unparseable";
  if (database === "unreachable") return "store-unreachable";
  if (database === "foreign-keys-off") return "foreign-keys-off";
  if (database !== "ok") return "unparseable";
  return "match";
}

export type ProbeOptions = {
  fetch: FetchLike;
  /** The device-facing origin, e.g. `https://example.com` — no trailing slash. */
  origin: string;
  expectedSha: string;
  /** Injected so tests pin them; `runProbe` never reads a clock or sleeps on its own. */
  now: () => number;
  sleep: (ms: number) => Promise<void>;
  log?: (line: string) => void;
  deadlineMs?: number;
  intervalMs?: number;
};

/** Poll until this bundle answers, a terminal cause appears, or the deadline passes. */
export async function runProbe(
  {
    fetch,
    origin,
    expectedSha,
    now,
    sleep,
    log = () => {},
    deadlineMs = 120_000,
    intervalMs = 5_000,
  }: ProbeOptions,
): Promise<Verdict> {
  const url = `${origin}/health`;
  const started = now();
  let attempts = 0;
  let cause: Cause = "unreachable";
  let detail = "no attempt made";

  while (true) {
    attempts++;
    try {
      const res = await fetch(url, { method: "GET" });
      const body = await res.text();
      cause = classify(res.status, body, expectedSha);
      detail = `HTTP ${res.status} ${body.slice(0, 120)}`;
    } catch (e) {
      cause = "unreachable";
      detail = String(e);
    }
    log(`probe attempt ${attempts}: ${cause} — ${detail}`);

    if (cause === "match") return { ok: true, cause, detail, attempts };
    if (!RETRYABLE.has(cause)) return { ok: false, cause, detail, attempts };
    // Check the deadline BEFORE sleeping, so the last attempt is not followed by a pointless wait.
    if (now() - started >= deadlineMs) return { ok: false, cause, detail, attempts };
    await sleep(intervalMs);
  }
}

/** `--name=value` / `--name value`. Both arguments are REQUIRED: there is no safe default for either. */
export function parseArgs(argv: readonly string[]): { origin: string; sha: string } {
  const values = new Map<string, string>();
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const eq = arg.indexOf("=");
    if (eq >= 0) values.set(arg.slice(2, eq), arg.slice(eq + 1));
    else values.set(arg.slice(2), argv[++i] ?? "");
  }
  const origin = values.get("origin") ?? "";
  const sha = values.get("sha") ?? "";
  if (!origin || !sha) {
    throw new Error("usage: probe.ts --origin=https://<host> --sha=<commit> (both required)");
  }
  return { origin: origin.replace(/\/+$/, ""), sha };
}

/** What a red run should tell the operator to look at. */
export function explain(cause: Cause): string {
  switch (cause) {
    case "match":
      return "the deployed bundle is serving";
    case "unreachable":
    case "server-error":
      return "the script is not serving — it most likely failed to boot (a missing or blank secret " +
        "makes readConfig throw at module top level, before any handler runs)";
    case "store-unreachable":
      return "this bundle is serving but cannot reach its relational store — check BUNNY_DATABASE_URL " +
        "and BUNNY_DATABASE_AUTH_TOKEN on the Edge Script, and that the database still exists";
    case "foreign-keys-off":
      return "this bundle is serving and its store answers, but PRAGMA foreign_keys is OFF — every " +
        "constraint is silently disabled, so a deleted event would leave its memberships and assets " +
        "behind. Fix the store's provisioning; no amount of waiting turns enforcement on";
    case "not-found":
      return "an older bundle without the health route is still live — the publish did not take";
    case "stale-sha":
      return "a different bundle is serving — this deploy never propagated";
    case "unstamped":
      return "the live bundle carries the unstamped placeholder — CI supplied no commit to the resolver";
    case "unparseable":
      return "something other than this backend answered — check DNS and the pull zone";
  }
}

if (import.meta.main) {
  try {
    const { origin, sha } = parseArgs(Deno.args);
    const verdict = await runProbe({
      fetch: (url, init) => fetch(url, init),
      origin,
      expectedSha: sha,
      now: Date.now,
      sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
      log: console.log,
    });
    if (verdict.ok) {
      console.log(`probe: PASS after ${verdict.attempts} attempt(s) — ${origin} is serving ${sha}`);
    } else {
      console.error(
        `probe: FAIL (${verdict.cause}) after ${verdict.attempts} attempt(s) — ${
          explain(verdict.cause)
        }\n` +
          `  last saw: ${verdict.detail}`,
      );
      Deno.exit(1);
    }
  } catch (e) {
    console.error(`probe: ${e instanceof Error ? e.message : e}`);
    Deno.exit(1);
  }
}
