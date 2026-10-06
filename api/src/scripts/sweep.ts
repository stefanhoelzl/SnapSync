// The nightly cleanup sweep (capability `event-lifetime`). Runs OUT of the Edge Script — Bunny has no
// scheduler and caps a request at 50 subrequests / 30 s CPU, so a whole-storage sweep cannot run there.
// This is a Deno program a scheduled GitHub Actions job runs on an Ubuntu runner: it talks to the
// relational store and to Bunny storage DIRECTLY (thousands of calls, no cap) and imports the Edge
// Script's OWN db/lifecycle modules so the rules cannot drift between the two.
//
// IT MARKS FROM THE DATABASE AND DELETES FROM STORAGE. Only the bytes live in storage; everything the
// sweep reasons about — which events exist, who is a member, which events still live — is a query
// (`docs/architecture.md`).
//
// Two ordered phases, and the order matters: the byte phase deletes the folders of the events the event
// phase leaves NOT living, and the event phase deletes their rows (by cascade) first — row before byte.
//
//   EVENT phase — each event gets one `sweepVerdict` (`lifecycle.ts`). DROP past its derived delete-by
//     (`max(createdAt, startsAt) + lifetimeSeconds` — the GUARANTEE): one `DELETE`, the cascade takes
//     memberships, assets and resources. COMPLETE once it is finished — EMPTY (ever joined, nobody still
//     present; dependable now that devices retry a leave until it lands) or past the CLOCK (`max(endsAt,
//     lastLandedAt) + 3 days`, ever joined): memberships, assets and resources go, the row stays until DROP
//     so a device still joined is told "completed". No notification is sent — see the delete site for why.
//
//     ⚠️ THE DECISION RUNS INSIDE AN INTERACTIVE TRANSACTION, which executes against the PRIMARY. The
//     emptiness rule is the exposed one: a stale replica that had not yet observed a REJOIN would see a
//     fully-departed event and complete a live one. The deadline rule reads immutable columns and is
//     stale-safe by contrast. Read-your-writes held in every trial measured, but from a workstation
//     against a test database — NOT from the edge (`PROBE-FINDINGS.md` §4.2) — and `config.ts` already
//     records the matching hazard for storage: "a stale replica read is the one failure mode that would
//     delete live data".
//
//   BYTE phase — an event's bytes are one folder, `files/<eventId>/` (change `per-event-storage-layout`).
//     Every such folder whose event no longer LIVES (dropped, completed) is deleted with ONE recursive
//     DELETE. Each run deletes every one it finds, so a crash, or a byte that landed after its event
//     closed, is healed by the next. Then the device rows: a device in NO surviving event, whose last token
//     has expired, loses its record and its attestation.
//
// ⚠️ THERE IS NO LONGER A REFUSAL TO SWEEP AN EMPTY STORE. A guard used to throw when the database held
// no rows at all while storage held device partitions — the signature of a store whose cutover backfill
// had not run, and of one this sweep would then read as "nothing is referenced" and empty entirely. It was
// removed deliberately (`changes/archive/2026-08-25-record-uploads-in-database` D9): it covered only the
// empty-store case and never the wrong-but-populated-store one. Nothing now stands between a store that
// does not describe this zone and the deletion of every event folder in it.
//
// The sweep touches `files/<eventId>/` folders and nothing else in the zone. `files/devices/` holds the
// bytes written before migration 0010; rows still point into it per event, so the old per-byte collection
// of it is retired, and the folder is deleted outright once its bytes are copied into their events
// (change `per-event-storage-layout`, migration plan). `site/` is the landing page's.

import { readSweepConfig } from "../config.ts";
import { libsqlDb } from "../db-libsql.ts";
import { deleteObject, eventDir, type FetchLike, listDir } from "../storage.ts";
import {
  collectableDevices,
  completeEvent,
  countDevices,
  type Db,
  deleteDevice,
  deleteEvent,
  deviceVersions,
  eventsWithCounts,
  liveEventIds,
} from "../db.ts";
import { validateUUID } from "../validators.ts";
import { sweepVerdict } from "../lifecycle.ts";
import { compareVersions } from "../version.ts";
import type { Config } from "../config.ts";

/** A count of storage objects plus their total size in bytes (summed from each entry's `Length`). */
export type Tally = { count: number; bytes: number };

/**
 * What one sweep run did — rendered by {@link formatSummary} into the GitHub Actions job log. Entity
 * tiers, each split deleted/kept: EVENTS, DEVICES (a device's record, attestation included), FILES (the
 * byte objects in the event folders), and DIRS (the `files/<eventId>/` folders). Files carry both a
 * `count` and a real `bytes` total so the log shows how much storage was actually reclaimed, not just how
 * many objects.
 *
 * Beside the tiers, `versions` counts the KEPT devices by app version and platform — what raising
 * `minAppVersion` would lock out (capability `app-update-required`).
 *
 * The devices tier counts DEVICE ROWS. It used to note "a device counted once regardless of how many of
 * its global config/attestation records exist"; a device now has exactly one record, so there is nothing
 * left to disambiguate.
 */
