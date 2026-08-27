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
// the one answering, it is the publish of that commit the caller asked for (a migrating deploy publishes
// the same commit twice — see `expectMaintenance`), both dependencies are reachable, and the whole
// device-facing chain — DNS, certificate, pull zone, script — is intact.
// WHAT IT DOES NOT PROVE: that any configured value is CORRECT beyond being addressable. `BUNNY_STORAGE_ZONE`
// naming a zone that does not exist is now covered — that was the half of the 2026-07 outage nothing
// watched — but a value that is present, wrong, and still resolves boots and probes green.
//
// WHAT IT CANNOT SEE AT ALL: the other ~118 points of presence. This polls ONE hostname, which resolves to
// ONE PoP, and no propagation contract is published (the vendor's own statements range from seconds to
// minutes). So a green probe before a migration means "very likely every PoP is serving the maintenance
// bundle", never "certainly". That residual is stated in `backend-deployment` rather than implied here.
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
  | "wrong-maintenance-state" // this bundle, but the OTHER publish of it — propagating — retryable
  | "unparseable"; // 200 that is not our health response: something else is answering — TERMINAL

/** Causes time can fix. Everything else fails at once: retrying a terminal cause only hides it. */
const RETRYABLE: ReadonlySet<Cause> = new Set<Cause>([
  "unreachable",
  // `server-error` now covers a dependency the health route could not reach, which used to be its own
  // `store-unreachable` cell. It stays retryable for the same reason that one was: a store or a zone that
  // does not answer at this instant may simply be waking.
  "server-error",
  "not-found",
  "stale-sha",
  // The right bundle in the other publish's state is propagation, exactly like `stale-sha` — a migrating
  // deploy publishes twice from ONE commit, so this is the only cell that can see the difference.
  "wrong-maintenance-state",
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
 *
 * `expectMaintenance` is what the CALLER asked for: a migrating deploy probes twice from one commit — once
 * expecting the window OPEN (before it migrates) and once expecting it CLOSED (after it publishes the
 * ordinary bundle). Without it the second probe would pass against the maintenance bundle and a run that
 * never lifted the window would report success.
 */
export function classify(
  status: number,
  body: string,
  expectedSha: string,
  expectMaintenance: boolean,
): Cause {
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
  // ⚠️ THE ORDER MATTERS, and getting it wrong turns a healthy deploy red. `dev` on the wire has TWO
  // causes with opposite actions, and only the EXPECTED sha separates them:
  //
  //   * we asked for `dev` and got it → THIS run built an unstamped bundle. Terminal, our bug.
  //   * we asked for a commit and got `dev` → the bundle answering is not ours. It is a previous
  //     deployment that predates stamping, still being served while ours propagates. That is exactly
  //     `stale-sha`, and time fixes it.
  //
  // Checking `sha === UNSTAMPED` FIRST collapsed the second into the first: the probe failed at attempt
  // one, without retrying, on a deploy that was live and healthy a minute later (2026-08-25, run
  // 32901373285 — `{"sha":"dev"}` at 21:45:14, the real commit serving by 21:47).
  if (sha !== expectedSha) return expectedSha === UNSTAMPED ? "unstamped" : "stale-sha";
  if (sha === UNSTAMPED) return "unstamped";

  // This IS the commit we deployed — but a migrating deploy publishes that commit TWICE, so identity is
  // no longer enough. Ask which of the two is answering (capability `backend-deployment`).
  //
  // The store and the zone need no cell here any more: the health route reaches both itself and answers a
  // non-success status when either is unreachable, which lands in `server-error` above. That collapse is
  // safe because only ONE condition remains and it is retryable — the terminal one (foreign keys off) was
  // removed with the assertion behind it (capability `database`).
  let field: unknown;
  try {
    field = (JSON.parse(body) as { maintenance?: unknown }).maintenance;
  } catch {
    return "unparseable";
  }
  // ABSENT MEANS CLOSED, deliberately. The health route omits the field unless the window is open, and
  // the only other thing that can omit it is a bundle predating the flag — which was built before
  // maintenance mode existed and is therefore serving the device API. Both causes are the same answer, so
  // collapsing them loses nothing. A field that is PRESENT but not a boolean is still `unparseable`: that
  // is not an absence, it is this backend's response shape being wrong.
  const open = field === undefined ? false : field;
  if (typeof open !== "boolean") return "unparseable";
  if (open !== expectMaintenance) return "wrong-maintenance-state";
  return "match";
}

export type ProbeOptions = {
  fetch: FetchLike;
  /** The device-facing origin, e.g. `https://example.com` — no trailing slash. */
  origin: string;
  expectedSha: string;
  /** Which of a migrating deploy's two publishes of this commit must be answering. */
  expectMaintenance: boolean;
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
    expectMaintenance,
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
      cause = classify(res.status, body, expectedSha, expectMaintenance);
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

/**
 * `--name=value` / `--name value`. `--origin` and `--sha` are REQUIRED: there is no safe default for
 * either.
 *
 * `--maintenance` is a flag, defaulting to FALSE — the state every non-migrating deploy expects, and the
 * one a caller who has not thought about it wants. Only the pre-migration probe of a migrating deploy
 * passes it. A value other than `true`/`false` is refused rather than coerced: `--maintenance=yes`
 * silently meaning "closed" would let a run assert the opposite of what it intended.
 */
export function parseArgs(
  argv: readonly string[],
): { origin: string; sha: string; maintenance: boolean } {
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
    throw new Error(
      "usage: probe.ts --origin=https://<host> --sha=<commit> [--maintenance=true|false] " +
        "(origin and sha required)",
    );
  }
  const raw = values.has("maintenance") ? (values.get("maintenance") || "true") : "false";
  if (raw !== "true" && raw !== "false") {
    throw new Error(`probe.ts: --maintenance must be 'true' or 'false', not '${raw}'`);
  }
  return { origin: origin.replace(/\/+$/, ""), sha, maintenance: raw === "true" };
}

/** What a red run should tell the operator to look at. */
export function explain(cause: Cause): string {
  switch (cause) {
    case "match":
      return "the deployed bundle is serving";
    case "unreachable":
      return "the script is not serving — it most likely failed to boot (a missing or blank secret " +
        "makes readConfig throw at module top level, before any handler runs)";
    case "server-error":
      return "the script did not answer successfully. TWO causes now land here and the script's own " +
        "log separates them: it failed to boot (a missing or blank secret makes readConfig throw at " +
        "module top level), or it is serving but a DEPENDENCY is unreachable — check " +
        "BUNNY_DATABASE_URL / BUNNY_DATABASE_AUTH_TOKEN, and that BUNNY_STORAGE_ZONE names a zone " +
        "that exists";
    case "not-found":
      return "an older bundle without the health route is still live — the publish did not take";
    case "stale-sha":
      return "a different bundle is serving — this deploy never propagated";
    case "wrong-maintenance-state":
      return "this commit is serving, but the other of its two publishes — a migrating deploy publishes " +
        "the maintenance bundle and then the ordinary one from the SAME commit, so this means the " +
        "publish being probed has not propagated yet";
    case "unstamped":
      return "THIS run built an unstamped bundle — CI supplied no commit to the resolver. (A `dev` " +
        "reply while a real commit was expected is `stale-sha`, not this: it means a previous " +
        "deployment is still answering while ours propagates.)";
    case "unparseable":
      return "something other than this backend answered — check DNS and the pull zone";
  }
}

if (import.meta.main) {
  try {
    const { origin, sha, maintenance } = parseArgs(Deno.args);
    const verdict = await runProbe({
      fetch: (url, init) => fetch(url, init),
      origin,
      expectedSha: sha,
      expectMaintenance: maintenance,
      now: Date.now,
      sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
      log: console.log,
    });
    if (verdict.ok) {
      console.log(
        `probe: PASS after ${verdict.attempts} attempt(s) — ${origin} is serving ${sha} ` +
          `(maintenance ${maintenance ? "open" : "closed"})`,
      );
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
