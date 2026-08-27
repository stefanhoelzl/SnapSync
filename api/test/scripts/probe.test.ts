import { assert, assertEquals, assertThrows } from "@std/assert";
import { type Cause, classify, parseArgs, runProbe, UNSTAMPED } from "../../src/scripts/probe.ts";
import type { FetchLike } from "../../src/storage.ts";

// The probe's whole value is its DECISION TABLE, so the table is what is tested — with an injected fetch
// and an injected clock, so the suite still runs with no `--allow-net` and takes no wall-clock time.

const SHA = "98f49ef8a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
const OTHER = "6c560f7511223344556677889900aabbccddeeff";

const ok = (body: string, status = 200) => new Response(body, { status });
// A healthy answer carries the WINDOW STATE beside the bundle id. It has to: a migrating deploy publishes
// the same commit twice — the maintenance bundle, then the ordinary one — so the sha alone cannot say
// which of the two is answering. (An unreachable dependency is no longer described here; the health route
// answers a non-success status for it, which lands in `server-error`.)
const health = (sha: string, maintenance = false) =>
  ok(JSON.stringify(maintenance ? { sha, maintenance } : { sha }));

/** A fetch that returns each scripted answer in turn, repeating the last one forever. */
function scripted(...answers: Array<() => Response | Promise<never>>): {
  fetch: FetchLike;
  calls: () => number;
} {
  let i = 0;
  return {
    calls: () => i,
    fetch: ((_url: string, _init?: RequestInit) => {
      const answer = answers[Math.min(i, answers.length - 1)];
      i++;
      return Promise.resolve(answer());
    }) as unknown as FetchLike,
  };
}

/** A clock that advances by `step` on every read, so a deadline is reached deterministically. */
function clock(step: number) {
  let t = 0;
  return () => (t += step);
}

const noSleep = () => Promise.resolve();

// ── The table, cell by cell ────────────────────────────────────────────────────────────────────────

Deno.test("classify: our sha → match", () => {
  assertEquals(
    classify(200, JSON.stringify({ sha: SHA, maintenance: false }), SHA, false),
    "match",
  );
});

Deno.test("classify: 404 → not-found (an older bundle without the route is still live)", () => {
  assertEquals(classify(404, "", SHA, false), "not-found");
});

Deno.test("classify: 5xx → server-error", () => {
  for (const status of [500, 502, 503]) {
    assertEquals(classify(status, "", SHA, false), "server-error");
  }
});

Deno.test("classify: a different well-formed sha → stale-sha (propagating)", () => {
  assertEquals(classify(200, JSON.stringify({ sha: OTHER }), SHA, false), "stale-sha");
});

Deno.test("classify: `dev` on the wire is STALE when we asked for a commit", () => {
  // The bundle answering is not ours — it is a previous deployment that predates stamping, still being
  // served while ours propagates. Time fixes that, so it must be retryable.
  //
  // This test previously asserted the OPPOSITE, which is why the conflation survived review: the probe
  // failed at attempt one on a deploy that was live and healthy a minute later (2026-08-25, run
  // 32901373285).
  assertEquals(classify(200, JSON.stringify({ sha: UNSTAMPED }), SHA, false), "stale-sha");
});

Deno.test("classify: asking for `dev` is UNSTAMPED whatever answers", () => {
  // Expecting `dev` means THIS run built an unstamped bundle — CI supplied no sha. That is terminal on
  // its own terms: no answer from the origin can make the artifact stamped, so neither the matching
  // reply nor a different one is worth retrying.
  assertEquals(classify(200, JSON.stringify({ sha: UNSTAMPED }), UNSTAMPED, false), "unstamped");
  assertEquals(classify(200, JSON.stringify({ sha: "other" }), UNSTAMPED, false), "unstamped");
});

Deno.test("classify: an HTML error page returned with 200 → unparseable", () => {
  // Robust to bunny behaviour nobody has measured: a 200-with-error-page still lands in a red cell.
  assertEquals(classify(200, "<html><body>error</body></html>", SHA, false), "unparseable");
});

