// THE VERIFY TEST (capability `database`): the created schema and the migrated schema are the same schema.
//
// `db.ts`'s `SCHEMA` builds the tables from nothing; `migrations.ts`'s `MIGRATIONS` evolves a store that
// already holds rows. Both describe the same shape, and nothing but this test makes that true — edit one
// and forget the other and the deployed store quietly stops matching the statements written against it.
//
// This is the property the DEVICE side already gets for free from SQLDelight, whose `6.sqm` names it:
// "the verify task compares migrated vs created schemas". There is no such task in Deno, so it is written
// out here.
//
// WHAT IS COMPARED. The normalized `sqlite_master` SQL of every table and index. Text rather than
// `PRAGMA table_info`, because the pragma cannot see the two things most worth catching: `STRICT` (a
// table that silently coerces where its twin rejects) and a `FOREIGN KEY … ON DELETE CASCADE` clause (an
// event whose deletion strands its memberships). Normalization strips `--` comments and collapses
// whitespace, so the two forms may be laid out and annotated differently — as they are, `SCHEMA` carrying
// prose that v1's frozen historical copy must not.

import { assertEquals, assertRejects } from "@std/assert";
import { SCHEMA } from "../src/db.ts";
import { appliedVersions, migrate, MIGRATIONS } from "../src/migrations.ts";
import { sqliteDb } from "../src/dev/db-sqlite.ts";

/**
 * A store's schema as a comparable value: every table and index, by name, with its defining SQL stripped
 * of comments and reflowed. `schema_migrations` is excluded — it is the runner's own bookkeeping and by
 * construction exists in only one of the two stores.
 */
async function shapeOf(
  db: { execute: (sql: string) => Promise<{ rows: Record<string, unknown>[] }> },
) {
  const { rows } = await db.execute(
    `SELECT name, sql FROM sqlite_master
      WHERE type IN ('table', 'index')
        AND name NOT LIKE 'sqlite_%'
        AND name <> 'schema_migrations'
      ORDER BY name`,
  );
  return rows.map((r) => ({
    name: String(r.name),
    sql: String(r.sql ?? "")
      .replace(/--[^\n]*/g, "") // prose, which the two forms are free to differ on
      // `IF NOT EXISTS` and the quoting SQLite adds when `ALTER TABLE … RENAME TO` rewrites
      // sqlite_master. Both are spelling, not shape: a tightening migration necessarily ends in a
      // rename, and comparing raw text would fail on the quotes alone.
      .replace(/\bIF NOT EXISTS\b/gi, "")
      .replace(/"/g, "")
      .replace(/\s+/g, " ")
      .trim(),
  }));
}

Deno.test("the created schema and the migrated schema are identical", async () => {
  const created = sqliteDb(":memory:");
  for (const sql of SCHEMA) await created.execute(sql);

  const migrated = sqliteDb(":memory:");
  await migrate(migrated);

  assertEquals(await shapeOf(migrated), await shapeOf(created));

  created.close();
  migrated.close();
});

Deno.test("migrate records every version, and re-running applies nothing", async () => {
  const db = sqliteDb(":memory:");
  await migrate(db);
  assertEquals(await appliedVersions(db), MIGRATIONS.map((m) => m.version));

  // Re-running is the ordinary case, not an edge one: the CI step runs on every deploy.
  const applied: string[] = [];
  await migrate(db, (msg) => applied.push(msg));
  assertEquals(applied, []);
  assertEquals(await appliedVersions(db), MIGRATIONS.map((m) => m.version));

  db.close();
});

Deno.test("a live-shaped store is migrated forward, and REFUSES to tighten before its data is ready", async () => {
  // The DEPLOYED store's exact position: v1's tables present, rows in them, no version record.
  const db = sqliteDb(":memory:");
  const v1 = MIGRATIONS.find((m) => m.version === 1)!;
  for (const sql of v1.statements) await db.execute(sql);
  await db.execute(
    `INSERT INTO device_records (device_id, push_kind, push_token, push_env, updated_at)
     VALUES ('d1', 'apns', 'tok', 'production', '2026-08-01T00:00:00.000Z')`,
  );

  // v3 cannot carry a row that has no attestation, and a migration must migrate its data rather than
  // discard it — so it refuses, leaving the store on v2 with everything intact.
  await assertRejects(() => migrate(db), Error, "refusing to tighten");

  assertEquals(await appliedVersions(db), [1, 2]);
  const { rows } = await db.execute(`SELECT * FROM devices`);
  assertEquals(rows.length, 1);
  assertEquals(rows[0].push_token, "tok"); // CARRIED, not dropped — the whole point
  assertEquals(rows[0].created_at, "2026-08-01T00:00:00.000Z"); // seeded from updated_at
  assertEquals(rows[0].attest_key, null); // filled by the one-time backfill, or by the device attesting

  // Once every row has an attestation, the tightening is total: nothing left to drop.
  await db.execute(
    `UPDATE devices SET attest_key = 'k', attest_env = 'production', attested_at = 'a',
                        attest_token_expires_at = 'e'`,
  );
  await migrate(db);
  assertEquals(await appliedVersions(db), MIGRATIONS.map((m) => m.version));
  const after = await db.execute(`SELECT push_token FROM devices`);
  assertEquals(after.rows, [{ push_token: "tok" }]); // survived the tightening rebuild too

  db.close();
});
