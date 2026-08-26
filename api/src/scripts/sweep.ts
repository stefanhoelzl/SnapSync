// The nightly cleanup sweep (capability `scheduled-cleanup`). Runs OUT of the Edge Script — Bunny has no
// scheduler and caps a request at 50 subrequests / 30 s CPU, so a whole-storage sweep cannot run there.
// This is a Deno program a scheduled GitHub Actions job runs on an Ubuntu runner: it talks to the
// relational store and to Bunny storage DIRECTLY (thousands of calls, no cap) and imports the Edge
// Script's OWN db/lifecycle modules so the rules cannot drift between the two.
//
// IT MARKS FROM THE DATABASE AND DELETES FROM STORAGE. Only the bytes live in storage now; everything
// the sweep reasons about — which events exist, who is a member, which keys are referenced, each
// device's floor — is a query (capability `database`). The per-event, per-device manifest fan-out the
// root set used to require is gone.
//
// Two ordered phases, and the order still matters for its original reason: the asset phase evaluates
// bytes against the events that SURVIVE the event phase. Relationally that is automatic — after the
// event phase deletes, what remains in the store IS the surviving set — which is why no phase threads
// an id list to the other.
//
//   EVENT phase — delete every STALE event row. Stale means past its derived delete-by
//     (`max(createdAt, startsAt) + lifetimeSeconds` — the GUARANTEE), or EMPTY (ever joined, nobody
//     active left — OPPORTUNISTIC: a leave whose backend DELETE never landed keeps a membership active,
//     so an abandoned event may never empty). One `DELETE`; the cascade takes memberships and assets.
//     No notification is sent — see the delete site for why.
//
//     ⚠️ THE DECISION RUNS INSIDE AN INTERACTIVE TRANSACTION, which executes against the PRIMARY. The
//     emptiness rule is the exposed one: a stale replica that had not yet observed a REJOIN would see a
//     fully-departed event and delete a live one. The deadline rule reads immutable columns and is
//     stale-safe by contrast. Read-your-writes held in every trial measured, but from a workstation
//     against a test database — NOT from the edge (`PROBE-FINDINGS.md` §4.2) — and `config.ts` already
//     records the matching hazard for storage: "a stale replica read is the one failure mode that would
//     delete live data".
//
//   ASSET phase — collect a device's byte iff it is unreferenced by any surviving event AND was uploaded
//     before the earliest surviving event the device is ACTIVE in (min startsAt; ∅ → +∞). Its resource
//     ROW is deleted BEFORE the byte: a crash then leaves an orphan byte the next run collects, rather
//     than a row claiming bytes that are gone. A device in NO surviving event additionally loses its
//     device record and its attestation object. No wall-clock age fudge: a live upload is always ≥ its
//     event's start ≥ the floor.
//
// ⚠️ THERE IS NO LONGER A REFUSAL TO SWEEP AN EMPTY STORE. A guard used to throw when the database held
// no rows at all while storage held device partitions — the signature of a store whose cutover backfill
// had not run, and of one this sweep would then read as "nothing is referenced" and empty entirely. It was
// removed deliberately (this change's design.md D9): it covered only the empty-store case and never the
// wrong-but-populated-store one, and the state it was written for is past. Nothing now stands between a
// store that does not describe this zone and the deletion of every byte in it.
//
// WHAT THIS SWEEP NO LONGER DOES, deliberately: it does not touch the legacy `events/` markers and
// manifests, or the legacy `devices/<id>.json` configs, that the object-store era wrote. Those objects
// are the ROLLBACK PATH for this change (design.md D13) — nothing reads them, nothing adds to them, and
// reclaiming them is a later change's job, not a silent side effect of this one.

import { readSweepConfig } from "../config.ts";
import { libsqlDb } from "../db-libsql.ts";
import { decodeObjectName, deleteObject, deviceDir, type FetchLike, listDir } from "../storage.ts";
import {
  activeFloors,
  collectableDevices,
  countDevices,
  type Db,
  deleteDevice,
  deleteEvent,
  deleteResource,
  eventsWithCounts,
  referencedKeys,
} from "../db.ts";
import { eventIsStale } from "../lifecycle.ts";
import type { Config } from "../config.ts";

/** A count of storage objects plus their total size in bytes (summed from each entry's `Length`). */
export type Tally = { count: number; bytes: number };

/**
 * What one sweep run did — rendered by {@link formatSummary} into the GitHub Actions job log. Three
 * entity tiers, each split deleted/kept: EVENTS (markers + their manifests), DEVICES (a device's global
 * config + attestation records — one device may own two objects, so this counts DEVICES, not records),
 * and FILES (the stored resource byte objects). Files carry both a `count` and a real `bytes` total so
 * the log shows how much storage was actually reclaimed, not just how many objects.
 *
 * The devices tier counts DEVICE ROWS. It used to note "a device counted once regardless of how many of
 * its global config/attestation records exist"; a device now has exactly one record, so there is nothing
 * left to disambiguate.
 */
