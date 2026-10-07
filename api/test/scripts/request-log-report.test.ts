// The nightly sweep's request-log phase (`src/scripts/request-log-report.ts`): the 30-day age limit, the
// previous UTC day's counts, and its 5xx report — every row up to a byte budget, never sent by a dry run,
// and a send that fails said on the summary so the run fails.

import { assert, assertEquals, assertThrows } from "@std/assert";
import type { RequestRecord } from "../../src/request-log.ts";
import { insertRequestRecord, type StoredRecord } from "../../src/request-log-store.ts";
import {
  dayWindow,
  previousUtcDay,
  runRequestLogPhase,
  type ServerErrorReport,
  serverErrorReport,
} from "../../src/scripts/request-log-report.ts";
import { formatSummary, markdownSummary, type SweepSummary } from "../../src/scripts/sweep.ts";
import { store } from "../support/harness.ts";

const RUN_AT = Date.parse("2026-10-07T03:17:00Z");

function record(reqid: string, at: string, status: number): RequestRecord {
  return {
    at: new Date(at),
    reqid,
    method: "GET",
    url: "https://x/api/v2/events/e/files",
    status,
    ms: 10,
    version: "0.14",
    bytesIn: undefined,
    bytesOut: 0,
    fields: status >= 500 ? { upstream: "store: Error: down" } : {},
    errors: status >= 500 ? ["upstream"] : [],
  };
}

/** A store holding: one row past the age limit, the day before the run, and the run's own day. */
async function seeded() {
  const db = await store();
  for (
    const r of [
      record("old001", "2026-09-06T00:00:00Z", 200),
      record("day001", "2026-10-06T00:00:00Z", 200),
      record("day002", "2026-10-06T08:00:00Z", 404),
      record("day003", "2026-10-06T09:00:00Z", 502),
      record("day004", "2026-10-06T23:59:59Z", 500),
      record("now001", "2026-10-07T01:00:00Z", 503),
    ]
  ) await insertRequestRecord(db, r);
  return db;
}

Deno.test("the window → the UTC day before the run, [00:00, next 00:00)", () => {
  assertEquals(previousUtcDay(RUN_AT), "2026-10-06");
  assertEquals(dayWindow("2026-10-06"), {
    from: "2026-10-06T00:00:00.000Z",
    to: "2026-10-07T00:00:00.000Z",
  });
  assertThrows(() => dayWindow("06.10.2026"));
  assertThrows(() => dayWindow("2026-13-40"));
});

Deno.test("the report → none for a day without 5xx; otherwise every row newest first, up to the budget", () => {
  const counts = { total: 9, clientErrors: 0, serverErrors: 3, withErrors: 3 };
  assertEquals(serverErrorReport("2026-10-06", { ...counts, serverErrors: 0 }, []), null);
  const rows: StoredRecord[] = ["c", "b", "a"].map((id, i) => ({
    ...record(`r0000${id}`, `2026-10-06T0${3 - i}:00:00Z`, 502),
    id: i,
  }));
  const all = serverErrorReport("2026-10-06", counts, rows)!;
  assertEquals(all.lines.length, 3);
  assert(all.lines[0].includes("[r0000c]"));
  assertEquals(all.omitted, 0);
  const one = new TextEncoder().encode(all.lines[0]).byteLength + 4;
  const cut = serverErrorReport("2026-10-06", counts, rows, one * 2)!;
  assertEquals(cut.lines.length, 2);
  assertEquals(cut.omitted, 1);
});

Deno.test("the phase → ages rows out, counts the day before, reports its 5xx", async () => {
  const db = await seeded();
  const sent: ServerErrorReport[] = [];
  const summary = await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: false,
    send: (r) => Promise.resolve(void sent.push(r)),
    log: () => {},
  });
  assertEquals(summary.day, "2026-10-06");
  assertEquals(summary.deleted, 1);
  assertEquals(summary.counts, { total: 4, clientErrors: 1, serverErrors: 2, withErrors: 2 });
  assertEquals(summary.size.rows, 5);
  assertEquals(summary.reported, 2);
  assertEquals(sent.length, 1);
  assertEquals(sent[0].lines.map((l) => l.match(/\[(\w+)\]/)![1]), ["day004", "day003"]);
  assertEquals(summary.sendFailed, undefined);
  db.close();
});

Deno.test("the phase → --date picks the day; a day with no 5xx sends nothing", async () => {
  const db = await seeded();
  let sends = 0;
  const summary = await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: false,
    day: "2026-10-05",
    send: () => Promise.resolve(void sends++),
    log: () => {},
  });
  assertEquals(summary.counts.total, 0);
  assertEquals(sends, 0);
  db.close();
});

Deno.test("the phase → a dry run deletes and sends nothing, but says what it would", async () => {
  const db = await seeded();
  const logged: string[] = [];
  const summary = await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: true,
    send: () => Promise.reject(new Error("a dry run must not send")),
    log: (m) => logged.push(m),
  });
  assertEquals(summary.deleted, 1);
  assertEquals(summary.size.rows, 6);
  assert(logged.some((m) => m.startsWith("[dry-run] would report 2 5xx of 2026-10-06")));
  assert(logged.some((m) => m.includes("[day004]")));
  db.close();
});

Deno.test("the phase → a report that did not leave is set on the summary, which the run fails on", async () => {
  const db = await seeded();
  const summary = await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: false,
    send: () => Promise.reject(new Error("bugsink 503")),
    log: () => {},
  });
  assertEquals(summary.sendFailed, "bugsink 503");
  db.close();
});

Deno.test("the summaries → the day's counts, the report and the table's size", () => {
  const s: SweepSummary = {
    events: { deleted: 0, completed: 0, kept: 0 },
    devices: { deleted: 0, kept: 0 },
    versions: [],
    files: { deleted: { count: 0, bytes: 0 }, kept: { count: 0, bytes: 0 } },
    dirs: { deleted: 0, kept: 0 },
    errors: 0,
    dryRun: false,
    requests: {
      day: "2026-10-06",
      counts: { total: 480, clientErrors: 12, serverErrors: 3, withErrors: 5 },
      deleted: 410,
      size: { rows: 14_000, bytes: 7 * 1024 * 1024 },
      reported: 3,
      omitted: 0,
    },
  };
  const text = formatSummary(s);
  assert(text.includes("requests 2026-10-06"), text);
  assert(text.includes("480 total   12 4xx   3 5xx   5 with errors"), text);
  assert(text.includes("5xx report: 3 rows"), text);
  assert(text.includes("410 rows aged out   14000 rows kept (~7.0 MB)"), text);
  const md = markdownSummary(s);
  assert(md.includes("### Requests on 2026-10-06"), md);
  assert(md.includes("| 480 | 12 | 3 | 5 |"), md);
  const failed = formatSummary({ ...s, requests: { ...s.requests!, sendFailed: "timeout" } });
  assert(failed.includes("5xx report: NOT SENT — timeout"), failed);
});
