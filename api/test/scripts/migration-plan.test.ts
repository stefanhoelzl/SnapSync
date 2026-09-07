// THE WINDOW DECISION (capabilities `database`, `backend-deployment`).
//
// What `api-deploy.yml` branches a production outage on. Wrong in either direction is expensive: a false
// "none" publishes new code onto an un-migrated store, and a false "pending" imposes a 503 window on a
// deploy that changes no schema.
//
// The fixtures below are the CLI's own `--output json` shape, taken from its implementation rather than
// invented — `{migrations: [{name, state, applied_at}]}`, with `state` one of applied / pending /
// modified / missing / out_of_order. No network: the subprocess is a thin shell around `planFrom`, and
// this is the part that decides anything.

import { assertEquals, assertStringIncludes } from "@std/assert";
import {
  type ListOutput,
  PENDING_EXIT_CODE,
  planFrom,
  planLine,
} from "../../src/scripts/migration-plan.ts";

const listing = (...ms: [string, ListOutput["migrations"][number]["state"]][]): ListOutput => ({
  migrations: ms.map(([name, state]) => ({
    name,
    state,
    applied_at: state === "applied" || state === "modified" ? "2026-09-01 00:00:00" : null,
  })),
});

Deno.test("a fully-applied store has nothing pending — the answer that skips the window", () => {
  const plan = planFrom(listing(["0001_baseline.sql", "applied"]));
  assertEquals(plan.pending, []);
  assertEquals(plan.applied, ["0001_baseline.sql"]);
  assertEquals(planLine(plan), "MIGRATE PLAN: none (1 applied)");
});

Deno.test("a store behind the files reports exactly what is missing, in order", () => {
  const plan = planFrom(listing(
    ["0001_baseline.sql", "applied"],
    ["0002_a.sql", "pending"],
    ["0003_b.sql", "pending"],
  ));
  assertEquals(plan.pending, ["0002_a.sql", "0003_b.sql"]);
  assertEquals(planLine(plan), "MIGRATE PLAN: 2 pending (0002_a.sql, 0003_b.sql)");
});

Deno.test("a store that has never migrated has every file pending", () => {
  const plan = planFrom(listing(["0001_baseline.sql", "pending"]));
  assertEquals(plan.pending, ["0001_baseline.sql"]);
  // This is the cutover's own position: `__bunny_migrations` is empty, so the baseline reports as
  // pending even though applying it changes nothing. The window opens for an inert migration, once.
  assertEquals(plan.applied, []);
});

Deno.test("an empty listing is 'none', not a crash", () => {
  // A store with no migration files at all. It must not read as pending — that would open a window with
  // nothing to apply inside it.
  assertEquals(planFrom({ migrations: [] }).pending, []);
  assertEquals(planFrom({} as ListOutput).pending, []);
});

// ── Drift is REPORTED, and deliberately not gated on here ─────────────────────────────────────────

Deno.test("unsound history is named in the plan line but does not change the decision", () => {
  const plan = planFrom(listing(
    ["0001_baseline.sql", "applied"],
    ["0002_edited.sql", "modified"],
    ["0003_gone.sql", "missing"],
    ["0004_late.sql", "out_of_order"],
  ));
  // Nothing is pending, so no window opens — the drift is left to fail at `apply`, which refuses it
  // because the deploy never passes `--allow-drift`.
  assertEquals(plan.pending, []);
  assertEquals(plan.issues.map((i) => i.state), ["modified", "missing", "out_of_order"]);
  const line = planLine(plan);
  assertStringIncludes(line, "MIGRATE PLAN: none");
  assertStringIncludes(line, "3 with unsound history");
  assertStringIncludes(line, "0002_edited.sql:modified");
  assertStringIncludes(line, "apply will refuse");
});

Deno.test("drift alongside a pending migration still reports pending", () => {
  const plan = planFrom(listing(["0001_a.sql", "modified"], ["0002_b.sql", "pending"]));
  assertEquals(plan.pending, ["0002_b.sql"]);
  assertStringIncludes(planLine(plan), "1 pending (0002_b.sql)");
  assertStringIncludes(planLine(plan), "unsound history");
});

Deno.test("the pending exit code is the one the workflow branches on", () => {
  // Pinned so a change here shows up in a diff beside the workflow's `case` statement, which hard-codes
  // the same number. They are two halves of one contract and cannot be checked against each other.
  assertEquals(PENDING_EXIT_CODE, 10);
});
