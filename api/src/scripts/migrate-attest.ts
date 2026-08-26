// THE ONE-TIME DATA MIGRATION (capability `device-attestation`): carry every `devices/<id>.attest.json`
// into the `devices` table, then delete the objects.
//
// WHY THIS IS A PROGRAM AND NOT A MIGRATION STATEMENT. The attested key lives in the STORAGE ZONE, which
// SQL cannot reach, and the attestation columns are `NOT NULL` — so schema migration v2 creates the table
// but can carry no rows. This program runs BETWEEN the two halves: it reads both stores, applies v2, and
// joins. It is the only thing that holds both credential sets, which is why it runs from its own
// dispatched job rather than from the deploy workflow (`backend-deployment` forbids the storage access key
// there, and this change deliberately does not widen that).
//
// RUNNING THE DEPLOY WITHOUT THIS IS SURVIVABLE, NOT CATASTROPHIC. v2 alone leaves `devices` empty: every
// device then re-attests once (recreating its row) and re-registers its push token at its next launch,
// because iOS redelivers the APNs token on every launch. One Apple attestation per device, no photo
// affected, no operator action. That is the cost of getting the order wrong — worth knowing, not worth
// building a lock around.
//
// RE-RUNNABLE. Applying v2 twice is a no-op (the version is recorded), the row insert is an upsert, and
// deleting an absent object is a no-op. A half-finished run is finished by running it again.
//
// ⚠️ THE DELETE IS EXACT-SUFFIX ONLY. The `devices/` prefix ALSO holds the legacy config objects
// (`devices/<id>.json`), and `deleteObject` deletes a DIRECTORY RECURSIVELY when handed a key ending in
// `/`. Only keys ending exactly `.attest.json` are ever passed to it, and never a prefix.

import { readSweepConfig } from "../config.ts";
import { libsqlDb } from "../db-libsql.ts";
import { deleteObject, type FetchLike, listDir, readObjectText } from "../storage.ts";
import { type Db, putAttestation } from "../db.ts";
import { migrate } from "../migrations.ts";
import { tokenExpiryIso } from "../attest.ts";
import type { Config } from "../config.ts";

/**
 * The retired on-storage shape. Declared here because this program is its LAST reader — the type left
 * `storage.ts` with the object it described.
 */
type LegacyAttestRecord = {
  publicKey: string;
  environment: string;
  attestedAt: string;
};

const SUFFIX = ".attest.json";

/** What one run did. Printed, and asserted by the tests. */
export type AttestMigrationSummary = {
  objectsFound: number;
  rowsWritten: number;
  pushCarried: number;
  rowsDropped: number;
  objectsDeleted: number;
  errors: number;
  dryRun: boolean;
};

export type Deps = {
  fetch: FetchLike;
  config: Config;
  db: Db;
  now: () => number;
  dryRun: boolean;
  log?: (msg: string) => void;
};

/** The push half of a legacy `device_records` row, if the table is still there to read. */
type LegacyPush = { kind: string | null; token: string | null; env: string | null };

/**
 * Read the legacy push registrations BEFORE v2 drops the table that holds them.
 *
 * Returns an empty map when the table is already gone — the re-run case, and the case where a deploy
 * applied v2 first. Neither is an error: a push token is reproduced by the device at its next launch, so
 * losing one costs a round-trip, not a fact.
 */
async function readLegacyPush(db: Db): Promise<Map<string, LegacyPush>> {
  const { rows: tables } = await db.execute(
    `SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'device_records'`,
  );
  if (tables.length === 0) return new Map();
  const { rows } = await db.execute(
    `SELECT device_id, push_kind, push_token, push_env FROM device_records`,
  );
  const out = new Map<string, LegacyPush>();
  for (const r of rows) {
    out.set(String(r.device_id), {
      kind: r.push_kind === null ? null : String(r.push_kind),
      token: r.push_token === null ? null : String(r.push_token),
      env: r.push_env === null ? null : String(r.push_env),
    });
  }
  return out;
}

