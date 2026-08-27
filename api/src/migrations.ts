// SCHEMA EVOLUTION (capability `database`): the ordered migration list, the version record, and the
// runner that applies what a store has not yet seen.
//
// WHY THIS EXISTS AT ALL. `db.ts`'s `SCHEMA` is every statement needed to build the schema FROM NOTHING,
// and every one of them is `CREATE … IF NOT EXISTS`. Run against a store that already holds tables that is
// a NO-OP THAT REPORTS SUCCESS — it cannot rename a table, add a column, or drop one. A deploy step built
// on it would change nothing and go green, which is the exact failure `api-deploy.yml`'s own header was
// written about ("the previous runtime stayed fail-closed for two weeks with CI green throughout").
//
// TWO FORMS, BOUND BY A TEST. `SCHEMA` is the CREATED shape — the readable statement of what the tables
// are today, and what `db.ts`'s statements are written against. `MIGRATIONS` is the ORDERED history — the
// only thing that can change a store holding rows. `migrations.test.ts` builds one store from each and
// asserts the two schemas are identical, so the pair cannot drift. This is the arrangement the DEVICE side
// already runs (`Ledger.sq` beside `1.sqm`…`6.sqm`); `6.sqm`'s own comment names the property: "the verify
// task compares migrated vs created schemas".
//
// THE RUNNER ALWAYS REPLAYS. There is no "is this store fresh?" branch. A fresh store applies v1 then v2
// and lands where `SCHEMA` would have put it; the live store finds v1's `IF NOT EXISTS` statements inert,
// records v1, and applies v2. One code path, so the behaviour that matters — what happens to the DEPLOYED
// store — is the behaviour every test exercises. A freshness heuristic is precisely the kind of guess that
// is wrong exactly once, on the store that matters.
//
// NOT IN THE DEPLOYED BUNDLE'S PATH. The Edge Script never migrates; `main.ts` does not reach this module.
// It is imported by the CI migrate step, the local rig, and the tests.

import type { Db } from "./db.ts";

/** One migration: an ordered list of statements applied as a unit, recorded by `version`. */
export type Migration = {
  version: number;
  /** Human name, for the runner's log. Not load-bearing; the `version` is the identity. */
  name: string;
  statements: readonly string[];
  /**
   * Checked before the statements run; THROWS to refuse the migration.
   *
   * Required of any migration that TIGHTENS a constraint (capability `database`, "A migration migrates its
   * data; it does not drop it"): a rebuild that narrows a column can only carry rows that already satisfy
   * the narrower shape, so it must refuse rather than discard the rest. `migrations.test.ts` gates this.
   */
  precondition?: (db: Db) => Promise<void>;
};

/**
 * The version record. `IF NOT EXISTS` because the store that most needs migrating — the deployed one —
 * predates this table entirely: it holds v1's tables with no record that it does.
 */
const VERSION_TABLE = `CREATE TABLE IF NOT EXISTS schema_migrations (
   version    INTEGER PRIMARY KEY NOT NULL,
   applied_at TEXT NOT NULL
 ) STRICT`;

/**
 * v1 — the five tables as the relational migration created them
 * (`changes/archive/2026-08-25-record-uploads-in-database`).
 *
 * Reproduced VERBATIM and left `IF NOT EXISTS`, which is what makes this safe to apply to the deployed
 * store: it already holds every one of them, so applying v1 there records a version and touches nothing.
 * Do not "modernise" these statements to match `SCHEMA` — v1 is history, and history is what the store
 * actually met. `SCHEMA` is where the current shape lives.
 */
