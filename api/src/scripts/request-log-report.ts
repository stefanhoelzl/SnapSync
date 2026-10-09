// The nightly sweep's REQUEST-LOG phase (`docs/deployment.md`, "Nightly cleanup"; migration 0012): the
// kept request log is aged out, the previous UTC day is counted for the run's summary, and that day's 5xx
// rows are reported to the error tracker — the Bugsink project the apps and the edge already report to.
//
// ANY 5xx IS REPORTED. A day's report carries every 5xx row as its console line, newest first, cut at a byte
// budget so the event stays well under Bugsink's 1 MiB event cap (CLAUDE.md, "Logging & errors": an
// oversized event is not dropped but cached and resent, blocking every report behind it); what the budget
// cut is counted in `omitted`, and the table keeps every row for 30 days. Every report carries ONE fixed
// fingerprint and ONE fixed message, so a run of bad days is one issue: Bugsink alerts on its first event,
// and again on the first one after the issue is resolved.
//
// THE WINDOW IS A CALENDAR DAY, `[D 00:00, D+1 00:00)` UTC — by default the day before the run, or `--date`.
// A late cron run checks the same day; a rerun reports the same rows into the same issue; a skipped day is
// reported by dispatching with its date.
//
// A FAILED SEND FAILS THE RUN: the report is the alert, so it must not vanish into a green run.

import * as Sentry from "@sentry/deno";
import type { Db } from "../db.ts";
import { formatLine } from "../request-log.ts";
import {
  deleteRequestLogBefore,
  REQUEST_LOG_RETENTION_DAYS,
  type RequestCounts,
  requestCounts,
  requestLogSize,
  serverErrorRecords,
  type StoredRecord,
} from "../request-log-store.ts";

const DAY_MS = 86_400_000;

/** What a report may carry in lines, in bytes: half of Bugsink's 1 MiB event cap, the rest headroom. */
export const REPORT_BUDGET_BYTES = 512 * 1024;

/** The one fingerprint every 5xx report shares, so a run of bad days stays one issue. */
export const REPORT_FINGERPRINT = "api-5xx-elevated";

/**
 * The one message every 5xx report carries — never the day or its counts, which ride as the `day` tag and the
 * extras. The message is the issue's title and what Bugsink groups by, so a message that varied by day made
 * every bad day an issue of its own despite the fixed fingerprint.
 */
export const REPORT_TITLE = "api: requests answered 5xx";

/** One day's 5xx, as reported. */
export type ServerErrorReport = {
  /** The UTC day, `YYYY-MM-DD`. */
  day: string;
  total: number;
  serverErrors: number;
  /** The 5xx rows as console lines, newest first, as many as the budget holds. */
  lines: string[];
  /** The 5xx rows the budget cut. */
  omitted: number;
};

/** Sends a report; throws when it did not leave. */
export type ReportSender = (report: ServerErrorReport) => Promise<void>;

/** What the phase did — the request-log rows of the run's summary. */
export type RequestLogSummary = {
  day: string;
  counts: RequestCounts;
  /** Rows older than the retention limit: deleted, or in a dry run would be. */
  deleted: number;
  size: { rows: number; bytes: number };
  /** The day's 5xx rows the report carried (0 when the day had none). */
  reported: number;
  omitted: number;
  /** Why the report did not leave — set only when sending failed, which fails the run. */
  sendFailed?: string;
};

/** The UTC day before `now`, `YYYY-MM-DD`. */
export function previousUtcDay(now: number): string {
  return new Date(now - DAY_MS).toISOString().slice(0, 10);
}

/** `[day 00:00, next day 00:00)` UTC, as ISO instants — the form the table's `at` is written in. */
export function dayWindow(day: string): { from: string; to: string } {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || Number.isNaN(Date.parse(`${day}T00:00:00Z`))) {
    throw new Error(`not a day: ${day} (expected YYYY-MM-DD)`);
  }
  const from = new Date(`${day}T00:00:00Z`);
  return { from: from.toISOString(), to: new Date(from.getTime() + DAY_MS).toISOString() };
}