Deno.test("classify: valid JSON without a usable sha → unparseable", () => {
  assertEquals(classify(200, JSON.stringify({}), SHA, false), "unparseable");
  assertEquals(classify(200, JSON.stringify({ sha: 7 }), SHA, false), "unparseable");
  assertEquals(classify(200, JSON.stringify({ sha: "" }), SHA, false), "unparseable");
});

Deno.test("classify: an unexpected non-200 status → unparseable", () => {
  assertEquals(classify(302, "", SHA, false), "unparseable");
  assertEquals(classify(401, "", SHA, false), "unparseable");
});

Deno.test("classify: the right commit in the OTHER publish's state → wrong-maintenance-state", () => {
  // The cell that only exists because a migrating deploy publishes ONE commit TWICE. Both directions,
  // because both probes run: the pre-migration one must not accept the ordinary bundle, and the final one
  // must not accept the maintenance bundle — the second is what stops a run that never lifted the window
  // from reporting success.
  assertEquals(classify(200, JSON.stringify({ sha: SHA }), SHA, true), "wrong-maintenance-state");
  assertEquals(
    classify(200, JSON.stringify({ sha: SHA, maintenance: true }), SHA, false),
    "wrong-maintenance-state",
  );
});

Deno.test("classify: the right commit in the EXPECTED state → match, in both directions", () => {
  assertEquals(classify(200, JSON.stringify({ sha: SHA, maintenance: true }), SHA, true), "match");
  assertEquals(
    classify(200, JSON.stringify({ sha: SHA, maintenance: false }), SHA, false),
    "match",
  );
});

Deno.test("classify: an ABSENT window field means closed", () => {
  // The health route omits it unless the window is open, and the only other thing that omits it is a
  // bundle predating the flag — built before maintenance mode existed, so necessarily serving the device
  // API. Both causes are the same answer, which is what makes the collapse safe.
  assertEquals(classify(200, JSON.stringify({ sha: SHA }), SHA, false), "match");
  assertEquals(classify(200, JSON.stringify({ sha: SHA }), SHA, true), "wrong-maintenance-state");
});

Deno.test("classify: a PRESENT but non-boolean window field is unparseable", () => {
  // Not an absence — this backend's response shape is wrong, which is a different answer from "closed"
  // and must not be collapsed into it.
  assertEquals(
    classify(200, JSON.stringify({ sha: SHA, maintenance: "true" }), SHA, true),
    "unparseable",
  );
  assertEquals(
    classify(200, JSON.stringify({ sha: SHA, maintenance: 1 }), SHA, false),
    "unparseable",
  );
});

Deno.test("classify: an unreachable dependency reaches the probe as server-error", () => {
  // The health route answers a bare non-success when its store or its zone is unreachable — it no longer
  // describes the cause in a 200 body, so `store-unreachable` and `foreign-keys-off` have no cells.
  assertEquals(classify(503, "", SHA, false), "server-error");
});

// ── Retry: only what time can fix ──────────────────────────────────────────────────────────────────

async function probe(
  fetchImpl: FetchLike,
  opts: { step?: number; expectedSha?: string; expectMaintenance?: boolean } = {},
) {
  return await runProbe({
    fetch: fetchImpl,
    origin: "https://example.invalid",
    expectedSha: opts.expectedSha ?? SHA,
    expectMaintenance: opts.expectMaintenance ?? false,
    now: clock(opts.step ?? 1),
    sleep: noSleep,
  });
}

Deno.test("runProbe: passes on the first attempt when the bundle is already serving", async () => {
  const s = scripted(() => health(SHA));
  const v = await probe(s.fetch);
  assert(v.ok);
  assertEquals(v.cause, "match");
  assertEquals(v.attempts, 1);
});

Deno.test("runProbe: retries a connection failure, then passes", async () => {
  const s = scripted(
    () => Promise.reject(new TypeError("connection refused")),
    () => health(SHA),
  );
  const v = await probe(s.fetch);
  assert(v.ok);
  assertEquals(v.attempts, 2);
});