const V1: Migration = {
  version: 1,
  name: "five tables",
  statements: [
    `CREATE TABLE IF NOT EXISTS events (
       id               TEXT PRIMARY KEY NOT NULL,
       name             TEXT NOT NULL,
       created_at       TEXT NOT NULL,
       starts_at        TEXT NOT NULL,
       ends_at          TEXT NOT NULL,
       capacity         INTEGER NOT NULL,
       lifetime_seconds INTEGER NOT NULL
     ) STRICT`,
    `CREATE TABLE IF NOT EXISTS memberships (
       event_id  TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
       device_id TEXT NOT NULL,
       state     TEXT NOT NULL,
       joined_at TEXT NOT NULL,
       PRIMARY KEY (event_id, device_id)
     ) STRICT`,
    `CREATE TABLE IF NOT EXISTS event_assets (
       event_id      TEXT NOT NULL,
       device_id     TEXT NOT NULL,
       asset_id      TEXT NOT NULL,
       creation_date TEXT NOT NULL,
       PRIMARY KEY (event_id, device_id, asset_id),
       FOREIGN KEY (event_id, device_id)
         REFERENCES memberships(event_id, device_id) ON DELETE CASCADE
     ) STRICT`,
    `CREATE TABLE IF NOT EXISTS resources (
       device_id    TEXT NOT NULL,
       key          TEXT NOT NULL,
       asset_id     TEXT NOT NULL,
       role         TEXT NOT NULL,
       content_type TEXT NOT NULL,
       filename     TEXT NOT NULL,
       uploaded     INTEGER NOT NULL DEFAULT 0,
       PRIMARY KEY (device_id, key)
     ) STRICT`,
    `CREATE INDEX IF NOT EXISTS resources_by_asset ON resources (device_id, asset_id)`,
    `CREATE TABLE IF NOT EXISTS device_records (
       device_id  TEXT PRIMARY KEY NOT NULL,
       push_kind  TEXT,
       push_token TEXT,
       push_env   TEXT,
       updated_at TEXT NOT NULL
     ) STRICT`,
  ],
};

/**
 * v2 — `device_records` becomes `devices`, carrying every row.
 *
 * A REBUILD, NOT AN ALTER, and that is forced: SQLite cannot `ADD COLUMN … NOT NULL` without a DEFAULT.
 * The `INSERT … SELECT` below is what makes it a migration rather than a deletion — the push registrations
 * it carries are live tokens for real devices, and losing them would silence every one of those phones
 * until it next launched.
 *
 * THE ATTESTATION COLUMNS ARE NULLABLE HERE, and only here. Their values live in the STORAGE ZONE, which
 * SQL cannot reach, so a `NOT NULL` column at this moment could only be satisfied by a fabricated
 * attestation — worse than an absent one. v3 tightens them once the data is in, and refuses to run until
 * it is.
 *
 * A NULL `attest_key` is therefore a real, legible state during the cutover: *this device has a row and a
 * push registration, but the backend holds no attestation for it*. Every reader already answers that
 * correctly — `readAttestation` returns null, so renewal `401`s and the device attests, which fills the
 * columns. The store heals itself even if the backfill never runs; the backfill's job is to save each
 * device the throttled Apple round-trip, not to make the system work.
 *
 * `created_at` seeds from `updated_at`: an UPPER BOUND on when the device was first seen, not a claim to
 * know it. `push_updated_at` seeds from the same column, where it is exact.
 */
const V2: Migration = {
  version: 2,
  name: "devices carries the attestation, preserving every row",
  statements: [
    `CREATE TABLE IF NOT EXISTS devices (
       device_id               TEXT PRIMARY KEY NOT NULL,
       created_at              TEXT NOT NULL,
       attest_key              TEXT,
       attest_env              TEXT,
       attested_at             TEXT,
       attest_token_expires_at TEXT,
       push_kind               TEXT,
       push_token              TEXT,
       push_env                TEXT,
       push_updated_at         TEXT
     ) STRICT`,
    `INSERT OR IGNORE INTO devices
       (device_id, created_at, push_kind, push_token, push_env, push_updated_at)
     SELECT device_id, updated_at, push_kind, push_token, push_env, updated_at
       FROM device_records`,
    `DROP TABLE IF EXISTS device_records`,
  ],
};

/**
 * v3 — the attestation columns become `NOT NULL`, restoring the invariant the schema should express: a
 * `devices` row exists IF AND ONLY IF that device has attested (capability `device-attestation`).
 *
 * ⚠️ THE PRECONDITION IS WHAT MAKES THIS SAFE, and it is not defensive decoration. This rebuild can only
 * carry rows that already have an attestation; run against the cutover state — every row carried by v2,
 * none of them attested yet — it would drop all of them. Refusing instead is fail-closed: v2 stays
 * applied, the deploy fails with the previous bundle live, and the message says what to run.
 *
 * With the precondition satisfied the `INSERT … SELECT` is total: every row qualifies, nothing is
 * dropped, and the tightening is pure. On a fresh store there are no rows and it passes trivially.
 */
