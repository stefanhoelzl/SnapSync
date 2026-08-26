// Apply the relational store's pending schema migrations (capability `database`). Run by `api-deploy.yml`
// BEFORE the bundle is published, so a bundle never serves against a store that has not had its schema
// applied.
//
// Out of the bundle, like the sweep: `main.ts` does not reach it, and the Edge Script never migrates.
// Idempotent — the version record makes an already-migrated store a no-op — so this runs on every deploy
// and does nothing on most of them.
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
import { appliedVersions, migrate } from "../migrations.ts";

const config = migrateConfig(Deno.env.toObject());
const db = libsqlDb(config.databaseUrl, config.databaseToken);

const before = await appliedVersions(db);
await migrate(db, (msg) => console.log(msg));
const after = await appliedVersions(db);

const applied = after.filter((v) => !before.includes(v));
console.log(
  applied.length === 0
    ? `schema is up to date (v${after.at(-1) ?? 0})`
    : `applied ${applied.length} migration(s): ${applied.map((v) => `v${v}`).join(", ")}`,
);
