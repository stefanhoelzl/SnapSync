// A migrated, in-memory relational store for tests that do not care about relational state — the
// attestation gate, the site/link routes, the download page. They still need a `Db` because every app
// carries one; giving them a REAL (empty) store rather than a stub means a route that unexpectedly
// starts reading rows fails on the assertion it should, not on a stub that throws.

import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import { migrate } from "../../src/migrations.ts";
import type { Db } from "../../src/db.ts";

/** An empty, migrated store. Callers that assert nothing about rows need not close it. */
export async function emptyStore() {
  const db = sqliteDb(":memory:");
  await migrate(db);
  return db;
}

/** A token expiry far enough out that the sweep will never consider the device collectable. */
export const LIVE_TOKEN = "2099-01-01T00:00:00Z";

/** A token expiry already past, so the sweep may collect the row. */
export const DEAD_TOKEN = "2020-01-01T00:00:00Z";

/**
 * Enrol a device: give it the attestation row every other device-scoped write now requires.
 *
 * Most tests do not care about attestation and simply need the device to EXIST — a `devices` row is
 * created only by `POST /attest/token`, so without this a config write is refused with `401` and a
 * notify fan-out finds no token. Real attestation is exercised in `attest.test.ts`; this is the
 * shorthand for "this device has already been through it".
 */
export async function enrolDevice(
  db: Db,
  deviceId: string,
  tokenExpiresAt: string = LIVE_TOKEN,
): Promise<void> {
  await db.execute(
    `INSERT INTO devices (device_id, created_at, attest_key, attest_env, attested_at,
                          attest_token_expires_at)
     VALUES (?, '2026-07-14T00:00:00Z', 'BASE64KEY', 'production', '2026-07-14T00:00:00Z', ?)
     ON CONFLICT (device_id) DO UPDATE SET attest_token_expires_at = excluded.attest_token_expires_at`,
    [deviceId, tokenExpiresAt],
  );
}