const V3: Migration = {
  version: 3,
  name: "the attestation columns are NOT NULL",
  precondition: async (db) => {
    const { rows } = await db.execute(
      `SELECT COUNT(*) AS n FROM devices WHERE attest_key IS NULL`,
    );
    const pending = Number(rows[0].n);
    if (pending > 0) {
      throw new Error(
        `refusing to tighten: ${pending} device row(s) carry no attestation yet. Tightening now would ` +
          `drop them. Run the one-time attestation backfill (it fills these columns from the ` +
          `devices/<id>.attest.json objects), then re-run this deploy.`,
      );
    }
  },
  statements: [
    `CREATE TABLE devices_attested (
       device_id               TEXT PRIMARY KEY NOT NULL,
       created_at              TEXT NOT NULL,
       attest_key              TEXT NOT NULL,
       attest_env              TEXT NOT NULL,
       attested_at             TEXT NOT NULL,
       attest_token_expires_at TEXT NOT NULL,
       push_kind               TEXT,
       push_token              TEXT,
       push_env                TEXT,
       push_updated_at         TEXT
     ) STRICT`,
    `INSERT INTO devices_attested SELECT * FROM devices`,
    `DROP TABLE devices`,
    `ALTER TABLE devices_attested RENAME TO devices`,
  ],
};

/** Every migration, in the order they must be applied. Append only; never edit one that has shipped. */
export const MIGRATIONS: readonly Migration[] = [V1, V2, V3];

/**
 * Which migrations this store has not applied, in order. Empty when the schema is current.
 *
 * THE ONE COMPARISON. Both things that ask the question go through it: `migrate` below, deciding what to
 * run, and `scripts/migrate.ts --pending`, deciding whether `api-deploy.yml` opens a maintenance window
 * at all (capability `backend-deployment`). A second implementation could disagree with this one, and the
 * disagreement that matters is silent — a deploy that believed nothing was pending would publish the new
 * bundle onto an un-migrated store, which is precisely what the window exists to prevent.
 *
 * Creating the version record is the only write it makes, and it is the same one `migrate` makes a moment
 * later. Reading a store that predates the table would otherwise throw rather than answer "all of them".
 */
export async function pendingMigrations(db: Db): Promise<readonly Migration[]> {
  await db.execute(VERSION_TABLE);
  const { rows } = await db.execute(`SELECT version FROM schema_migrations`);
  const applied = new Set(rows.map((r) => Number(r.version)));
  return MIGRATIONS.filter((m) => !applied.has(m.version));
}

/**
 * Apply every migration this store has not seen, in order, recording each.
 *
 * Idempotent: applying the list to an already-migrated store runs no statement. Each migration's
 * statements plus its version record go through `batch`, which is atomic — so a migration is either
 * applied and recorded, or neither. A half-applied migration that reported success would leave the store
 * in a shape no version describes, which is the one state this runner must never produce.
 */
export async function migrate(db: Db, log: (msg: string) => void = () => {}): Promise<void> {
  for (const m of await pendingMigrations(db)) {
    // Refused BEFORE anything is written, so a migration that cannot carry its data leaves the store on
    // the previous version rather than half-applied.
    if (m.precondition) await m.precondition(db);
    await db.batch([
      ...m.statements.map((sql) => ({ sql })),
      {
        sql: `INSERT INTO schema_migrations (version, applied_at) VALUES (?, datetime('now'))`,
        args: [m.version],
      },
    ]);
    log(`applied migration v${m.version} (${m.name})`);
  }
}

/** The versions this store has applied, ascending. Used by the one-time program and by tests. */
export async function appliedVersions(db: Db): Promise<number[]> {
  await db.execute(VERSION_TABLE);
  const { rows } = await db.execute(`SELECT version FROM schema_migrations ORDER BY version`);
  return rows.map((r) => Number(r.version));
}
