// THE SCHEMA GATES (capability `database`).
//
// Two things live here. First, THE VERIFY PROPERTY: the committed `api/schema.sql` is exactly what
// replaying `api/migrations/*.sql` produces. That pair used to be two HAND-WRITTEN forms kept honest by a
// test; the created form is now generated from the ordered one, so they cannot disagree — this asserts the
// committed copy is current, which is the only way they now can.
//
// Second, THE AUTHORING GATES, which catch the mistakes that are invisible in a migration's diff:
//
//   ① a `DROP TABLE` with no copy out of it first — a schema change that behaves as a deletion;
//   ② a column narrowed to NOT NULL with no precondition — a rebuild that silently discards the rows
//     that do not qualify;
//   ③ `INSERT … SELECT *` — which maps BY POSITION, so a rebuild that reorders columns writes every
//     value into its neighbour's column, carrying every row and corrupting all of them.
//
// ⚠️ WHAT THESE CANNOT CATCH, stated so they are not over-trusted. ① establishes that a copy is PRESENT,
// never that it is COMPLETE — a copy naming the wrong columns, or narrowed by a `WHERE`, passes. And none
// of them sees an object that vanished with a rebuilt table (an index, a table option): a rebuild drops
// those silently, and the only thing that shows it is the deletion in `schema.sql`'s diff. Mechanical
// detection of THAT was deliberately deferred — see the change's design record for what triggers it.
//
// ── THE CANONICAL PRECONDITION GUARD ──────────────────────────────────────────────────────────────
//
// Gate ② asks for a refusal that runs against the DEPLOYED store's rows, which means it has to be SQL in
// the migration file — nothing in TypeScript executes where the rows are. Copy this, substituting the
// migration's number, and put it BEFORE the rebuild it guards:
//
//   CREATE TEMP TABLE _pre_0007 (offending INTEGER);
//   CREATE TEMP TRIGGER _pre_0007_guard BEFORE INSERT ON _pre_0007 WHEN NEW.offending > 0
//   BEGIN
//     SELECT RAISE(ABORT, 'refusing to tighten devices.foo: rows still hold NULL. List them with
//       SELECT device_id FROM devices WHERE foo IS NULL, fill them, then re-run this deploy.');
//   END;
//   INSERT INTO _pre_0007 SELECT COUNT(*) FROM devices WHERE foo IS NULL;
//   DROP TRIGGER _pre_0007_guard;
//   DROP TABLE _pre_0007;
//
// Three things about it are not stylistic.
//
// ⚠️ THE NAMES CARRY THE MIGRATION NUMBER. An abort leaves the temp table and trigger behind — its own
// `DROP`s never run — so a guard reusing a fixed name collides with its own leftovers on the retry and
// fails with "table already exists" instead of the message it was written to deliver.
//
// ⚠️ THE MESSAGE NAMES A QUERY, NOT A COUNT. `RAISE(ABORT, …)` takes a string LITERAL — SQL cannot
// interpolate the number of offending rows into it. `database` requires a refusal to name what would
// satisfy it, so the message hands the operator the query that lists them instead.
//
// ⚠️ IT MUST PRECEDE THE REBUILD. Placed after, it inspects the store the migration already produced and
// passes trivially.
//
// THE SCHEMA MODEL IS REAL SQLITE, not a regex. The gate replays the files into an in-memory store and
// reads `pragma_table_info` between them, so what it believes about a column is what SQLite built. Only
// the ORDERING questions — did a copy precede this drop, is this `SELECT *` inside an INSERT — are
// answered from the file text, by string index, because they are questions about the file rather than
// about the schema.

import { assertEquals, assertStringIncludes } from "@std/assert";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { discoverMigrations, replay } from "../src/dev/replay.ts";
import { generateSchema, SCHEMA_PATH } from "../src/scripts/generate-schema.ts";

// ── The verify property ───────────────────────────────────────────────────────────────────────────

Deno.test("the committed schema snapshot is what replaying the migrations produces", async () => {
  assertEquals(
    await Deno.readTextFile(SCHEMA_PATH),
    await generateSchema(),
    "api/schema.sql is stale — run `deno task schema` in api/ and commit it",
  );
});