export type SweepSummary = {
  events: { deleted: number; kept: number };
  devices: { deleted: number; kept: number };
  files: { deleted: Tally; kept: Tally };
  errors: number;
  dryRun: boolean;
};

export type SweepDeps = {
  /** Upstream fetch to Bunny storage (global fetch in production; a fake in tests). */
  fetch: FetchLike;
  /** Validated config (storage host/zone/accessKey). */
  config: Config;
  /** The relational store this sweep marks from (capability `database`). */
  db: Db;
  /** Wall clock, epoch ms. Injected so tests pin it. */
  now: () => number;
  /** When true, log every candidate and delete NOTHING. */
  dryRun: boolean;
  /** Progress log. Defaults to `console.log`. */
  log?: (msg: string) => void;
};

/** Parse a stored `LastChanged` / `startsAt` to epoch ms, or `NaN` when unparseable. */
function ms(s: string | undefined): number {
  return s ? Date.parse(s) : NaN;
}

/**
 * Run the two-phase sweep. THROWS only on a SYSTEMIC failure (cannot list the top-level `events/` or
 * `files/devices/` directories — an auth failure surfaces here). Per-event and per-object failures are
 * caught, counted in `summary.errors`, and never abort the run (deletes are idempotent).
 */
export async function runSweep(deps: SweepDeps): Promise<SweepSummary> {
  const { fetch: f, config, db, now, dryRun } = deps;
  const log = deps.log ?? console.log;
  const summary: SweepSummary = {
    events: { deleted: 0, kept: 0 },
    devices: { deleted: 0, kept: 0 },
    files: { deleted: { count: 0, bytes: 0 }, kept: { count: 0, bytes: 0 } },
    errors: 0,
    dryRun,
  };

  // ── EVENT PHASE ─────────────────────────────────────────────────────────────────────────────────
  // One read gives every event and its membership counts — the whole input to the staleness decision.
  // The decision AND the deletes run inside an interactive transaction, which executes against the
  // primary: see the header for why a possibly-stale replica read must not decide a deletion.
  const stale: string[] = [];
  await db.transaction(async (tx) => {
    for (const { event, total, active } of await eventsWithCounts(tx)) {
      if (!eventIsStale(event, { total, active }, now())) {
        summary.events.kept++;
        continue;
      }
      stale.push(event.eventId);
      if (dryRun) {
        log(`[dry-run] would delete event ${event.eventId} (${total} membership(s))`);
        summary.events.deleted++;
        continue;
      }
      // No notification is sent: the notify channel carries a semantic-free "something changed, go sync"
      // payload, which is the OPPOSITE of what a deletion means, and it would have to be dispatched
      // milliseconds before the deletes it announces — so the device wakes to an already-deleted event
      // and burns a scarce wake syncing against a corpse. Members discover the deletion on their own next
      // foreground details fetch (capability `leave-event`), the only context where acting on it is safe.
      await deleteEvent(tx, event.eventId);
      summary.events.deleted++;
      log(`deleted stale event ${event.eventId}`);
    }
  });

  // ── ASSET PHASE ─────────────────────────────────────────────────────────────────────────────────
  // Against the events that SURVIVE — which, after the phase above, is simply what the store still holds.
  // The stale ids are excluded explicitly rather than relied on to be gone: in a DRY RUN nothing was
  // deleted, so the reads would otherwise still see them and report a collection a real run would not
  // make. Passing the set makes both modes evaluate the identical surviving world.
  const staleIds = new Set(stale);
  const referenced = await referencedKeys(db, staleIds);
  const floors = await activeFloors(db, staleIds);

  // Walk every device's byte partition and collect the unreferenced, below-floor bytes.
  const deviceDirEntries = await listDir(f, config, `files/devices/`);
  const deviceIds = deviceDirEntries.filter((e) => e.IsDirectory).map((e) => e.ObjectName);

  for (const deviceId of deviceIds) {
    // A device with no active surviving membership has floor `+∞`: nothing of its is above the floor.
    const floorMs = floors.has(deviceId) ? ms(floors.get(deviceId)) : Infinity;
    try {
      const files = await listDir(f, config, deviceDir(deviceId));
      for (const e of files.filter((e) => !e.IsDirectory)) {
        // Resource rows carry the BARE object name, so decode the stored `ObjectName` before comparing —
        // a percent-encoded filename must still match.
        const key = decodeObjectName(e.ObjectName);
        if (referenced.has(`${deviceId}/${key}`)) {
          summary.files.kept.count++;
          summary.files.kept.bytes += e.Length;
          continue;
        }
        const uploadedMs = ms(e.LastChanged);
        // Retain when the upload time is unparseable (fail safe) or at/after the floor (a live upload).
        if (Number.isNaN(uploadedMs) || uploadedMs >= floorMs) {
          summary.files.kept.count++;
          summary.files.kept.bytes += e.Length;
          continue;
        }
        if (dryRun) {
          log(`[dry-run] would collect byte files/devices/${deviceId}/${e.ObjectName}`);
          summary.files.deleted.count++;
          summary.files.deleted.bytes += e.Length;
          continue;
        }
        // ROW FIRST, THEN BYTE (design.md D8). A crash between them leaves an orphan byte that is still
        // unreferenced and still below the floor, so the next run collects it. The reverse order would
        // leave a row asserting `uploaded = 1` for bytes that are gone.
        await deleteResource(db, deviceId, key);
        await deleteObject(f, config, `${deviceDir(deviceId)}${e.ObjectName}`);
        summary.files.deleted.count++;
        summary.files.deleted.bytes += e.Length;
      }
    } catch (e) {
      summary.errors++;
      log(`device ${deviceId} byte collection failed (continuing): ${e}`);
    }
  }

  // Device rows: a device's whole global record, attestation included. Collected iff it holds no
  // membership in any surviving event AND no token minted for it can still verify — see the header for
  // why the second clause is forcing. A returning device re-attests on demand and re-registers its push
  // token on its next launch.
  //
  // ONE PREDICATE, NOT A ROSTER WALK. The device roster this used to iterate existed to find devices whose
  // attestation OBJECT needed collecting even though they held no row; the object is a column now, so
  // there is nothing left for a roster to find.
  const totalDevices = await countDevices(db);
  for (const deviceId of await collectableDevices(db, new Date(now()).toISOString(), staleIds)) {
    try {
      if (dryRun) {
        log(`[dry-run] would collect the device record for ${deviceId}`);
        summary.devices.deleted++;
        continue;
      }
      await deleteDevice(db, deviceId);
      summary.devices.deleted++;
    } catch (err) {
      summary.errors++;
      log(`device ${deviceId} record collection failed (continuing): ${err}`);
    }
  }
  summary.devices.kept = totalDevices - summary.devices.deleted;

  return summary;
}

