// deno-lint-ignore-file no-console -- a command-line tool, never part of the edge script; its console is its interface.
// The nightly cleanup sweep. Runs OUT of the Edge Script — Bunny has no
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
//     (`max(createdAt, openUntil) + lifetimeSeconds` — the GUARANTEE): one `DELETE`, the cascade takes
//     memberships, assets and resources. COMPLETE, only for an event someone joined, once it is FINISHED
//     (past its open-until date, every member still in it has shared and received everything), EMPTY
//     (nobody still present; dependable now that devices retry a leave until it lands) or past the CLOCK
//     (`max(openUntil, lastJoinedAt, lastLandedAt) + 3 days`): memberships, assets and resources go, the
//     row stays until DROP so a device still joined is told "completed". No notification is sent — see the
//     delete site for why.
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

import { BUILD_SHA, DEPLOYMENT, readSweepConfig } from "../config.ts";
import { libsqlDb } from "../db-libsql.ts";
import { deleteObject, eventDir, type FetchLike, listDir } from "../storage.ts";
import { AwsClient } from "aws4fetch";
import { s3Client } from "../s3.ts";
import {
  collectableDevices,
  completeEvent,
  type Db,
  deleteDevice,
  deleteEvent,
  eventsWithCounts,
  liveEventIds,
} from "../db.ts";
import { canonicalFromMs, validateUUID } from "../validators.ts";
import { sweepVerdict } from "../lifecycle.ts";
import type { Config } from "../config.ts";
import { newReqid } from "../request-log.ts";
import { insertRequestRecord } from "../request-log-store.ts";
import {
  noSender,
  type ReportOutcome,
  type ReportSender,
  runRequestLogPhase,
  senderFor,
} from "./request-log-report.ts";

/**
 * What a run's caller needs to know beyond its log: only whether the day's 5xx report left, since a report
 * that did not fails the run. The run keeps no summary — what it completed and deleted is in the request log,
 * one record per event (`SWEEP`; decision record `changes/delivery-record`, D9), and its progress in its log.
 */
export type SweepOutcome = ReportOutcome;

/** The request-log method of the record the sweep leaves for each event it completes or deletes. */
export const SWEEP_METHOD = "SWEEP";

/**
 * The delivery record of what the sweep did to one event — written in the same transaction as the change, so the
 * record and the change commit together (`docs/architecture.md`, "The delivery record").
 */
async function recordOutcome(
  tx: Db,
  eventId: string,
  outcome: "completed" | "deleted",
  at: number,
) {
  await insertRequestRecord(tx, {
    at: new Date(at),
    reqid: newReqid(),
    method: SWEEP_METHOD,
    url: `sweep:/events/${eventId}`,
    status: 200,
    ms: 0,
    version: undefined,
    bytesIn: undefined,
    bytesOut: 0,
    fields: { event: eventId, outcome },
    errors: [],
  });
}

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
  /** The UTC day whose request log is counted and reported (`YYYY-MM-DD`); the day before `now` by default. */
  day?: string;
  /** Where a day's 5xx report goes. Defaults to one that cannot send, so a day with 5xx fails the run. */
  sendReport?: ReportSender;
};

/**
 * Abort every open multipart upload (the resumable byte route's, `s3.ts`) of an event that no longer lives —
 * BEFORE its folder goes, because the folder's recursive delete leaves an open upload behind and completing it
 * afterwards would put its object back (measured). Listed from the zone rather than per folder: an upload that
 * never stored a whole file has no folder entry to find it by. A failure is counted and the run continues.
 */
async function abortOpenUploads(
  { fetch: f, config, dryRun, log = console.log }: SweepDeps,
  lives: (eventId: string) => boolean,
): Promise<void> {
  const aws = new AwsClient({
    accessKeyId: config.zone,
    secretAccessKey: config.accessKey,
    region: config.s3Region,
    service: "s3",
  });
  const s3 = s3Client(f, config, aws);
  try {
    for (const u of await s3.uploadsUnder("files/")) {
      const eventId = u.key.split("/")[1] ?? "";
      if (!validateUUID(eventId) || lives(eventId)) continue;
      if (dryRun) {
        log(`[dry-run] would abort the open upload of ${u.key}`);
      } else {
        await s3.abort(u.key, u.uploadId);
        log(`aborted the open upload of ${u.key}`);
      }
    }
  } catch (e) {
    log(`open uploads could not be aborted (continuing): ${e}`);
  }
}

/**
 * Run the two-phase sweep. THROWS only on a SYSTEMIC failure (cannot list the top-level `files/`
 * directory — an auth failure surfaces here). Per-event failures are logged and never abort the run (deletes
 * are idempotent, and the next night retries them).
 */
