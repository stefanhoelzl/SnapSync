// The nightly sweep's request-log phase (`src/scripts/request-log-report.ts`): the 30-day age limit, the
// previous UTC day's counts, and its 5xx report — every row up to a byte budget, never sent by a dry run,
// and a send that fails is the run's outcome, so the run fails.

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

/** The reqids the store still holds, oldest first. */
async function reqids(db: Awaited<ReturnType<typeof store>>): Promise<string[]> {
  return (await db.execute(`SELECT reqid FROM request_log ORDER BY at`)).rows.map((r) =>
    String(r.reqid)
  );
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

// @Verifies(spec = "privacy-security", requirement = "The service records how photos are delivered", scenario = "The record outlives the event's photos")
Deno.test("the phase → ages rows out, counts the day before, reports its 5xx", async () => {
  const db = await seeded();
  const sent: ServerErrorReport[] = [];
  const outcome = await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: false,
    send: (r) => Promise.resolve(void sent.push(r)),
    log: () => {},
  });
  assertEquals(
    await reqids(db),
    ["day001", "day002", "day003", "day004", "now001"],
    "the row past the limit aged out",
  );
  assertEquals(sent.length, 1);
  assertEquals(sent[0].day, "2026-10-06");
  assertEquals([sent[0].total, sent[0].serverErrors], [4, 2]);
  assertEquals(sent[0].lines.map((l) => l.match(/\[(\w+)\]/)![1]), ["day004", "day003"]);
  assertEquals(outcome, {});
  db.close();
});

Deno.test("the phase → --date picks the day; a day with no 5xx sends nothing", async () => {
  const db = await seeded();
  let sends = 0;
  await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: false,
    day: "2026-10-05",
    send: () => Promise.resolve(void sends++),
    log: () => {},
  });
  assertEquals(sends, 0);
  db.close();
});

Deno.test("the phase → a dry run deletes and sends nothing, but says what it would", async () => {
  const db = await seeded();
  const logged: string[] = [];
  await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: true,
    send: () => Promise.reject(new Error("a dry run must not send")),
    log: (m) => logged.push(m),
  });
  assertEquals((await reqids(db)).length, 6, "a dry run ages nothing out");
  assert(logged.some((m) => m.startsWith("[dry-run] would delete 1 request log row(s)")));
  assert(logged.some((m) => m.startsWith("[dry-run] would report 2 5xx of 2026-10-06")));
  assert(logged.some((m) => m.includes("[day004]")));
  db.close();
});

Deno.test("the phase → a report that did not leave is the outcome, which the run fails on", async () => {
  const db = await seeded();
  const outcome = await runRequestLogPhase({
    db,
    now: () => RUN_AT,
    dryRun: false,
    send: () => Promise.reject(new Error("bugsink 503")),
    log: () => {},
  });
  assertEquals(outcome, { sendFailed: "bugsink 503" });
  db.close();
});