export type SweepSummary = {
  events: { deleted: number; completed: number; kept: number };
  devices: { deleted: number; kept: number };
  /** The kept devices counted by app version and platform — see {@link versionTable}. */
  versions: VersionRow[];
  files: { deleted: Tally; kept: Tally };
  dirs: { deleted: number; kept: number };
  errors: number;
  dryRun: boolean;
};

export type SweepDeps = {
  /** Upstream fetch to Bunny storage (global fetch in production; a fake in tests). */
  fetch: FetchLike;
  /** Validated config (storage host/zone/accessKey). */
  config: Config;
  /** The relational store this sweep marks from (`docs/architecture.md`). */
  db: Db;
  /** Wall clock, epoch ms. Injected so tests pin it. */
  now: () => number;
  /** When true, log every candidate and delete NOTHING. */
  dryRun: boolean;
  /** Progress log. Defaults to `console.log`. */
  log?: (msg: string) => void;
};

/**
 * Run the two-phase sweep. THROWS only on a SYSTEMIC failure (cannot list the top-level `files/`
 * directory — an auth failure surfaces here). Per-event failures are caught, counted in `summary.errors`,
 * and never abort the run (deletes are idempotent).
 */
export async function runSweep(deps: SweepDeps): Promise<SweepSummary> {
  const { fetch: f, config, db, now, dryRun } = deps;
  const log = deps.log ?? console.log;
  const summary: SweepSummary = {
    events: { deleted: 0, completed: 0, kept: 0 },
    devices: { deleted: 0, kept: 0 },
    versions: [],
    files: { deleted: { count: 0, bytes: 0 }, kept: { count: 0, bytes: 0 } },
    dirs: { deleted: 0, kept: 0 },
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
      const verdict = sweepVerdict(event, { total, active }, now());
      if (verdict === "keep") {
        summary.events.kept++;
        continue;
      }
      // A completed event keeps its row but no longer lives: the byte phase below deletes its folder
      // exactly like a deleted one's (in a dry run nothing was deleted, and the id set stands in for that).
      stale.push(event.eventId);
      if (verdict === "complete") {
        if (dryRun) {
          log(`[dry-run] would complete event ${event.eventId} (${active}/${total} active)`);
        } else {
          // No notification, for the same reason as a delete below: a device still joined learns the
          // event completed from its own next read, and leaves on it (capability `manage-membership`).
          await completeEvent(tx, event.eventId, new Date(now()).toISOString());
          log(`completed event ${event.eventId} (${active}/${total} active)`);
        }
        summary.events.completed++;
        continue;
      }
      if (dryRun) {
        log(`[dry-run] would delete event ${event.eventId} (${total} membership(s))`);
        summary.events.deleted++;
        continue;
      }
      // No notification is sent: the notify channel carries a semantic-free "something changed, go sync"
      // payload, which is the OPPOSITE of what a deletion means, and it would have to be dispatched
      // milliseconds before the deletes it announces — so the device wakes to an already-deleted event
      // and burns a scarce wake syncing against a corpse. Members discover the deletion on their own next
      // foreground details fetch (capability `manage-membership`), the only context where acting on it is safe.
      await deleteEvent(tx, event.eventId);
      summary.events.deleted++;
      log(`deleted stale event ${event.eventId}`);
    }
  });

  // ── BYTE PHASE ──────────────────────────────────────────────────────────────────────────────────
  // An event's bytes are ONE folder, `files/<eventId>/` (change `per-event-storage-layout`, D7). Its rows
  // went with its memberships — the phase above deleted them, row before byte — so what is left is the
  // folder of every event that no longer lives: dropped, completed, or completed by this run. That also
  // takes a byte that landed after its event closed (a PUT that passed the membership check just before)
  // and a folder a crashed run left: each run deletes every such folder it finds, so the step self-heals.
  //
  // LIST FIRST, THEN READ THE LIVE SET ON THE PRIMARY. A listed folder had its event row committed before
  // its first byte (a write needs a membership), so the later primary read sees it; the reverse order could
  // miss an event created in between, and a stale replica could miss one outright — either deletes a live
  // event's photos, the one failure this sweep must never have.
  //
  // `files/devices/` — the bytes written before migration 0010 — is not an event folder and is not touched
  // here: its rows now point into it per event, and it is deleted outright once copied out.
  const staleIds = new Set(stale);
  const top = await listDir(f, config, `files/`);
  const live = await db.transaction((tx) => liveEventIds(tx));
  for (const entry of top.filter((e) => e.IsDirectory)) {
    const eventId = entry.ObjectName;
    if (!validateUUID(eventId)) continue; // `devices`, and anything this sweep did not write
    const keep = live.has(eventId) && !staleIds.has(eventId);
    try {
      const files = (await listDir(f, config, eventDir(eventId))).filter((e) => !e.IsDirectory);
      const tally = keep ? summary.files.kept : summary.files.deleted;
      tally.count += files.length;
      tally.bytes += files.reduce((n, e) => n + e.Length, 0);
      if (keep) {
        summary.dirs.kept++;
        continue;
      }
      if (dryRun) {
        log(`[dry-run] would delete ${eventDir(eventId)} (${files.length} file(s))`);
      } else {
        await deleteObject(f, config, eventDir(eventId));
        log(`deleted ${eventDir(eventId)} (${files.length} file(s))`);
      }
      summary.dirs.deleted++;
    } catch (e) {
      summary.dirs.kept++;
      summary.errors++;
      log(`event ${eventId} byte folder deletion failed (continuing): ${e}`);
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
  const collectable = await collectableDevices(db, new Date(now()).toISOString(), staleIds);
  // The devices counted deleted — collected, or in a dry run would be — so the version table counts the
  // same kept set as the devices tier.
  const collected = new Set<string>();
  for (const deviceId of collectable) {
    try {
      if (dryRun) {
        log(`[dry-run] would collect the device record for ${deviceId}`);
        summary.devices.deleted++;
        collected.add(deviceId);
        continue;
      }
      await deleteDevice(db, deviceId);
      summary.devices.deleted++;
      collected.add(deviceId);
    } catch (err) {
      summary.errors++;
      log(`device ${deviceId} record collection failed (continuing): ${err}`);
    }
  }
  summary.devices.kept = totalDevices - summary.devices.deleted;
  // Diagnostics only: a failed read loses the table, never the run.
  try {
    summary.versions = versionTable(
      (await deviceVersions(db)).filter((d) => !collected.has(d.deviceId)),
    );
  } catch (err) {
    summary.errors++;
    log(`device version read failed (continuing): ${err}`);
  }

  return summary;
}

/**
 * One row of the version table: how many kept devices last declared `version` (capability
 * `app-update-required`), per platform. `version` is `null` for a device that never declared one since
 * the column existed — a v1-only build, or one not seen since.
 */
export type VersionRow = { version: string | null; ios: number; android: number };

/**
 * Count devices by version and platform: newest version first, the versionless row last. What raising
 * `minAppVersion` would lock out is read off the rows below the new minimum.
 */
export function versionTable(
  devices: readonly { appVersion: string | null; platform: string }[],
): VersionRow[] {
  const rows = new Map<string | null, VersionRow>();
  for (const d of devices) {
    const row = rows.get(d.appVersion) ?? { version: d.appVersion, ios: 0, android: 0 };
    if (d.platform === "android") row.android++;
    else row.ios++;
    rows.set(d.appVersion, row);
  }
  return [...rows.values()].sort((a, b) =>
    a.version === null ? 1 : b.version === null ? -1 : compareVersions(b.version, a.version)
  );
}

/** The version table's rows as label + per-platform counts, closed by a total row. */
function versionLines(
  rows: readonly VersionRow[],
): { label: string; ios: number; android: number }[] {
  const total = rows.reduce((t, r) => ({ ios: t.ios + r.ios, android: t.android + r.android }), {
    ios: 0,
    android: 0,
  });
  return [
    ...rows.map((r) => ({ label: r.version ?? "unknown", ios: r.ios, android: r.android })),
    { label: "total", ...total },
  ];
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
 * files and dirs each on one line, deleted vs kept, with files showing both object count and reclaimed
 * size.
 */
export function formatSummary(s: SweepSummary): string {
  const file = (t: Tally) => `${t.count} (${humanBytes(t.bytes)})`;
  return [
    `sweep summary${s.dryRun ? " (dry-run)" : ""}:`,
    `  events    ${s.events.deleted} deleted   ${s.events.completed} completed   ${s.events.kept} kept`,
    `  devices   ${s.devices.deleted} deleted   ${s.devices.kept} kept`,
    `  files     ${file(s.files.deleted)} deleted   ${file(s.files.kept)} kept`,
    `  dirs      ${s.dirs.deleted} deleted   ${s.dirs.kept} kept`,
    `  errors    ${s.errors}`,
    `  app versions (kept devices)`,
    `    ${"version".padEnd(10)}${"ios".padStart(6)}${"android".padStart(9)}`,
    ...versionLines(s.versions).map((l) =>
      `    ${l.label.padEnd(10)}${String(l.ios).padStart(6)}${String(l.android).padStart(9)}`
    ),
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
    `| tier | deleted | completed | kept |`,
    `| --- | --- | --- | --- |`,
    `| events | ${s.events.deleted} | ${s.events.completed} | ${s.events.kept} |`,
    `| devices | ${s.devices.deleted} | — | ${s.devices.kept} |`,
    `| files | ${file(s.files.deleted)} | — | ${file(s.files.kept)} |`,
    `| dirs | ${s.dirs.deleted} | — | ${s.dirs.kept} |`,
    ``,
    `**errors:** ${s.errors}`,
    ``,
    `### App versions (kept devices)`,
    ``,
    `| version | ios | android |`,
    `| --- | --- | --- |`,
    ...versionLines(s.versions).map((l) =>
      l.label === "total"
        ? `| **total** | **${l.ios}** | **${l.android}** |`
        : `| ${l.label} | ${l.ios} | ${l.android} |`
    ),
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
