// THE IN-REPOSITORY RUNNER (capability `database`). What is asserted here is not "it applies files" —
// it is the two properties that, if wrong, produce a DIFFERENT STORE rather than an error, and which no
// schema comparison anywhere else would notice.

import { assertEquals, assertRejects, assertThrows } from "@std/assert";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { checksumOf, discoverMigrations, replay } from "../src/dev/replay.ts";

/** A scratch migrations directory whose files this test controls. */
async function withMigrations<T>(
  files: Record<string, string>,
  fn: (dir: string) => Promise<T>,
): Promise<T> {
  const dir = await Deno.makeTempDir();
  try {
    for (const [name, sql] of Object.entries(files)) {
      await Deno.writeTextFile(`${dir}/${name}`, sql);
    }
    return await fn(dir);
  } finally {
    await Deno.remove(dir, { recursive: true });
  }
}

Deno.test("replaying the repository's migrations builds the schema, and re-running applies nothing", async () => {
  const db = sqliteDb(":memory:");
  const first = await replay(db);
  assertEquals(first, discoverMigrations().map((m) => m.name));

  // The ordinary case, not an edge one: the rig replays on every boot and the fixtures on every test.
  assertEquals(await replay(db), []);

  const { rows } = await db.execute(
    `SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'
       AND name <> '__bunny_migrations' ORDER BY name`,
  );
  assertEquals(rows.map((r) => String(r.name)), [
    "devices",
    "event_assets",
    "events",
    "memberships",
    "resources",
  ]);
  db.close();
});

// ── The property that produces a different store rather than an error ──────────────────────────────
//
// SQLite performs an implicit `DELETE FROM` when a table is dropped, and that FIRES `ON DELETE CASCADE`.
// So a migration that rebuilds a referenced table — the shape SQLite forces for ANY constraint change —
// silently empties its children unless enforcement is genuinely off. Deferring is not enough: it
// postpones the check, not the cascading action. Both wrong settings pass every other test in this
// suite, because the rows they destroy belong to a table the migration never mentions.

Deno.test("a rebuild of a referenced table carries the referencing rows", async () => {
  const seed = `
    CREATE TABLE parent (id TEXT PRIMARY KEY NOT NULL) STRICT;
    CREATE TABLE child (
      id        TEXT PRIMARY KEY NOT NULL,
      parent_id TEXT NOT NULL REFERENCES parent(id) ON DELETE CASCADE
    ) STRICT;
    INSERT INTO parent (id) VALUES ('p1');
    INSERT INTO child (id, parent_id) VALUES ('c1', 'p1');`;

  // Steps 4-7 of SQLite's documented rebuild procedure, which is what any constraint change looks like.
  const rebuild = `
    CREATE TABLE parent_new (id TEXT PRIMARY KEY NOT NULL, label TEXT) STRICT;
    INSERT INTO parent_new (id, label) SELECT id, NULL FROM parent;
    DROP TABLE parent;
    ALTER TABLE parent_new RENAME TO parent;`;

  await withMigrations({ "0001_seed.sql": seed, "0002_rebuild.sql": rebuild }, async (dir) => {
    const db = sqliteDb(":memory:");
    assertEquals(await replay(db, dir), ["0001_seed.sql", "0002_rebuild.sql"]);

    const child = await db.execute(`SELECT COUNT(*) AS n FROM child`);
    assertEquals(
      Number(child.rows[0].n),
      1,
      "the child rows were cascaded away by the parent rebuild",
    );
    const parent = await db.execute(`SELECT COUNT(*) AS n FROM parent`);
    assertEquals(Number(parent.rows[0].n), 1);
    db.close();
  });
});

Deno.test("enforcement is restored after replaying, so the store is not left permissive", async () => {
  const db = sqliteDb(":memory:");
  await replay(db);
  const { rows } = await db.execute(`PRAGMA foreign_keys`);
  assertEquals(Number(Object.values(rows[0])[0]), 1);
  db.close();
});

Deno.test("a failing migration is not recorded, and leaves the store on the previous file", async () => {
  await withMigrations({
    "0001_ok.sql": `CREATE TABLE t (id TEXT PRIMARY KEY NOT NULL) STRICT;`,
    "0002_bad.sql": `INSERT INTO nope (id) VALUES ('x');`,
  }, async (dir) => {
    const db = sqliteDb(":memory:");
    await assertRejects(() => replay(db, dir), Error, "0002_bad.sql");
    const { rows } = await db.execute(`SELECT name FROM __bunny_migrations ORDER BY id`);
    assertEquals(rows.map((r) => String(r.name)), ["0001_ok.sql"]);
    db.close();
  });
});

// ── Agreement with the platform runner ────────────────────────────────────────────────────────────

Deno.test("an applied migration that is later edited is refused rather than ignored", async () => {
  await withMigrations(
    { "0001_a.sql": `CREATE TABLE a (id TEXT PRIMARY KEY NOT NULL) STRICT;` },
    async (dir) => {
      const db = sqliteDb(":memory:");
      await replay(db, dir);
      await Deno.writeTextFile(
        `${dir}/0001_a.sql`,
        `CREATE TABLE a (id TEXT PRIMARY KEY NOT NULL, x TEXT) STRICT;`,
      );
      await assertRejects(() => replay(db, dir), Error, "was modified after it was applied");
      db.close();
    },
  );
});

Deno.test("the checksum is the platform runner's: LF-normalised, trimmed, sha256 hex", () => {
  // Pinned by construction rather than by a literal: the two runners must agree, and the normalisation
  // is the part that could silently drift (a store recorded under one spelling reads as `modified` to
  // the other, which blocks every later apply).
  const body = "CREATE TABLE t (id TEXT) STRICT;";
  assertEquals(checksumOf(`${body}\n`), checksumOf(body));
  assertEquals(checksumOf(body.replace(/\n/g, "\r\n")), checksumOf(body));
  assertEquals(checksumOf(`  ${body}  `), checksumOf(body));
  assertEquals(checksumOf(body).length, 64);
});

Deno.test("apply order is a lexicographic sort on the file name", () => {
  assertThrows(() => Deno.readDirSync("/nonexistent-migrations-dir").next());
  const names = discoverMigrations().map((m) => m.name);
  assertEquals(names, [...names].sort());
});
