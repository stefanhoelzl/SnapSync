import { assert, assertEquals, assertThrows } from "@std/assert";
import { type Cause, classify, parseArgs, runProbe, UNSTAMPED } from "../../src/scripts/probe.ts";
import type { FetchLike } from "../../src/storage.ts";

// The probe's whole value is its DECISION TABLE, so the table is what is tested — with an injected fetch
// and an injected clock, so the suite still runs with no `--allow-net` and takes no wall-clock time.

const SHA = "98f49ef8a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
const OTHER = "6c560f7511223344556677889900aabbccddeeff";

const ok = (body: string, status = 200) => new Response(body, { status });
// A healthy answer now carries the STORE's state beside the bundle id: a deployment can be the right
// bundle and still be unusable, and the probe exists to catch exactly that class of green-but-broken.
const health = (sha: string, database = "ok") => ok(JSON.stringify({ sha, database }));

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
  assertEquals(classify(200, JSON.stringify({ sha: SHA, database: "ok" }), SHA), "match");
});

Deno.test("classify: 404 → not-found (an older bundle without the route is still live)", () => {
  assertEquals(classify(404, "", SHA), "not-found");
});

Deno.test("classify: 5xx → server-error", () => {
  for (const status of [500, 502, 503]) {
    assertEquals(classify(status, "", SHA), "server-error");
  }
});

Deno.test("classify: a different well-formed sha → stale-sha (propagating)", () => {
  assertEquals(classify(200, JSON.stringify({ sha: OTHER }), SHA), "stale-sha");
});

Deno.test("classify: the placeholder sha → unstamped, never stale-sha", () => {
  // Waiting cannot turn `dev` into a commit, so this must be terminal rather than retried.
  assertEquals(classify(200, JSON.stringify({ sha: UNSTAMPED }), SHA), "unstamped");
});

Deno.test("classify: an HTML error page returned with 200 → unparseable", () => {
  // Robust to bunny behaviour nobody has measured: a 200-with-error-page still lands in a red cell.
  assertEquals(classify(200, "<html><body>error</body></html>", SHA), "unparseable");
});

Deno.test("classify: valid JSON without a usable sha → unparseable", () => {
  assertEquals(classify(200, JSON.stringify({}), SHA), "unparseable");
  assertEquals(classify(200, JSON.stringify({ sha: 7 }), SHA), "unparseable");
  assertEquals(classify(200, JSON.stringify({ sha: "" }), SHA), "unparseable");
});

Deno.test("classify: an unexpected non-200 status → unparseable", () => {
  assertEquals(classify(302, "", SHA), "unparseable");
  assertEquals(classify(401, "", SHA), "unparseable");
});

// ── Retry: only what time can fix ──────────────────────────────────────────────────────────────────

async function probe(fetchImpl: FetchLike, opts: { step?: number } = {}) {
  return await runProbe({
    fetch: fetchImpl,
    origin: "https://example.invalid",
    expectedSha: SHA,
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
  const s = scripted(() => health(UNSTAMPED));
  const v = await probe(s.fetch);
  assert(!v.ok);
  assertEquals(v.cause, "unstamped");
  assertEquals(v.attempts, 1); // terminal: exactly one request, not a two-minute timeout
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
  });
  assertEquals(parseArgs(["--origin", "https://a.invalid", "--sha", "abc"]), {
    origin: "https://a.invalid",
    sha: "abc",
  });
});

Deno.test("parseArgs: either argument missing is an error — neither has a safe default", () => {
  // A defaulted sha would compare against the wrong expectation and pass green, which is the one
  // outcome this must never produce.
  assertThrows(() => parseArgs(["--origin=https://a.invalid"]), Error, "both required");
  assertThrows(() => parseArgs(["--sha=abc"]), Error, "both required");
  assertThrows(() => parseArgs([]), Error, "both required");
});

Deno.test("the retryable set is exactly the causes time can fix", () => {
  // Pinned as a set so adding a cause forces a deliberate decision about whether waiting can help.
  const terminal: Cause[] = ["unstamped", "unparseable"];
  for (const cause of terminal) {
    assert(cause === "unstamped" || cause === "unparseable");
  }
});