export async function runAttestMigration(deps: Deps): Promise<AttestMigrationSummary> {
  const { fetch: f, config, db, now, dryRun } = deps;
  const log = deps.log ?? console.log;
  const summary: AttestMigrationSummary = {
    objectsFound: 0,
    rowsWritten: 0,
    pushCarried: 0,
    rowsDropped: 0,
    objectsDeleted: 0,
    errors: 0,
    dryRun,
  };

  // 1. Both halves, read BEFORE anything is written or dropped.
  const legacyPush = await readLegacyPush(db);
  const entries = await listDir(f, config, `devices/`);
  const attestKeys = entries
    .filter((e) => !e.IsDirectory && e.ObjectName.endsWith(SUFFIX))
    .map((e) => `devices/${e.ObjectName}`);
  summary.objectsFound = attestKeys.length;

  const records = new Map<string, LegacyAttestRecord>();
  for (const key of attestKeys) {
    try {
      const raw = await readObjectText(f, config, key);
      if (raw === null) continue; // vanished between the listing and the read
      const deviceId = key.slice("devices/".length, -SUFFIX.length);
      records.set(deviceId, JSON.parse(raw) as LegacyAttestRecord);
    } catch (e) {
      summary.errors++;
      log(`could not read ${key} (continuing): ${e}`);
    }
  }

  // A legacy row with no attestation object cannot satisfy the NOT NULL columns. Its device re-attests at
  // its next launch, which recreates the row; until then it is unreachable by push, which notify already
  // treats as ordinary.
  summary.rowsDropped = [...legacyPush.keys()].filter((id) => !records.has(id)).length;
  if (summary.rowsDropped > 0) {
    log(
      `${summary.rowsDropped} legacy device record(s) have no attestation object and will be dropped`,
    );
  }

  if (dryRun) {
    log(
      `[dry-run] would write ${records.size} device row(s), carry ` +
        `${
          [...records.keys()].filter((id) => legacyPush.get(id)?.token).length
        } push registration(s), ` +
        `drop ${summary.rowsDropped}, and delete ${attestKeys.length} object(s)`,
    );
    return summary;
  }

  // 2. Apply the schema migration. v1 is inert against the deployed store; v2 creates `devices` and drops
  //    `device_records` — which is why step 1 read it first.
  await migrate(db, log);

  // 3. Join. THE SEEDED EXPIRY IS AN UPPER BOUND ON THE TRUTH, not a guess: a token minted at any instant
  //    T ≤ now expires at T + ttl ≤ now + ttl. Seeding `now + ttl` therefore cannot collect a device while
  //    it may still hold a working credential, which is the one property the sweep depends on. The object
  //    records no minted expiry to recover — a token is verified from its own signature and never stored.
  const seededExpiry = tokenExpiryIso(config, now());
  for (const [deviceId, record] of records) {
    try {
      await putAttestation(
        db,
        deviceId,
        { publicKey: record.publicKey, environment: record.environment },
        record.attestedAt,
        seededExpiry,
      );
      summary.rowsWritten++;
      const push = legacyPush.get(deviceId);
      if (push?.kind && push.token && push.env) {
        await db.execute(
          `UPDATE devices SET push_kind = ?, push_token = ?, push_env = ?, push_updated_at = ?
            WHERE device_id = ?`,
          [push.kind, push.token, push.env, record.attestedAt, deviceId],
        );
        summary.pushCarried++;
      }
    } catch (e) {
      summary.errors++;
      log(`could not write the row for ${deviceId} (continuing): ${e}`);
    }
  }

  // 4. The objects, only once their contents are rows. Exact suffix, never a prefix, never a directory.
  for (const key of attestKeys) {
    if (!key.endsWith(SUFFIX) || key.endsWith("/")) continue; // belt and braces; see the header
    try {
      await deleteObject(f, config, key);
      summary.objectsDeleted++;
    } catch (e) {
      summary.errors++;
      log(`could not delete ${key} (continuing): ${e}`);
    }
  }

  return summary;
}

export function formatSummary(s: AttestMigrationSummary): string {
  return [
    s.dryRun ? "attestation migration (DRY RUN)" : "attestation migration",
    `  objects found     ${s.objectsFound}`,
    `  rows written      ${s.rowsWritten}`,
    `  push carried      ${s.pushCarried}`,
    `  legacy rows drop  ${s.rowsDropped}`,
    `  objects deleted   ${s.objectsDeleted}`,
    `  errors            ${s.errors}`,
  ].join("\n");
}

if (import.meta.main) {
  const dryRun = Deno.args.includes("--dry-run");
  const config = readSweepConfig(Deno.env.toObject());
  const db = libsqlDb(config.databaseUrl, config.databaseToken);
  const summary = await runAttestMigration({
    fetch: (url, init) => fetch(url, init),
    config,
    db,
    now: () => Date.now(),
    dryRun,
  });
  const rendered = formatSummary(summary);
  console.log(rendered);
  const stepSummary = Deno.env.get("GITHUB_STEP_SUMMARY");
  if (stepSummary) {
    await Deno.writeTextFile(stepSummary, `\n\`\`\`\n${rendered}\n\`\`\`\n`, { append: true });
  }
  if (summary.errors > 0) Deno.exit(1);
}
