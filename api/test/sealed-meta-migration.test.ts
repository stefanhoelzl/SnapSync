// Migration 0017 (decision record `changes/seal-event-name`, D4) against rows written before it: the rebuild of
// `events` keeps every existing row and every row that references one, and its CHECK admits exactly the three
// legal shapes — plain, sealed, and legacy encrypted.

import { assertEquals, assertRejects } from "@std/assert";
import { join } from "@std/path";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { discoverMigrations, MIGRATIONS_DIR, replay } from "../src/dev/replay.ts";

const KEY_ID = "0123456789abcdef";
const ENVELOPE = "AQ" + "A".repeat(40);

/** A store replayed up to, and not including, 0017 — the shape the deployed store has before this change. */
async function storeBefore0017() {
  const dir = await Deno.makeTempDir();
  for (const m of discoverMigrations(MIGRATIONS_DIR)) {
    if (m.name >= "0017") break;
    await Deno.copyFile(m.path, join(dir, m.name));
  }
  const db = sqliteDb(":memory:");
  await replay(db, dir);
  return db;
}

const COLUMNS =
  `id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds, key_id, open_until`;
const row = (id: string, name: string | null, keyId: string | null) => [
  id,
  name,
  "2026-11-01T00:00:00Z",
  "2026-11-01T00:00:00Z",
  "2026-11-02T00:00:00Z",
  50,
  2592000,
  keyId,
  "2026-11-02T00:00:00Z",
];

Deno.test("0017 keeps every plain and legacy encrypted event, and what references them", async () => {
  const db = await storeBefore0017();
  await db.execute(
    `INSERT INTO events (${COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    row("plain", "Picnic", null),
  );
  await db.execute(
    `INSERT INTO events (${COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    row("legacy", "Ana's 30th", KEY_ID),
  );
  await db.execute(
    `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES ('legacy', 'd1', 'sharing', 'x')`,
  );
  await db.execute(`INSERT INTO union_log (event_id, kind, at) VALUES ('plain', 'gained', 'x')`);

  assertEquals(await replay(db), ["0017_sealed_event_meta.sql"]);

  const { rows } = await db.execute(`SELECT id, name, key_id, sealed_meta FROM events ORDER BY id`);
  assertEquals(rows.map((r) => [r.id, r.name, r.key_id, r.sealed_meta]), [
    ["legacy", "Ana's 30th", KEY_ID, null],
    ["plain", "Picnic", null, null],
  ]);
  assertEquals(
    (await db.execute(`SELECT COUNT(*) AS n FROM memberships`)).rows[0].n,
    1,
    "no cascade",
  );
  assertEquals(
    (await db.execute(`SELECT COUNT(*) AS n FROM union_log`)).rows[0].n,
    1,
    "no cascade",
  );
  db.close();
});

Deno.test("after 0017 a sealed event has no readable name, and every illegal shape is refused", async () => {
  const db = sqliteDb(":memory:");
  await replay(db);
  // `async`, so a refusal the driver throws synchronously still arrives as a rejection.
  const insert = async (
    id: string,
    name: string | null,
    keyId: string | null,
    sealed: string | null,
  ) =>
    await db.execute(
      `INSERT INTO events (${COLUMNS}, sealed_meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [...row(id, name, keyId), sealed],
    );
  await insert("sealed", null, KEY_ID, ENVELOPE);
  await insert("plain", "Picnic", null, null);
  await insert("legacy", "Ana's 30th", KEY_ID, null);
  await assertRejects(() => insert("a", null, null, null), Error, "CHECK", "plain without a name");
  await assertRejects(
    () => insert("b", "x", null, ENVELOPE),
    Error,
    "CHECK",
    "a plain event with a record",
  );
  await assertRejects(
    () => insert("c", null, KEY_ID, null),
    Error,
    "CHECK",
    "encrypted with neither",
  );
  await assertRejects(
    () => insert("d", "x", KEY_ID, ENVELOPE),
    Error,
    "CHECK",
    "encrypted with both",
  );
  db.close();
});
