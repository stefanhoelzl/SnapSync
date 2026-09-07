// THE IN-REPOSITORY MIGRATION RUNNER (capability `database`): applies `api/migrations/*.sql` to a local
// SQLite store — the dev rig, every test fixture, and the schema generator.
//
// WHY THIS EXISTS ALONGSIDE THE PLATFORM RUNNER. `bunny db migrations apply` is what migrates the
// DEPLOYED store, and it cannot be used here: measured, it refuses any URL that is not `libsql://`,
// `https://` or `wss://` ("Database URL must use an encrypted connection"), so it cannot address a local
// file or `:memory:` at all. There is no arrangement in which one runner serves both.
//
// SO THERE ARE TWO RUNNERS OVER ONE SOURCE OF TRUTH. The `.sql` files are the truth; both runners read
// them, in the same order, and record the same thing in the same table. Everything below that could
// differ between the two is called out where it happens, because a divergence here does not produce a
// different ERROR — it produces a different STORE.
//
// IT IS DEV INFRASTRUCTURE. `main.ts` never reaches it, and `deno bundle` roots the deployed bundle at
// `main.ts`, so none of this ships.

import { createHash } from "node:crypto";
import { fromFileUrl } from "@std/path";

/** Where the migration files live, resolved from THIS module rather than the cwd. */
export const MIGRATIONS_DIR: string = fromFileUrl(new URL("../../migrations", import.meta.url));

/**
 * The platform runner's own bookkeeping table, reproduced EXACTLY.
 *
 * Read out of the shipped CLI binary rather than guessed. It is deliberately not `STRICT` and does use
 * `AUTOINCREMENT` — this is not our table to design, and a store migrated by one runner must be readable
 * by the other. `migrations.test.ts` and the deploy-time schema assertion both exclude it, because it is
 * the runner's state and not part of the schema either form describes.
 */
const TRACKING_TABLE = `CREATE TABLE IF NOT EXISTS "__bunny_migrations" (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      name       TEXT NOT NULL UNIQUE,
      checksum   TEXT NOT NULL,
      applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )`;

/** A migration file: its identity, its contents, and the checksum recorded against it. */
export type MigrationFile = {
  /** The file name, which IS the migration's identity. Ordering is a sort on this. */
  name: string;
  path: string;
  sql: string;
  checksum: string;
};

/**
 * The checksum the platform runner records — line endings normalised, surrounding whitespace trimmed,
 * SHA-256, hex.
 *
 * Reproduced exactly so a store this runner has migrated is one the platform runner reads as `applied`
 * rather than `modified`. Change this and the two runners stop agreeing about history.
 */
export function checksumOf(contents: string): string {
  return createHash("sha256").update(contents.replace(/\r\n/g, "\n").trim()).digest("hex");
}

/**
 * Every migration file, in apply order.
 *
 * ⚠️ The order is a LEXICOGRAPHIC sort on the file name, not a numeric one — that is what the platform
 * runner does, and the two must agree. The `NNNN_` prefix is zero-padded precisely so the two orderings
 * coincide; a file named `10_x.sql` would sort before `9_x.sql` and the runners would still agree with
 * each other, but not with the author's intent. Keep the padding.
 */
export function discoverMigrations(dir: string = MIGRATIONS_DIR): MigrationFile[] {
  const files: MigrationFile[] = [];
  for (const entry of Deno.readDirSync(dir)) {
    if (!entry.isFile || !entry.name.endsWith(".sql")) continue;
    const path = `${dir}/${entry.name}`;
    const sql = Deno.readTextFileSync(path);
    files.push({ name: entry.name, path, sql, checksum: checksumOf(sql) });
  }
  return files.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

/** The minimum surface the replayer needs: raw multi-statement SQL, and a parameterised write. */
type Handle = {
  exec(sql: string): void;
  execute(sql: string, args?: unknown[]): Promise<{ rows: Record<string, unknown>[] }>;
};

/** What a store has already applied, by file name, with the checksum it was applied under. */
async function appliedFiles(db: Handle): Promise<Map<string, string>> {
  db.exec(TRACKING_TABLE);
  const { rows } = await db.execute(`SELECT name, checksum FROM __bunny_migrations ORDER BY id`);
  return new Map(rows.map((r) => [String(r.name), String(r.checksum)]));
}

/**
 * Apply every migration this store has not seen, in order. Returns the file names applied.
 *
 * ⚠️ FOREIGN KEYS ARE DISABLED AROUND EACH FILE, AND THAT IS LOAD-BEARING (capability `database`).
 * SQLite performs an implicit `DELETE FROM` when a table is dropped, which FIRES `ON DELETE CASCADE` —
 * so rebuilding a table that others reference deletes their rows while reporting success. Measured on
 * this exact schema shape: with enforcement on, rebuilding `memberships` left `event_assets` empty.
 *
 * ⚠️ AND IT MUST BE `foreign_keys=off`, NEVER `defer_foreign_keys`. Deferral postpones the CHECK, not the
 * cascading ACTION: the same measurement with `defer_foreign_keys=ON` also emptied the child table. This
 * is the single most important line in this file to not "simplify".
 *
 * ⚠️ AND THE PRAGMA MUST BE OUTSIDE THE TRANSACTION. SQLite ignores `PRAGMA foreign_keys` while a
 * transaction is open — silently, reporting success — so issuing it as the first statement inside the
 * BEGIN would change nothing and look correct. This ordering is the same one the platform runner uses
 * (it brackets its wire batch identically), which is why the two produce the same store.
 *
 * Each file plus its tracking row is one transaction, so a migration either lands and is recorded or
 * neither happens.
 */
export async function replay(db: Handle, dir: string = MIGRATIONS_DIR): Promise<string[]> {
  const applied = await appliedFiles(db);
  const pending: MigrationFile[] = [];

  for (const m of discoverMigrations(dir)) {
    const recorded = applied.get(m.name);
    if (recorded === undefined) {
      pending.push(m);
      continue;
    }
    // Drift. The platform runner refuses this too; refusing here as well means a local store cannot
    // quietly disagree with what the deployed one was given.
    if (recorded !== m.checksum) {
      throw new Error(
        `migration ${m.name} was modified after it was applied to this store. A migration is frozen ` +
          `once applied — the deployed store recorded the old bytes. If this is a local store you are ` +
          `iterating on, delete it and let it rebuild; if it is not, revert the file.`,
      );
    }
  }
  if (pending.length === 0) return [];

  db.exec("PRAGMA foreign_keys = off");
  try {
    for (const m of pending) {
      db.exec("BEGIN");
      try {
        db.exec(m.sql);
        await db.execute(`INSERT INTO __bunny_migrations (name, checksum) VALUES (?, ?)`, [
          m.name,
          m.checksum,
        ]);
        db.exec("COMMIT");
      } catch (e) {
        db.exec("ROLLBACK");
        throw new Error(`migration ${m.name} failed: ${e instanceof Error ? e.message : e}`);
      }
    }
  } finally {
    // Restored even on failure: a store left with enforcement off would silently accept referential
    // corruption for the rest of the process's life.
    db.exec("PRAGMA foreign_keys = on");
  }
  return pending.map((m) => m.name);
}