/** The day's report — `null` for a day with no 5xx. */
export function serverErrorReport(
  day: string,
  counts: RequestCounts,
  records: readonly StoredRecord[],
  budget = REPORT_BUDGET_BYTES,
): ServerErrorReport | null {
  if (records.length === 0) return null;
  const lines: string[] = [];
  let used = 0;
  for (const r of records) {
    const line = formatLine(r);
    const cost = new TextEncoder().encode(line).byteLength + 4; // the quotes and comma JSON adds
    if (used + cost > budget) break;
    lines.push(line);
    used += cost;
  }
  return {
    day,
    total: counts.total,
    serverErrors: counts.serverErrors,
    lines,
    omitted: records.length - lines.length,
  };
}

export type RequestLogPhaseDeps = {
  db: Db;
  now: () => number;
  dryRun: boolean;
  /** The day to count and report; the day before `now` by default. */
  day?: string;
  /** Where a report goes; a dry run never calls it. */
  send: ReportSender;
  log: (msg: string) => void;
};

/** Age the table out, count the day, report its 5xx. Throws only when the store cannot be read. */
export async function runRequestLogPhase(deps: RequestLogPhaseDeps): Promise<RequestLogSummary> {
  const { db, now, dryRun, log } = deps;
  const day = deps.day ?? previousUtcDay(now());
  const { from, to } = dayWindow(day);
  const cutoff = new Date(now() - REQUEST_LOG_RETENTION_DAYS * DAY_MS).toISOString();

  const deleted = dryRun
    ? (await requestCounts(db, "", cutoff)).total
    : await deleteRequestLogBefore(db, cutoff);
  log(
    `${
      dryRun ? "[dry-run] would delete" : "deleted"
    } ${deleted} request log row(s) before ${cutoff}`,
  );

  const counts = await requestCounts(db, from, to);
  const report = serverErrorReport(day, counts, await serverErrorRecords(db, from, to));
  const summary: RequestLogSummary = {
    day,
    counts,
    deleted,
    size: await requestLogSize(db),
    reported: report?.lines.length ?? 0,
    omitted: report?.omitted ?? 0,
  };
  if (report === null) return summary;
  if (dryRun) {
    log(`[dry-run] would report ${counts.serverErrors} 5xx of ${day}:`);
    for (const line of report.lines) log(`  ${line}`);
    return summary;
  }
  try {
    await deps.send(report);
    log(`reported ${counts.serverErrors} 5xx of ${day} (${report.omitted} omitted by the budget)`);
  } catch (e) {
    summary.sendFailed = e instanceof Error ? e.message : String(e);
    log(`the 5xx report of ${day} did not leave: ${summary.sendFailed}`);
  }
  return summary;
}

/** How long the sweep waits for its one report to leave. */
const FLUSH_MS = 15_000;

/**
 * The deployed sender: one Bugsink event through `@sentry/deno`, level error, tagged `platform=api` like the
 * edge's own reports. The limits are raised because the SDK otherwise truncates a string past 250
 * characters and an object past depth 3 — the lines ARE the report.
 */
export function bugsinkSender(dsn: string, release: string): ReportSender {
  Sentry.init({
    dsn,
    release,
    environment: "production",
    defaultIntegrations: false,
    maxValueLength: 10_000,
    normalizeDepth: 5,
    initialScope: { tags: { platform: "api", source: "nightly-sweep" } },
  });
  return async (report) => {
    Sentry.withScope((scope) => {
      scope.setFingerprint([REPORT_FINGERPRINT]);
      scope.setLevel("error");
      scope.setTag("day", report.day);
      scope.setExtras({
        day: report.day,
        requests: report.total,
        serverErrors: report.serverErrors,
        omitted: report.omitted,
        lines: report.lines,
      });
      Sentry.captureMessage(REPORT_TITLE);
    });
    if (!(await Sentry.flush(FLUSH_MS))) throw new Error(`not sent within ${FLUSH_MS} ms`);
  };
}

/** The sender of a run with no DSN: it cannot send, so the run fails rather than lose the report. */
export const noSender: ReportSender = () =>
  Promise.reject(
    new Error("no DSN in this deployment's rendering — resolve it with SENTRY_DSN set"),
  );
