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
 * v2 — `device_records` becomes `devices`, carrying the attestation the object store used to hold.
 *
 * A REBUILD, NOT AN ALTER, and that is forced: SQLite cannot `ADD COLUMN … NOT NULL` without a DEFAULT,
 * and the attestation columns must be `NOT NULL` (a row exists only where a device has attested —
 * capability `device-attestation`). Any default would be a fabricated attestation, which is worse than no
 * row at all.
 *
 * THIS MIGRATION CARRIES NO ROWS ACROSS, and that is deliberate rather than an omission. The attested key
 * lives in the STORAGE ZONE, which SQL cannot reach — so the rows are inserted by the one-time program in
 * `scripts/migrate-attest.ts`, which reads both halves, applies this migration between them, and joins.
 * Running this migration WITHOUT that program is survivable, not catastrophic: every device re-attests
 * once (recreating its row) and re-registers its push token at its next launch, because iOS redelivers the
 * APNs token on every launch. That is the failure mode if a deploy ever races ahead of the program — one
 * Apple attestation per device, no photo affected, no operator action.
 *
 * `created_at` means FIRST ATTESTED. That is not a convention this migration invents; it is forced by the
 * gate: every route but `/attest/*` needs a token, and a token needs an attestation, so attestation is
 * strictly the first thing any device does.
 */
const V2: Migration = {
  version: 2,
  name: "devices carries the attestation",
  statements: [
    `CREATE TABLE IF NOT EXISTS devices (
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
    `DROP TABLE IF EXISTS device_records`,
  ],
};

/** Every migration, in the order they must be applied. Append only; never edit one that has shipped. */
export const MIGRATIONS: readonly Migration[] = [V1, V2];

/**
 * Apply every migration this store has not seen, in order, recording each.
 *
 * Idempotent: applying the list to an already-migrated store runs no statement. Each migration's
 * statements plus its version record go through `batch`, which is atomic — so a migration is either
 * applied and recorded, or neither. A half-applied migration that reported success would leave the store
 * in a shape no version describes, which is the one state this runner must never produce.
 */
export async function migrate(db: Db, log: (msg: string) => void = () => {}): Promise<void> {
  await db.execute(VERSION_TABLE);
  const { rows } = await db.execute(`SELECT version FROM schema_migrations`);
  const applied = new Set(rows.map((r) => Number(r.version)));

  for (const m of MIGRATIONS) {
    if (applied.has(m.version)) continue;
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