/** Render a byte count as a human-readable size (`1.2 MB`); IEC-style, `< 1024` stays `N B`. */
export function humanBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB", "TB", "PB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(1)} ${units[i]}`;
}

/**
 * Render a {@link SweepSummary} as an aligned, human-readable block for the job log — events, devices,
 * and files each on one line, deleted vs kept, with files showing both object count and reclaimed size.
 */
export function formatSummary(s: SweepSummary): string {
  const file = (t: Tally) => `${t.count} (${humanBytes(t.bytes)})`;
  return [
    `sweep summary${s.dryRun ? " (dry-run)" : ""}:`,
    `  events    ${s.events.deleted} deleted   ${s.events.kept} kept`,
    `  devices   ${s.devices.deleted} deleted   ${s.devices.kept} kept`,
    `  files     ${file(s.files.deleted)} deleted   ${file(s.files.kept)} kept`,
    `  errors    ${s.errors}`,
  ].join("\n");
}

/**
 * Render a {@link SweepSummary} as GitHub-flavoured Markdown for the Actions job **Summary** panel
 * (`$GITHUB_STEP_SUMMARY`) — a table so the tiers render, not a collapsed paragraph. Same numbers as
 * {@link formatSummary}; only the framing differs.
 */
export function markdownSummary(s: SweepSummary): string {
  const file = (t: Tally) => `${t.count} (${humanBytes(t.bytes)})`;
  return [
    `## Nightly cleanup sweep${s.dryRun ? " (dry-run — nothing deleted)" : ""}`,
    ``,
    `| tier | deleted | kept |`,
    `| --- | --- | --- |`,
    `| events | ${s.events.deleted} | ${s.events.kept} |`,
    `| devices | ${s.devices.deleted} | ${s.devices.kept} |`,
    `| files | ${file(s.files.deleted)} | ${file(s.files.kept)} |`,
    ``,
    `**errors:** ${s.errors}`,
    ``,
  ].join("\n");
}

// ── Entry point (GitHub Actions) ────────────────────────────────────────────────────────────────────
if (import.meta.main) {
  try {
    // Config first (a missing secret is a systemic failure), then the run. Both exit 1 loudly.
    const config = readSweepConfig(Deno.env.toObject());
    const dryRun = Deno.args.includes("--dry-run");

    const summary = await runSweep({
      fetch: (url, init) => fetch(url, init),
      config,
      db: libsqlDb(config.databaseUrl, config.databaseToken),
      now: Date.now,
      dryRun,
      log: console.log,
    });
    console.log(formatSummary(summary));
    // On a GitHub Actions runner, also render the summary to the job's Summary panel (a Markdown table).
    // The env var is absent locally, so this is a no-op off-CI and needs no write permission there.
    const stepSummaryPath = Deno.env.get("GITHUB_STEP_SUMMARY");
    if (stepSummaryPath) {
      await Deno.writeTextFile(stepSummaryPath, markdownSummary(summary), { append: true });
    }
  } catch (e) {
    console.error(`sweep: systemic failure — ${e}`);
    Deno.exit(1);
  }
}
