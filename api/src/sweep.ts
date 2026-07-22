// The nightly cleanup sweep (capability `scheduled-cleanup`). Runs OUT of the Edge Script — Bunny has no
// scheduler and caps a request at 50 subrequests / 30 s CPU, so a whole-storage sweep cannot run there.
// This is a Deno program a scheduled GitHub Actions job runs on an Ubuntu runner: it talks to Bunny
// storage DIRECTLY (thousands of calls, no cap) and imports the Edge Script's OWN storage/lifecycle
// modules so the layout and lifecycle rules cannot drift between the two.
//
// Two ordered phases:
//   EVENT phase — delete every STALE event (now > endsAt + grace, or a legacy/corrupt marker): notify its
//     active members through the edge's notify route (authorized with ADMIN_NOTIFY_KEY) FIRST, then delete its
//     marker + every manifest. Deletion by the sweep IS expiry (the on-touch reap is gone).
//   ASSET phase (against the events that SURVIVE) — collect a device's byte iff it is unreferenced by any
//     surviving manifest AND was uploaded before the earliest surviving event the device is ACTIVE in
//     (min startsAt; ∅ → +∞). A device in NO surviving event additionally loses its config + attestation
//     record. No wall-clock age fudge: a live upload is always ≥ its event's start ≥ the floor.

import { readSweepConfig } from "./config.ts";
import {
  type BunnyEntry,
  decodeObjectName,
  deleteObject,
  deviceDir,
  deviceLeftManifestKey,
  deviceManifestDir,
  deviceManifestKey,
  type FetchLike,
  listDir,
  MARKER_PREFIX,
  markerKey,
  readManifestObject,
  readMarker,
} from "./storage.ts";
import { eventIsStale, resolveMembership } from "./lifecycle.ts";
import type { Config } from "./config.ts";

/** A count of storage objects plus their total size in bytes (summed from each entry's `Length`). */
export type Tally = { count: number; bytes: number };

