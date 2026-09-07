// GENERATE THE SCHEMA SNAPSHOT (capability `database`): replay every migration into an empty store, dump
// what SQLite says the schema is, and write it to `api/schema.sql`.
//
// WHY GENERATED RATHER THAN WRITTEN. This file replaces a hand-maintained `SCHEMA` constant that stated
// the same shape a second time. Two hand-written forms can disagree, and the only thing standing between
// them was a test asserting they did not; deriving one from the other removes the possibility instead of
// policing it. It also needs no SQL parser — SQLite does the parsing, and `sqlite_master` reports what it
// actually built, comments and all.
//
// WHAT THE SNAPSHOT IS FOR. Two readers:
//   1. a HUMAN, reviewing a migration — the diff shows what the migration did to the schema, including
//      the things a migration file does not mention (an index that went away with a rebuilt table);
//   2. the DEPLOY, which asserts the live store matches it (capability `backend-deployment`).
//
// ⚠️ FRESHNESS IS NOT CORRECTNESS. `--check` proves the committed file is what replaying produces. It
// does NOT prove the migrations were right: an author who drops an index, regenerates and commits gets a
// green check and a smaller file. That loss is visible in the diff, deliberately as a review signal —
// see the change's design record for the mechanical guards that were deferred and what triggers them.
//
// Out of the bundle: `main.ts` never reaches it.

import { sqliteDb } from "../dev/db-sqlite.ts";
import { replay } from "../dev/replay.ts";
import { fromFileUrl } from "@std/path";

/** Where the committed snapshot lives, resolved from THIS module rather than the cwd. */
export const SCHEMA_PATH: string = fromFileUrl(new URL("../../schema.sql", import.meta.url));

const HEADER =
  `-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- THE SCHEMA, AS THE MIGRATIONS BUILD IT (capability \`database\`) — GENERATED, DO NOT EDIT.
--
-- Regenerate with:  deno task schema
-- CI fails when this file is not what replaying \`api/migrations/*.sql\` produces.
--
-- ⚠️ NOT a migration. It is \`sqlite_master\` verbatim, ordered by object NAME so that a migration
-- produces the smallest possible diff — which means a table may appear before the table it references.
-- Nothing applies this file; to build a store, replay the migrations.
--
-- Do NOT confuse it with \`api/migrations/0001_baseline.sql\`, which is a FROZEN INPUT: that file is
-- checksummed and never changes, this one moves with every migration. On the day they were introduced
-- the two were near-identical text, which is why both say which they are.
--
-- ⚠️ A DELETION IN THIS FILE'S DIFF IS THE POINT. A rebuild drops the table's indexes and its table
-- options with it, and a migration that forgets to restore them says nothing about it — the absence IS
-- the bug, and it shows up here as removed lines and nowhere else. Read the deletions.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
`;

/** The schema a fresh replay produces, as the text this file commits. */
export async function generateSchema(): Promise<string> {
  const db = sqliteDb(":memory:");
  try {
    await replay(db);
    const { rows } = await db.execute(
      `SELECT sql FROM sqlite_master
        WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name <> '__bunny_migrations'
        ORDER BY name`,
    );
    return `${HEADER}\n${rows.map((r) => `${String(r.sql).trim()};`).join("\n\n")}\n`;
  } finally {
    db.close();
  }
}

if (import.meta.main) {
  const generated = await generateSchema();

  if (Deno.args.includes("--check")) {
    const committed = await Deno.readTextFile(SCHEMA_PATH).catch(() => null);
    if (committed === generated) {
      console.log("SCHEMA SNAPSHOT: current");
      Deno.exit(0);
    }
    console.error(
      committed === null
        ? `::error::api/schema.sql is missing. Run 'deno task schema' in api/ and commit it.`
        : `::error::api/schema.sql is stale — it is not what replaying api/migrations/*.sql produces. ` +
          `Run 'deno task schema' in api/ and commit the result. If the diff shows something REMOVED ` +
          `that you did not mean to remove, that is the migration dropping it, not this file being wrong.`,
    );
    Deno.exit(1);
  }

  await Deno.writeTextFile(SCHEMA_PATH, generated);
  console.log(`wrote ${SCHEMA_PATH}`);
}