Deno.test("runProbe: retries 404 then the previous bundle, then passes", async () => {
  const s = scripted(
    () => ok("", 404),
    () => health(OTHER),
    () => health(SHA),
  );
  const v = await probe(s.fetch);
  assert(v.ok);
  assertEquals(v.cause, "match");
  assertEquals(v.attempts, 3);
});

Deno.test("runProbe: an unstamped bundle fails IMMEDIATELY, without burning the deadline", async () => {
  // Asking for `dev` and being handed something else: this run built no stamp. Terminal.
  const s = scripted(() => health("something-else"));
  const v = await probe(s.fetch, { expectedSha: UNSTAMPED });
  assert(!v.ok);
  assertEquals(v.cause, "unstamped");
  assertEquals(v.attempts, 1); // terminal: exactly one request, not a two-minute timeout
});

Deno.test("runProbe: a `dev` bundle still serving is RETRIED, then passes", async () => {
  // The regression this pair exists for. A previous deployment that predates stamping answers while
  // ours propagates; the probe must wait it out rather than fail the deploy that is about to be live.
  const s = scripted(() => health(UNSTAMPED), () => health(UNSTAMPED), () => health(SHA));
  const v = await probe(s.fetch);
  assert(v.ok);
  assertEquals(v.cause, "match");
  assertEquals(v.attempts, 3);
});

Deno.test("runProbe: an unparseable answer fails IMMEDIATELY", async () => {
  const s = scripted(() => ok("<html>nope</html>"));
  const v = await probe(s.fetch);
  assert(!v.ok);
  assertEquals(v.cause, "unparseable");
  assertEquals(v.attempts, 1);
});

// ── The deadline ───────────────────────────────────────────────────────────────────────────────────

Deno.test("runProbe: a permanently dead script fails at the deadline, naming what it last saw", async () => {
  const s = scripted(() => Promise.reject(new TypeError("connection refused")));
  const v = await runProbe({
    fetch: s.fetch,
    origin: "https://example.invalid",
    expectedSha: SHA,
    expectMaintenance: false,
    now: clock(30_000), // four reads per elapsed 120s
    sleep: noSleep,
    deadlineMs: 120_000,
  });
  assert(!v.ok);
  assertEquals(v.cause, "unreachable");
  assert(v.attempts > 1, "should have retried before giving up");
  assert(v.detail.includes("connection refused"), v.detail);
});

Deno.test("runProbe: a bundle that never propagates fails as stale-sha, not as a timeout", async () => {
  const s = scripted(() => health(OTHER));
  const v = await runProbe({
    fetch: s.fetch,
    origin: "https://example.invalid",
    expectedSha: SHA,
    expectMaintenance: false,
    now: clock(30_000),
    sleep: noSleep,
    deadlineMs: 60_000,
  });
  assert(!v.ok);
  assertEquals(v.cause, "stale-sha");
});

Deno.test("runProbe: one answer arriving exactly at the deadline still passes", async () => {
  const s = scripted(() => ok("", 404), () => health(SHA));
  const v = await runProbe({
    fetch: s.fetch,
    origin: "https://example.invalid",
    expectedSha: SHA,
    expectMaintenance: false,
    now: clock(120_000), // the first attempt already sits on the deadline
    sleep: noSleep,
    deadlineMs: 120_000,
  });
  // The deadline is checked AFTER classifying, so a success on the boundary is honoured.
  assertEquals(v.cause, "not-found");
  assert(!v.ok, "a retryable cause at the deadline gives up rather than sleeping again");
});

Deno.test("runProbe: every attempt is logged with its cause", async () => {
  const lines: string[] = [];
  const s = scripted(() => ok("", 503), () => health(SHA));
  await runProbe({
    fetch: s.fetch,
    origin: "https://example.invalid",
    expectedSha: SHA,
    expectMaintenance: false,
    now: clock(1),
    sleep: noSleep,
    log: (l) => lines.push(l),
  });
  assertEquals(lines.length, 2);
  assert(lines[0].includes("server-error"), lines[0]);
  assert(lines[1].includes("match"), lines[1]);
});

