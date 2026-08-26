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

// ── THE GATE: a migration migrates its data (capability `database`) ────────────────────────────────
//
// A migration is written once and read rarely, and the mistake this guards against is invisible in the
// diff: a `DROP TABLE` looks identical whether or not a copy precedes it. SQLite makes the wrong shape
// the easy one — a column's constraints cannot be altered in place, so any change to them forces a
// create-new/drop-old rebuild, and the `INSERT … SELECT` in the middle is the step it is possible to
// simply not write. That shape reads as a schema change and behaves as a deletion; it reached `main`
// once and was caught by a reviewer's question, not by anything mechanical.
//
// ⚠️ WHAT THIS CANNOT CATCH, stated so it is not over-trusted: it establishes that a copy is PRESENT,
// never that it is COMPLETE. A copy naming the wrong columns, or one narrowed by a `WHERE`, passes here.
// Completeness is what the per-migration tests above assert for the migrations that actually exist.
// The alternative — replay every migration against a populated store and compare row counts — is a better
// test of one migration and a worse gate over all of them: it would need representative data for every
// table a future migration touches, which is exactly what an author writing a bad migration would not
// supply. This check needs nothing from the author and so cannot be satisfied vacuously.

type Column = { name: string; notNull: boolean };

/** The columns a `CREATE TABLE` declares. Constraint clauses (PRIMARY KEY (…), FOREIGN KEY …) are not columns. */
function columnsOf(createSql: string): Column[] {
  const body = createSql.slice(createSql.indexOf("(") + 1, createSql.lastIndexOf(")"));
  const parts: string[] = [];
  let depth = 0;
  let current = "";
  for (const ch of body.replace(/--[^\n]*/g, "")) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    if (ch === "," && depth === 0) {
      parts.push(current);
      current = "";
      continue;
    }
    current += ch;
  }
  parts.push(current);
  return parts
    .map((p) => p.trim())
    .filter((p) => p && !/^(PRIMARY|FOREIGN|UNIQUE|CHECK|CONSTRAINT)\b/i.test(p))
    .map((p) => ({ name: p.split(/\s+/)[0], notNull: /\bNOT\s+NULL\b/i.test(p) }));
}

/** The tables a migration list defines, replayed statement by statement. */
type Schema = Map<string, Column[]>;

function applyToModel(schema: Schema, sql: string): void {
  const create = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["']?(\w+)["']?\s*\(/i.exec(sql);
  if (create) {
    schema.set(create[1], columnsOf(sql));
    return;
  }
  const rename = /ALTER\s+TABLE\s+["']?(\w+)["']?\s+RENAME\s+TO\s+["']?(\w+)["']?/i.exec(sql);
  if (rename) {
    const cols = schema.get(rename[1]);
    schema.delete(rename[1]);
    if (cols) schema.set(rename[2], cols);
    return;
  }
  const drop = /DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["']?(\w+)["']?/i.exec(sql);
  if (drop) schema.delete(drop[1]);
}

/**
 * Every way the given migrations break the rule. Empty means they obey it.
 *
 * Exported shape rather than assertions inline so the tests below can drive it with DELIBERATELY BAD
 * fixtures — a gate never shown to go red is not a property, it is a hope.
 */
export function migrationViolations(
  migrations: readonly { version: number; statements: readonly string[]; precondition?: unknown }[],
): string[] {
  const out: string[] = [];
  const schema: Schema = new Map();

  for (const m of migrations) {
    const before = new Map([...schema].map(([t, c]) => [t, c.map((x) => ({ ...x }))]));

    m.statements.forEach((sql, i) => {
      const drop = /DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["']?(\w+)["']?/i.exec(sql);
      if (drop) {
        const table = drop[1];
        // A table this migration created itself carries nothing; only a pre-existing one holds rows.
        const preExisting = before.has(table);
        const copied = m.statements.slice(0, i).some((earlier) =>
          new RegExp(
            `INSERT\\s+(OR\\s+\\w+\\s+)?INTO[\\s\\S]*SELECT[\\s\\S]*FROM\\s+["']?${table}["']?`,
            "i",
          )
            .test(earlier)
        );
        if (preExisting && !copied) {
          out.push(
            `v${m.version} drops \`${table}\` without copying from it first — a migration migrates its ` +
              `data, it does not drop it`,
          );
        }
      }
      applyToModel(schema, sql);
    });

    // A column that was nullable and is now NOT NULL cannot carry every row by construction, so the
    // migration must REFUSE rather than discard the rows that do not qualify.
    for (const [table, cols] of schema) {
      const was = before.get(table);
      if (!was) continue;
      for (const col of cols) {
        const previously = was.find((c) => c.name === col.name);
        if (previously && !previously.notNull && col.notNull && !m.precondition) {
          out.push(
            `v${m.version} narrows \`${table}.${col.name}\` to NOT NULL without a precondition — it ` +
              `would discard every row that does not already qualify`,
          );
        }
      }
    }
  }
  return out;
}

Deno.test("the migration list migrates its data", () => {
  assertEquals(migrationViolations(MIGRATIONS), []);
});

Deno.test("the gate catches a drop with no copy", () => {
  const bad = [
    { version: 1, statements: [`CREATE TABLE old (id TEXT PRIMARY KEY NOT NULL, v TEXT)`] },
    {
      version: 2,
      statements: [`CREATE TABLE new (id TEXT PRIMARY KEY NOT NULL, v TEXT)`, `DROP TABLE old`],
    },
  ];
  assertEquals(migrationViolations(bad), [
    "v2 drops `old` without copying from it first — a migration migrates its data, it does not drop it",
  ]);

  // The same migration WITH the copy passes — so the gate is keyed on the copy, not on the drop.
  bad[1].statements.splice(1, 0, `INSERT INTO new SELECT * FROM old`);
  assertEquals(migrationViolations(bad), []);
});

Deno.test("the gate catches a tightening with no precondition", () => {
  const bad: { version: number; statements: string[]; precondition?: unknown }[] = [
    { version: 1, statements: [`CREATE TABLE t (id TEXT PRIMARY KEY NOT NULL, k TEXT)`] },
    {
      version: 2,
      statements: [
        `CREATE TABLE t2 (id TEXT PRIMARY KEY NOT NULL, k TEXT NOT NULL)`,
        `INSERT INTO t2 SELECT * FROM t`,
        `DROP TABLE t`,
        `ALTER TABLE t2 RENAME TO t`,
      ],
    },
  ];
  assertEquals(migrationViolations(bad), [
    "v2 narrows `t.k` to NOT NULL without a precondition — it would discard every row that does not " +
    "already qualify",
  ]);

  // Declaring one satisfies the gate: the migration now refuses rather than discarding.
  bad[1].precondition = () => Promise.resolve();
  assertEquals(migrationViolations(bad), []);
});

Deno.test("dropping a table the same migration created is not a violation", () => {
  // A scratch table holds no rows anyone had; requiring a copy out of it would be noise, and noise is
  // how a gate earns a blanket suppression.
  assertEquals(
    migrationViolations([
      {
        version: 1,
        statements: [`CREATE TABLE scratch (id TEXT PRIMARY KEY NOT NULL)`, `DROP TABLE scratch`],
      },
    ]),
    [],
  );
});