Deno.test("replaying the migrations builds every table the snapshot declares", async () => {
  const db = sqliteDb(":memory:");
  await replay(db);
  const { rows } = await db.execute(
    `SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'
       AND name <> '__bunny_migrations' ORDER BY name`,
  );
  const snapshot = await Deno.readTextFile(SCHEMA_PATH);
  for (const name of rows.map((r) => String(r.name))) {
    assertStringIncludes(snapshot, `CREATE TABLE ${name} (`);
  }
  db.close();
});

// ── The authoring gates ───────────────────────────────────────────────────────────────────────────

/** A migration as the gate reads it: a name to blame, and the text to inspect. */
type Candidate = { name: string; sql: string };

/** Which columns each table has, and whether each is NOT NULL — read from SQLite, not parsed. */
type Model = Map<string, Map<string, boolean>>;

async function modelOf(db: ReturnType<typeof sqliteDb>): Promise<Model> {
  const model: Model = new Map();
  const { rows } = await db.execute(
    `SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'
       AND name <> '__bunny_migrations'`,
  );
  for (const t of rows.map((r) => String(r.name))) {
    const cols = await db.execute(`SELECT name, "notnull" AS nn FROM pragma_table_info(?)`, [t]);
    model.set(t, new Map(cols.rows.map((c) => [String(c.name), Number(c.nn) === 1])));
  }
  return model;
}

/**
 * The file with comments and string literals blanked out, character-for-character.
 *
 * WHY THIS EXISTS. The scans below are index-based, and SQL that only MENTIONS a keyword would otherwise
 * be read as SQL that DOES it. That is not hypothetical: the canonical precondition guard raises a
 * message telling the operator to run `SELECT * FROM …`, and its trigger is declared `BEFORE INSERT` — so
 * the recommended idiom tripped the `SELECT *` rule the first time this ran.
 *
 * Length is preserved exactly, so every index into the mask means the same position in the original. The
 * original is what gets executed; only the scanning reads this. It is a lexer for quotes and comments,
 * NOT a SQL parser — it knows nothing about schemas, and the worst it can do is make a text rule slightly
 * over- or under-eager.
 */
function mask(sql: string): string {
  const out = sql.split("");
  const blank = (i: number) => {
    if (sql[i] !== "\n") out[i] = " ";
  };
  let i = 0;
  while (i < sql.length) {
    if (sql[i] === "-" && sql[i + 1] === "-") {
      while (i < sql.length && sql[i] !== "\n") blank(i++);
    } else if (sql[i] === "/" && sql[i + 1] === "*") {
      blank(i++), blank(i++);
      while (i < sql.length && !(sql[i] === "*" && sql[i + 1] === "/")) blank(i++);
      if (i < sql.length) blank(i++), blank(i++);
    } else if (sql[i] === "'") {
      blank(i++);
      while (i < sql.length) {
        if (sql[i] === "'") {
          // A doubled quote is an escaped one: still inside the literal.
          if (sql[i + 1] === "'") {
            blank(i++), blank(i++);
            continue;
          }
          blank(i++);
          break;
        }
        blank(i++);
      }
    } else i++;
  }
  return out.join("");
}

/** Does an `INSERT … SELECT … FROM <table>` appear in this span of text? */
function copiesFrom(text: string, table: string): boolean {
  return new RegExp(
    `INSERT\\s+(?:OR\\s+\\w+\\s+)?INTO[\\s\\S]*?SELECT[\\s\\S]*?FROM\\s+["']?${table}["']?\\b`,
    "i",
  ).test(text);
}

/** The statement a match sits in, taken as the text back to the previous `;`. */
function statementBefore(sql: string, index: number): string {
  return sql.slice(sql.lastIndexOf(";", index) + 1, index);
}

/**
 * Every way the given migrations break the rules. Empty means they obey them.
 *
 * Exported shape rather than assertions inline so the tests below can drive it with DELIBERATELY BAD
 * fixtures — a gate never shown to go red is not a property, it is a hope.
 */