export async function runSweep(deps: SweepDeps): Promise<SweepOutcome> {
  const { fetch: f, config, db, now, dryRun } = deps;
  const log = deps.log ?? console.log;

  // ── EVENT PHASE ─────────────────────────────────────────────────────────────────────────────────
  // One read gives every event and its membership counts — the whole input to the staleness decision.
  // The decision AND the deletes run inside an interactive transaction, which executes against the
  // primary: see the header for why a possibly-stale replica read must not decide a deletion.
  const stale: string[] = [];
  await db.transaction(async (tx) => {
    const at = now();
    for (const counts of await eventsWithCounts(tx, canonicalFromMs(at))) {
      const { event, total, active } = counts;
      const verdict = sweepVerdict(event, counts, at);
      if (verdict === "keep") continue;
      // A completed event keeps its row but no longer lives: the byte phase below deletes its folder
      // exactly like a deleted one's (in a dry run nothing was deleted, and the id set stands in for that).
      stale.push(event.eventId);
      if (verdict === "complete") {
        if (dryRun) {
          log(`[dry-run] would complete event ${event.eventId} (${active}/${total} active)`);
        } else {
          // No notification, for the same reason as a delete below: a device still joined learns the
          // event completed from its own next read, and shows it as over.
          await completeEvent(tx, event.eventId, canonicalFromMs(now()));
          await recordOutcome(tx, event.eventId, "completed", now());
          log(`completed event ${event.eventId} (${active}/${total} active)`);
        }
        continue;
      }
      if (dryRun) {
        log(`[dry-run] would delete event ${event.eventId} (${total} membership(s))`);
        continue;
      }
      // No notification is sent: the notify channel carries a semantic-free "something changed, go sync"
      // payload, which is the OPPOSITE of what a deletion means, and it would have to be dispatched
      // milliseconds before the deletes it announces — so the device wakes to an already-deleted event
      // and burns a scarce wake syncing against a corpse. Members discover the deletion on their own next
      // foreground details fetch, the only context where acting on it is safe.
      await deleteEvent(tx, event.eventId);
      await recordOutcome(tx, event.eventId, "deleted", now());
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
  const lives = (eventId: string) => live.has(eventId) && !staleIds.has(eventId);
  await abortOpenUploads(deps, lives);
  for (const entry of top.filter((e) => e.IsDirectory)) {
    const eventId = entry.ObjectName;
    if (!validateUUID(eventId)) continue; // `devices`, and anything this sweep did not write
    if (lives(eventId)) continue;
    try {
      if (dryRun) {
        log(`[dry-run] would delete ${eventDir(eventId)}`);
      } else {
        await deleteObject(f, config, eventDir(eventId));
        log(`deleted ${eventDir(eventId)}`);
      }
    } catch (e) {
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
  for (const deviceId of await collectableDevices(db, new Date(now()).toISOString(), staleIds)) {
    try {
      if (dryRun) {
        log(`[dry-run] would collect the device record for ${deviceId}`);
        continue;
      }
      await deleteDevice(db, deviceId);
      log(`collected the device record for ${deviceId}`);
    } catch (err) {
      log(`device ${deviceId} record collection failed (continuing): ${err}`);
    }
  }

  // ── REQUEST-LOG PHASE (`request-log-report.ts`) ────────────────────────────────────────────────────
  // Last, and independent of the phases above: a store that cannot be read here loses this night's report,
  // never the run's deletions. A report that did not leave is the outcome, and the entry point fails the run
  // on it.
  try {
    return await runRequestLogPhase({
      db,
      now,
      dryRun,
      day: deps.day,
      send: deps.sendReport ?? noSender,
      log,
    });
  } catch (err) {
    log(`request log phase failed (continuing): ${err}`);
    return {};
  }
}

// ── Entry point (GitHub Actions) ────────────────────────────────────────────────────────────────────
if (import.meta.main) {
  try {
    // Config first (a missing secret is a systemic failure), then the run. Both exit 1 loudly.
    const config = readSweepConfig(Deno.env.toObject());
    const dryRun = Deno.args.includes("--dry-run");
    const day = Deno.args.find((a) => a.startsWith("--date="))?.slice("--date=".length) ||
      undefined;

    const outcome = await runSweep({
      fetch: (url, init) => fetch(url, init),
      config,
      db: libsqlDb(config.databaseUrl, config.databaseToken),
      now: Date.now,
      dryRun,
      log: console.log,
      day,
      // The DSN is the deployment's (rendered when the resolve step had SENTRY_DSN), as the edge's is.
      sendReport: senderFor(DEPLOYMENT.sentryDsn, BUILD_SHA),
    });
    // The 5xx report is the alert: one that did not leave fails the run.
    if (outcome.sendFailed !== undefined) {
      console.error(`sweep: the 5xx report did not leave — ${outcome.sendFailed}`);
      Deno.exit(1);
    }
  } catch (e) {
    console.error(`sweep: systemic failure — ${e}`);
    Deno.exit(1);
  }
}