/**
 * What one sweep run did — rendered by {@link formatSummary} into the GitHub Actions job log. Three
 * entity tiers, each split deleted/kept: EVENTS (markers + their manifests), DEVICES (a device's global
 * config + attestation records — one device may own two objects, so this counts DEVICES, not records),
 * and FILES (the stored resource byte objects). Files carry both a `count` and a real `bytes` total so
 * the log shows how much storage was actually reclaimed, not just how many objects.
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
  /** Validated config (storage host/zone/accessKey + grace period + admin key). */
  config: Config;
  /** Wall clock, epoch ms. Injected so tests pin it. */
  now: () => number;
  /** When true, log every candidate and delete NOTHING. */
  dryRun: boolean;
  /**
   * Notify an expiring event's members before it is deleted (best-effort). Injected so tests need no
   * edge: production POSTs `/<eventId>/notify` to the edge with the ADMIN_NOTIFY_KEY. Never throws.
   */
  notify: (eventId: string) => Promise<void>;
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
  const { fetch: f, config, now, dryRun } = deps;
  const log = deps.log ?? console.log;
  const summary: SweepSummary = {
    events: { deleted: 0, kept: 0 },
    devices: { deleted: 0, kept: 0 },
    files: { deleted: { count: 0, bytes: 0 }, kept: { count: 0, bytes: 0 } },
    errors: 0,
    dryRun,
  };

  // ── EVENT PHASE ─────────────────────────────────────────────────────────────────────────────────
  // Enumerate every event directory (systemic LIST — a failure here throws and fails the run).
  const eventEntries = await listDir(f, config, `${MARKER_PREFIX}/`);
  const eventIds = (eventEntries ?? []).filter((e) => e.IsDirectory).map((e) => e.ObjectName);
  // The events that SURVIVE this phase — the asset phase evaluates bytes against these.
  const surviving: { eventId: string; startsAtMs: number; entries: BunnyEntry[] | null }[] = [];

  for (const eventId of eventIds) {
    try {
      const marker = await readMarker(f, config, eventId);
      const stale = marker === null || eventIsStale(marker, now(), config.eventGraceSeconds);
      const entries = await listDir(f, config, deviceManifestDir(eventId));
      if (!stale) {
        surviving.push({ eventId, startsAtMs: ms(marker!.startsAt), entries });
        summary.events.kept++;
        continue;
      }
      // STALE → notify members (best-effort, before the marker is gone), then delete marker + manifests.
      if (dryRun) {
        log(
          `[dry-run] would delete event ${eventId} (${(entries ?? []).length} manifest object(s))`,
        );
        summary.events.deleted++;
        continue;
      }
      await deps.notify(eventId); // best-effort; never throws
      for (const e of (entries ?? []).filter((e) => !e.IsDirectory)) {
        await deleteObject(f, config, `${deviceManifestDir(eventId)}${e.ObjectName}`);
      }
      await deleteObject(f, config, markerKey(eventId)); // marker LAST — retryable if interrupted
      summary.events.deleted++;
      log(`deleted stale event ${eventId}`);
    } catch (e) {
      summary.errors++;
      log(`event ${eventId}: delete failed (continuing): ${e}`);
    }
  }

  // ── ASSET PHASE ─────────────────────────────────────────────────────────────────────────────────
  // Build, from the SURVIVING events: the referenced byte keys, each device's active-event floor, and
  // the set of devices that appear (active or departed) in any surviving event.
  const referenced = new Set<string>(); // `${deviceId}/${decodedResourceKey}`
  const activeFloorMs = new Map<string, number>(); // deviceId → min startsAt over ACTIVE memberships
  const inSurviving = new Set<string>(); // deviceIds appearing (active OR departed) in a surviving event

  for (const { eventId, startsAtMs, entries } of surviving) {
    for (const m of resolveMembership(entries)) {
      inSurviving.add(m.deviceId);
      if (m.state === "active") {
        const prev = activeFloorMs.get(m.deviceId);
        if (prev === undefined || startsAtMs < prev) activeFloorMs.set(m.deviceId, startsAtMs);
      }
      try {
        const key = m.state === "departed"
          ? deviceLeftManifestKey(eventId, m.deviceId)
          : deviceManifestKey(eventId, m.deviceId);
        const manifest = await readManifestObject(f, config, key);
        for (const a of manifest.assets ?? []) {
          for (const r of a.resources ?? []) referenced.add(`${m.deviceId}/${r.key}`);
        }
      } catch (e) {
        // A manifest we cannot read: fail SAFE — treat every one of this event's bytes as referenced by
        // marking nothing collectable this run (we simply skip building its referenced set, and the
        // per-byte floor still protects live uploads). Count it and move on.
        summary.errors++;
        log(`manifest read failed for ${m.deviceId} in ${eventId} (bytes kept this run): ${e}`);
      }
    }
  }

  // Walk every device's byte partition and collect the unreferenced, below-floor bytes.
  const deviceDirEntries = await listDir(f, config, `files/devices/`);
  const deviceIds = (deviceDirEntries ?? []).filter((e) => e.IsDirectory).map((e) => e.ObjectName);
  for (const deviceId of deviceIds) {
    const floor = activeFloorMs.get(deviceId) ?? Infinity; // ∅ (no active surviving membership) → +∞
    try {
      const files = await listDir(f, config, deviceDir(deviceId));
      for (const e of (files ?? []).filter((e) => !e.IsDirectory)) {
        // The manifest names the DECODED object name (same as the union's completeness check), so decode
        // the stored `ObjectName` before comparing — a percent-encoded filename must still match.
        if (referenced.has(`${deviceId}/${decodeObjectName(e.ObjectName)}`)) {
          summary.files.kept.count++;
          summary.files.kept.bytes += e.Length;
          continue;
        }
        const uploadedMs = ms(e.LastChanged);
        // Retain when the upload time is unparseable (fail safe) or at/after the floor (a live upload).
        if (Number.isNaN(uploadedMs) || uploadedMs >= floor) {
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
        await deleteObject(f, config, `${deviceDir(deviceId)}${e.ObjectName}`);
        summary.files.deleted.count++;
        summary.files.deleted.bytes += e.Length;
      }
    } catch (e) {
      summary.errors++;
      log(`device ${deviceId} byte collection failed (continuing): ${e}`);
    }
  }

  // Device-global records: config + attestation. A device owns up to two objects (`<id>.json` and
  // `<id>.attest.json`), so group the `devices/` entries by deviceId and count DEVICES, not records —
  // a device is KEPT iff it appears in a surviving event, else its every record is collected.
  const configEntries = await listDir(f, config, `devices/`);
  const recordsByDevice = new Map<string, string[]>(); // deviceId → its record object name(s)
  for (const e of (configEntries ?? []).filter((e) => !e.IsDirectory)) {
    const name = e.ObjectName;
    // `<id>.attest.json` first (it also ends with `.json`), then `<id>.json`.
    const deviceId = name.endsWith(".attest.json")
      ? name.slice(0, -".attest.json".length)
      : name.endsWith(".json")
      ? name.slice(0, -".json".length)
      : null;
    if (deviceId === null) continue;
    const records = recordsByDevice.get(deviceId);
    if (records) records.push(name);
    else recordsByDevice.set(deviceId, [name]);
  }
  for (const [deviceId, names] of recordsByDevice) {
    if (inSurviving.has(deviceId)) {
      summary.devices.kept++;
      continue;
    }
    try {
      if (dryRun) {
        for (const name of names) log(`[dry-run] would collect device record devices/${name}`);
        summary.devices.deleted++;
        continue;
      }
      for (const name of names) await deleteObject(f, config, `devices/${name}`);
      summary.devices.deleted++;
    } catch (err) {
      summary.errors++;
      log(`device ${deviceId} record collection failed (continuing): ${err}`);
    }
  }

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

    // Notify goes THROUGH the edge (it holds the APNs key; the sweep does not): POST the notify route on
    // the device-facing host with the ADMIN_NOTIFY_KEY. Best-effort — a failed notify never blocks a delete.
    const notify = async (eventId: string): Promise<void> => {
      try {
        const res = await fetch(`https://${config.linkDomain}/events/${eventId}/notify`, {
          method: "POST",
          headers: { authorization: `Bearer ${config.adminKey}` },
        });
        await res.body?.cancel();
        if (!res.ok) console.warn(`notify ${eventId} → ${res.status} (continuing)`);
      } catch (e) {
        console.warn(`notify ${eventId} failed (continuing): ${e}`);
      }
    };

    const summary = await runSweep({
      fetch: (url, init) => fetch(url, init),
      config,
      now: Date.now,
      dryRun,
      notify,
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