Deno.test("runProbe: requests /health at the given origin", async () => {
  let seen = "";
  const fetchImpl = ((url: string) => {
    seen = url;
    return Promise.resolve(health(SHA));
  }) as unknown as FetchLike;
  await runProbe({
    fetch: fetchImpl,
    origin: "https://example.invalid",
    expectedSha: SHA,
    expectMaintenance: false,
    now: clock(1),
    sleep: noSleep,
  });
  assertEquals(seen, "https://example.invalid/health");
});

// ── argv ───────────────────────────────────────────────────────────────────────────────────────────

Deno.test("parseArgs: both forms, and a trailing slash is trimmed", () => {
  assertEquals(parseArgs(["--origin=https://a.invalid/", "--sha=abc"]), {
    origin: "https://a.invalid",
    sha: "abc",
    maintenance: false,
  });
  assertEquals(parseArgs(["--origin", "https://a.invalid", "--sha", "abc"]), {
    origin: "https://a.invalid",
    sha: "abc",
    maintenance: false,
  });
});

Deno.test("parseArgs: either argument missing is an error — neither has a safe default", () => {
  // A defaulted sha would compare against the wrong expectation and pass green, which is the one
  // outcome this must never produce.
  assertThrows(() => parseArgs(["--origin=https://a.invalid"]), Error, "origin and sha required");
  assertThrows(() => parseArgs(["--sha=abc"]), Error, "origin and sha required");
  assertThrows(() => parseArgs([]), Error, "origin and sha required");
});

Deno.test("parseArgs: the window flag defaults CLOSED and accepts both forms", () => {
  // Closed is the state every non-migrating deploy expects, so a caller who has not thought about it
  // gets the assertion they meant.
  const base = ["--origin=https://a.invalid", "--sha=abc"];
  assertEquals(parseArgs(base).maintenance, false);
  assertEquals(parseArgs([...base, "--maintenance"]).maintenance, true);
  assertEquals(parseArgs([...base, "--maintenance=true"]).maintenance, true);
  assertEquals(parseArgs([...base, "--maintenance=false"]).maintenance, false);
});

Deno.test("parseArgs: a non-boolean window flag is refused, never coerced", () => {
  // `--maintenance=yes` quietly meaning "closed" would make a run assert the opposite of its intent —
  // and the pre-migration probe would then accept the un-migrated bundle it exists to reject.
  assertThrows(
    () => parseArgs(["--origin=https://a.invalid", "--sha=abc", "--maintenance=yes"]),
    Error,
    "must be 'true' or 'false'",
  );
});

Deno.test("runProbe: the maintenance bundle still serving is RETRIED, then passes", async () => {
  // The final probe's real shape: the ordinary bundle has been published but the maintenance one is
  // still answering this PoP. That is propagation, not failure — the same judgement `stale-sha` gets.
  const s = scripted(() => health(SHA, true), () => health(SHA, true), () => health(SHA, false));
  const v = await probe(s.fetch);
  assert(v.ok);
  assertEquals(v.cause, "match");
  assertEquals(v.attempts, 3);
});

Deno.test("runProbe: a window that is never lifted fails as wrong-maintenance-state", async () => {
  // The failure this probe was extended to catch: the run published the ordinary bundle, it never took,
  // and without this the deploy would report success with the API serving 503 to every device.
  const s = scripted(() => health(SHA, true));
  const v = await probe(s.fetch, { step: 60_000 });
  assert(!v.ok);
  assertEquals(v.cause, "wrong-maintenance-state");
});

Deno.test("the retryable set is exactly the causes time can fix", () => {
  // Pinned as a set so adding a cause forces a deliberate decision about whether waiting can help.
  const terminal: Cause[] = ["unstamped", "unparseable"];
  for (const cause of terminal) {
    assert(cause === "unstamped" || cause === "unparseable");
  }
});