export async function migrationViolations(files: readonly Candidate[]): Promise<string[]> {
  const out: string[] = [];
  const db = sqliteDb(":memory:");
  // Enforcement off, exactly as both real runners apply migrations — otherwise a rebuild here would
  // cascade and the model would describe a store neither runner produces.
  db.exec("PRAGMA foreign_keys = off");

  try {
    for (const f of files) {
      const before = await modelOf(db);
      // Every scan below runs on the MASK, never on the raw file: mentioning a keyword in prose or in a
      // message string must not read as doing it. Indices are shared, so `f.sql` is what executes.
      const scan = mask(f.sql);

      // ① A drop must be preceded, IN THIS FILE, by a copy out of the table. Located by string index so
      //    "preceded" means what it says, without splitting the file into statements.
      for (const m of scan.matchAll(/DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["']?(\w+)["']?/gi)) {
        const table = m[1];
        // A table this migration created itself carries nothing; only a pre-existing one holds rows.
        if (!before.has(table)) continue;
        if (!copiesFrom(scan.slice(0, m.index), table)) {
          out.push(
            `${f.name} drops \`${table}\` without copying from it first — a migration migrates its ` +
              `data, it does not drop it`,
          );
        }
      }

      // ③ `SELECT *` inside an INSERT. Position-mapped, so a reordering rebuild transposes every value
      //    while carrying every row — the only failure here that produces WRONG data, not missing data.
      for (const m of scan.matchAll(/SELECT\s+\*/gi)) {
        if (/\bINSERT\s+(?:OR\s+\w+\s+)?INTO\b/i.test(statementBefore(scan, m.index))) {
          out.push(
            `${f.name} copies rows with \`INSERT … SELECT *\`, which maps by POSITION — name the ` +
              `columns on both sides so a reordering rebuild cannot transpose them`,
          );
        }
      }

      db.exec(f.sql);
      const after = await modelOf(db);

      // ② A column that was nullable and is now NOT NULL cannot carry every row by construction, so the
      //    migration must REFUSE rather than discard the rows that do not qualify. The refusal lives in
      //    the file, as SQL that aborts, because that is the only thing that runs against real rows.
      const declaresPrecondition = /RAISE\s*\(\s*ABORT/i.test(scan);
      for (const [table, cols] of after) {
        const was = before.get(table);
        if (!was) continue;
        for (const [col, notNull] of cols) {
          if (notNull && was.get(col) === false && !declaresPrecondition) {
            out.push(
              `${f.name} narrows \`${table}.${col}\` to NOT NULL without a precondition — it would ` +
                `discard every row that does not already qualify. Add the canonical RAISE(ABORT) guard ` +
                `(see the header of this file) before the rebuild.`,
            );
          }
        }
      }
    }
  } finally {
    db.exec("PRAGMA foreign_keys = on");
    db.close();
  }
  return out;
}

Deno.test("the repository's migrations obey the gates", async () => {
  assertEquals(await migrationViolations(discoverMigrations()), []);
});

Deno.test("the gate catches a drop with no copy", async () => {
  const bad: Candidate[] = [
    { name: "0001_a.sql", sql: `CREATE TABLE old (id TEXT PRIMARY KEY NOT NULL, v TEXT) STRICT;` },
    {
      name: "0002_b.sql",
      sql: `CREATE TABLE new (id TEXT PRIMARY KEY NOT NULL, v TEXT) STRICT;
            DROP TABLE old;`,
    },
  ];
  assertEquals(await migrationViolations(bad), [
    "0002_b.sql drops `old` without copying from it first — a migration migrates its data, it does not " +
    "drop it",
  ]);

  // The same migration WITH the copy passes — so the gate is keyed on the copy, not on the drop.
  bad[1].sql = `CREATE TABLE new (id TEXT PRIMARY KEY NOT NULL, v TEXT) STRICT;
                INSERT INTO new (id, v) SELECT id, v FROM old;
                DROP TABLE old;`;
  assertEquals(await migrationViolations(bad), []);
});

Deno.test("the gate catches a tightening with no precondition", async () => {
  const bad: Candidate[] = [
    { name: "0001_a.sql", sql: `CREATE TABLE t (id TEXT PRIMARY KEY NOT NULL, k TEXT) STRICT;` },
    {
      name: "0002_b.sql",
      sql: `CREATE TABLE t2 (id TEXT PRIMARY KEY NOT NULL, k TEXT NOT NULL) STRICT;
            INSERT INTO t2 (id, k) SELECT id, k FROM t;
            DROP TABLE t;
            ALTER TABLE t2 RENAME TO t;`,
    },
  ];
  assertEquals(await migrationViolations(bad), [
    "0002_b.sql narrows `t.k` to NOT NULL without a precondition — it would discard every row that does " +
    "not already qualify. Add the canonical RAISE(ABORT) guard (see the header of this file) before the " +
    "rebuild.",
  ]);

  // The same migration WITH an aborting guard passes.
  bad[1].sql = `CREATE TEMP TABLE _pre_0002 (offending INTEGER);
                CREATE TEMP TRIGGER _pre_0002_guard BEFORE INSERT ON _pre_0002 WHEN NEW.offending > 0
                BEGIN
                  SELECT RAISE(ABORT, 'refusing to tighten t.k: rows hold NULL. List them with
                    SELECT * FROM t WHERE k IS NULL, fill them, then re-run.');
                END;
                INSERT INTO _pre_0002 SELECT COUNT(*) FROM t WHERE k IS NULL;
                DROP TRIGGER _pre_0002_guard;
                DROP TABLE _pre_0002;
                CREATE TABLE t2 (id TEXT PRIMARY KEY NOT NULL, k TEXT NOT NULL) STRICT;
                INSERT INTO t2 (id, k) SELECT id, k FROM t;
                DROP TABLE t;
                ALTER TABLE t2 RENAME TO t;`;
  assertEquals(await migrationViolations(bad), []);
});

Deno.test("the gate catches `INSERT … SELECT *`", async () => {
  const bad: Candidate[] = [
    { name: "0001_a.sql", sql: `CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL) STRICT;` },
    {
      name: "0002_b.sql",
      sql: `CREATE TABLE t2 (b TEXT NOT NULL, a TEXT NOT NULL) STRICT;
            INSERT INTO t2 SELECT * FROM t;
            DROP TABLE t;
            ALTER TABLE t2 RENAME TO t;`,
    },
  ];
  assertStringIncludes((await migrationViolations(bad))[0], "maps by POSITION");

  // A bare `SELECT *` that is not copying rows is not the hazard and is left alone.
  assertEquals(
    await migrationViolations([
      { name: "0001_a.sql", sql: `CREATE TABLE t (a TEXT NOT NULL) STRICT;` },
      { name: "0002_b.sql", sql: `CREATE VIEW v AS SELECT * FROM t;` },
    ]),
    [],
  );
});

// ── The transposition the `SELECT *` rule exists to prevent ───────────────────────────────────────

Deno.test("a reordering rebuild that names its columns carries values into the right columns", async () => {
  const db = sqliteDb(":memory:");
  db.exec(`CREATE TABLE d (device_id TEXT NOT NULL, attest_key TEXT NOT NULL,
                           attest_env TEXT NOT NULL) STRICT`);
  await db.execute(`INSERT INTO d VALUES ('dev-1', 'PUBKEY-abc', 'production')`);

  db.exec("PRAGMA foreign_keys = off");
  // The columns are deliberately reordered, which is exactly what makes `SELECT *` transpose.
  db.exec(`CREATE TABLE d_new (device_id TEXT NOT NULL, attest_env TEXT NOT NULL,
                               attest_key TEXT NOT NULL) STRICT;
           INSERT INTO d_new (device_id, attest_env, attest_key)
                SELECT device_id, attest_env, attest_key FROM d;
           DROP TABLE d;
           ALTER TABLE d_new RENAME TO d;`);
  db.exec("PRAGMA foreign_keys = on");

  const { rows } = await db.execute(`SELECT attest_key, attest_env FROM d`);
  assertEquals(rows[0].attest_key, "PUBKEY-abc");
  assertEquals(rows[0].attest_env, "production");
  db.close();
});
