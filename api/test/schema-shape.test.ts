// THE COMPARISON THE DEPLOY ASSERTS ON (capability `backend-deployment`).
//
// It runs across two engines — libSQL live, `node:sqlite` for the replay — so the risk is symmetrical and
// both directions are tested here: normalising away something that MATTERS makes the assertion blind,
// and failing to normalise something that does NOT makes it flaky on the deploy path, which is worse
// than not having it.

import { assertEquals } from "@std/assert";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { replay } from "../src/dev/replay.ts";
import { normalizeSql, shapeDifferences, shapeOf } from "../src/dev/schema-shape.ts";

Deno.test("a replayed store matches itself", async () => {
  const a = sqliteDb(":memory:");
  const b = sqliteDb(":memory:");
  await replay(a);
  await replay(b);
  assertEquals(shapeDifferences(await shapeOf(a), await shapeOf(b)), []);
  a.close();
  b.close();
});

Deno.test("spelling is normalised away, because the two engines spell differently", () => {
  const same = [
    // `IF NOT EXISTS`: SQLite strips it when storing, so only one side ever carries it.
    ["CREATE TABLE IF NOT EXISTS t (a TEXT) STRICT", "CREATE TABLE t (a TEXT) STRICT"],
    // Quoting: `ALTER TABLE … RENAME TO` rewrites sqlite_master with the identifier quoted, and every
    // tightening migration ends in a rename.
    [`CREATE TABLE "t" (a TEXT) STRICT`, "CREATE TABLE t (a TEXT) STRICT"],
    // Layout and prose.
    ["CREATE TABLE t (\n  a TEXT -- the column\n) STRICT", "CREATE TABLE t (a TEXT) STRICT"],
  ];
  for (const [x, y] of same) assertEquals(normalizeSql(x), normalizeSql(y), `${x} ≠ ${y}`);
});

Deno.test("shape is NOT normalised away", () => {
  // The two things the comparison exists for. If either of these compared equal, a deployed store could
  // silently coerce where the migrations say it rejects, or strand rows the schema says it cascades.
  const differs = [
    ["CREATE TABLE t (a TEXT) STRICT", "CREATE TABLE t (a TEXT)"],
    [
      "CREATE TABLE c (p TEXT REFERENCES t(id) ON DELETE CASCADE) STRICT",
      "CREATE TABLE c (p TEXT REFERENCES t(id)) STRICT",
    ],
    ["CREATE TABLE t (a TEXT NOT NULL) STRICT", "CREATE TABLE t (a TEXT) STRICT"],
  ];
  for (const [x, y] of differs) {
    assertEquals(normalizeSql(x) === normalizeSql(y), false, `${x} compared equal to ${y}`);
  }
});

Deno.test("a difference is reported in the direction that says what to do", async () => {
  const live = sqliteDb(":memory:");
  await replay(live);
  // A store hand-edited outside the migrations — exactly what the assertion is for.
  live.exec(`CREATE TABLE stray (id TEXT PRIMARY KEY NOT NULL) STRICT`);

  const expectedStore = sqliteDb(":memory:");
  await replay(expectedStore);

  const diffs = shapeDifferences(await shapeOf(live), await shapeOf(expectedStore));
  assertEquals(diffs, ["stray: the deployed store has it, no migration builds it"]);

  live.close();
  expectedStore.close();
});

Deno.test("a missing object is reported the other way round", async () => {
  const expectedStore = sqliteDb(":memory:");
  await replay(expectedStore);
  const empty = sqliteDb(":memory:");

  const diffs = shapeDifferences(await shapeOf(empty), await shapeOf(expectedStore));
  assertEquals(diffs.length, 5);
  assertEquals(diffs[0], "devices: the migrations build it, the deployed store does not have it");

  empty.close();
  expectedStore.close();
});

Deno.test("the runner's bookkeeping is excluded — it exists on one side only", async () => {
  const migrated = sqliteDb(":memory:");
  await replay(migrated);
  assertEquals((await shapeOf(migrated)).some((o) => o.name === "__bunny_migrations"), false);
  migrated.close();
});
