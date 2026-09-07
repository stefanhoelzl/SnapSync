// ASSERT THE DEPLOYED STORE'S SHAPE (capability `backend-deployment`).
//
// Compares the LIVE store's schema against what replaying `api/migrations/*.sql` builds, and fails the
// deploy when they differ.
//
// WHY THIS EXISTS AT ALL — three things nothing else in this repository observes:
//
// ① THE DEPLOYED SHAPE IS OTHERWISE UNMEASURED. Every other check runs against a locally-replayed store
//    on a DIFFERENT ENGINE (`node:sqlite` here, libSQL there). "The migrations produce the right schema"
//    has never been asserted about the store that actually serves.
// ② A HAND-EDITED STORE. `bunny db shell` is write-capable, so the schema can now be changed outside the
//    migrations — silently, and permanently, since no later migration would ever reconcile it.
// ③ A MIGRATION THAT APPLIED BUT DID NOT DO WHAT ITS AUTHOR THOUGHT.
//
// WHERE IT RUNS, AND WHY THAT IS TWO PLACES. `api-deploy.yml` calls it on both sides of its pending
// branch, and the cost of failing differs enormously between them:
//   · NOTHING PENDING → before the window decision. The store is already expected to match, so a
//     mismatch fails the run before anything is published and before any window opens. This is where
//     hand-edits land, since drift happens BETWEEN deploys and involves no migration.
//   · SOMETHING PENDING → after applying, before the real bundle publishes. Inside the window, which the
//     existing restore path lifts.
// Asserting pre-flight while a migration is pending would fail every migrating deploy, because the store
// legitimately does not match yet. The split is structural, not a convenience.
//
// IT NEEDS ONLY READ ACCESS. The one statement it issues against the live store is a `sqlite_master`
// SELECT. It holds the database credentials the workflow already has and touches no user data.
//
// Out of the bundle: `main.ts` never reaches it.

import { libsqlDb } from "../db-libsql.ts";
import { sqliteDb } from "../dev/db-sqlite.ts";
import { replay } from "../dev/replay.ts";
import { shapeDifferences, shapeOf } from "../dev/schema-shape.ts";
import { generateSchema, SCHEMA_PATH } from "./generate-schema.ts";

const url = Deno.env.get("BUNNY_DATABASE_URL");
const token = Deno.env.get("BUNNY_DATABASE_AUTH_TOKEN");
if (!url || !token) {
  console.error("missing configuration: BUNNY_DATABASE_URL, BUNNY_DATABASE_AUTH_TOKEN");
  Deno.exit(1);
}

// The COMMITTED artifact is what this claims to assert against, so check it is current first. On the
// deploy path that is belt-and-braces — `api.yml` gates it on the PR — but it costs nothing and makes
// the failure legible: "the snapshot is stale" and "the store disagrees" are different problems with
// different fixes, and collapsing them would send the operator after the wrong one.
if (await Deno.readTextFile(SCHEMA_PATH) !== await generateSchema()) {
  console.error(
    "::error::api/schema.sql is not what replaying api/migrations/*.sql produces. The deployed store " +
      "was NOT inspected. Run 'deno task schema' in api/ and commit it.",
  );
  Deno.exit(1);
}

const expectedStore = sqliteDb(":memory:");
await replay(expectedStore);
const expected = await shapeOf(expectedStore);
expectedStore.close();

const live = await shapeOf(libsqlDb(url, token));

const differences = shapeDifferences(live, expected);
if (differences.length === 0) {
  console.log(`SCHEMA ASSERTION: the deployed store matches (${expected.length} objects)`);
  Deno.exit(0);
}

console.error(
  `::error::the deployed store's schema is not what the migrations build (${differences.length} ` +
    `difference(s)). Either the schema was changed outside the migrations — 'bunny db shell' can do ` +
    `that — or a migration did not do what it appears to. This is repaired by rolling FORWARD with a ` +
    `migration, never by editing the store to match.`,
);
for (const d of differences) console.error(`  ${d}`);
Deno.exit(1);
