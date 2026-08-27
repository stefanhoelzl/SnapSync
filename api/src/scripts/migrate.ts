// Apply the relational store's pending schema migrations (capability `database`). Run by `api-deploy.yml`
// BEFORE the bundle is published, so a bundle never serves against a store that has not had its schema
// applied.
//
// Out of the bundle, like the sweep: `main.ts` does not reach it, and the Edge Script never migrates.
// Idempotent — the version record makes an already-migrated store a no-op — so this runs on every deploy
// and does nothing on most of them.
//
// TWO MODES, ONE COMPARISON. `--pending` answers "would this apply anything?" without applying it, and
// `api-deploy.yml` branches on the answer: a deploy with nothing pending skips the maintenance window
// entirely and costs one publish, exactly as it does today. Both modes read `appliedVersions(db)` against
// the same `MIGRATIONS` list — two implementations of that comparison is the pair that must never
// disagree, since one decides whether the window opens and the other decides what runs inside it.
//
// THREE OUTCOMES, NOT TWO, and that is the point of the exit codes below. `deno run` also exits non-zero
// on a crash, so `0`/`1` would make "nothing pending" and "the check blew up" the same answer. If a crash
// were read as "nothing pending", CI would publish the new bundle onto an un-migrated store — the exact
// failure the maintenance window exists to prevent. So: 0 = none, 10 = pending, anything else = failed,
// and the workflow treats any unrecognised code as fatal.
//
// It holds the DATABASE credentials only, and resolves config through `migrateConfig` rather than
// `readSweepConfig` for that reason: the sweep's reader demands every secret including the storage access
// key, which `backend-deployment` forbids this workflow from holding. Asking for it here fails the deploy
// on a credential the step is right not to have.
//
// The one-time ATTESTATION backfill needs both halves and is NOT here: it is a throwaway that runs once,
// from a scratchpad, through `proton-env` (decision record: the relational migration's D13a — the cutover's
// programs are not committed).

import { migrateConfig } from "../config.ts";
import { libsqlDb } from "../db-libsql.ts";
import { appliedVersions, migrate, pendingMigrations } from "../migrations.ts";

/** Exit code for `--pending` when at least one migration is unapplied. Read by `api-deploy.yml`. */
export const PENDING_EXIT_CODE = 10;

const config = migrateConfig(Deno.env.toObject());
const db = libsqlDb(config.databaseUrl, config.databaseToken);

const before = await appliedVersions(db);

if (Deno.args.includes("--pending")) {
  // READ-ONLY. `appliedVersions` creates the version table if it is absent, which is the one write this
  // mode can make — and it is the same one `migrate` would make a moment later. It records nothing about
  // any migration.
  const pending = await pendingMigrations(db);
  // A single greppable line, so a workflow reading the log can say what it decided and why — the exit
  // code carries the decision, this carries the reason.
  console.log(
    pending.length === 0
      ? `MIGRATE PLAN: none (schema is at v${before.at(-1) ?? 0})`
      : `MIGRATE PLAN: ${pending.length} pending (${
        pending.map((m) => `v${m.version}`).join(", ")
      })`,
  );
  Deno.exit(pending.length === 0 ? 0 : PENDING_EXIT_CODE);
}

await migrate(db, (msg) => console.log(msg));
const after = await appliedVersions(db);

const applied = after.filter((v) => !before.includes(v));
console.log(
  applied.length === 0
    ? `schema is up to date (v${after.at(-1) ?? 0})`
    : `applied ${applied.length} migration(s): ${applied.map((v) => `v${v}`).join(", ")}`,
);
