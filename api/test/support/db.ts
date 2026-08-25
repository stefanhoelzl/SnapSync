// A migrated, in-memory relational store for tests that do not care about relational state — the
// attestation gate, the site/link routes, the download page. They still need a `Db` because every app
// carries one; giving them a REAL (empty) store rather than a stub means a route that unexpectedly
// starts reading rows fails on the assertion it should, not on a stub that throws.

import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import { migrate } from "../../src/db.ts";

/** An empty, migrated store. Callers that assert nothing about rows need not close it. */
export async function emptyStore() {
  const db = sqliteDb(":memory:");
  await migrate(db);
  return db;
}
