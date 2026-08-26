// Apply the relational store's pending schema migrations (capability `database`). Run by `api-deploy.yml`
// BEFORE the bundle is published, so a bundle never serves against a store that has not had its schema
// applied.
//
// Out of the bundle, like the sweep: `main.ts` does not reach it, and the Edge Script never migrates.
// Idempotent — the version record makes an already-migrated store a no-op — so this runs on every deploy
// and does nothing on most of them.
//
// It holds the DATABASE credentials only. The storage access key is deliberately not available here; the
// one migration that needs both is `migrate-attest.ts`, which runs from its own dispatched job.

import { readSweepConfig } from "../config.ts";
import { libsqlDb } from "../db-libsql.ts";
import { appliedVersions, migrate } from "../migrations.ts";

const config = readSweepConfig(Deno.env.toObject());
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
