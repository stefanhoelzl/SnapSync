// The log tool (`src/scripts/logs.ts`): its options, its filters over a real store, the route it derives
// from a URL, and its statistics.

import { assertEquals, assertThrows } from "@std/assert";
import type { RequestRecord } from "../../src/request-log.ts";
import { insertRequestRecord } from "../../src/request-log-store.ts";
import {
  formatStats,
  instant,
  parseOptions,
  percentile,
  readRecords,
  routeOf,
  stats,
} from "../../src/scripts/logs.ts";
import { store } from "../support/harness.ts";

const NOW = Date.parse("2026-10-07T12:00:00Z");
const EV = "7a3f9c21-0000-4000-8000-000000000001";
const DEV = "1c1c1c1c-0000-4000-8000-000000000002";

function record(reqid: string, overrides: Partial<RequestRecord> = {}): RequestRecord {
  return {
    at: new Date("2026-10-07T10:00:00Z"),
    reqid,
    method: "GET",
    url: `https://snapsync.app/api/v2/events/${EV}/files?cursor=3`,
    status: 200,
    ms: 10,
    version: "0.14",
    bytesIn: undefined,
    bytesOut: 100,
    fields: {},
    errors: [],
    ...overrides,
  };
}

async function seeded() {
  const db = await store();
  for (
    const r of [
      record("old001", { at: new Date("2026-10-05T10:00:00Z") }),
      record("get001", { ms: 5 }),
      record("get002", { ms: 50, version: "0.13" }),
      record("put001", {
        at: new Date("2026-10-07T11:00:00Z"),
        method: "PUT",
        url:
          `https://snapsync.app/api/v2/events/${EV}/files/devices/${DEV}/IMG_0001/primary?filename=a.heic`,
        status: 201,
        ms: 900,
        bytesIn: 4096,
        bytesOut: 0,
        fields: { completed: 1, fanout: "notify: Error: timed out" },
        errors: ["fanout"],
      }),
      record("bad001", {
        at: new Date("2026-10-07T11:30:00Z"),
        status: 502,
        fields: { upstream: "store: down" },
        errors: ["upstream"],
      }),
      record("hlt001", { url: "https://snapsync.app/health", status: 404 }),
    ]
  ) await insertRequestRecord(db, r);
  return db;
}

const ids = (rs: { reqid: string }[]) => rs.map((r) => r.reqid);
const query = (args: string[]) => parseOptions(args, NOW).query;

Deno.test("options → durations, dates and instants; unknown options, tags and stats are refused", () => {
  assertEquals(instant("2h", NOW), "2026-10-07T10:00:00.000Z");
  assertEquals(instant("7d", NOW), "2026-09-30T12:00:00.000Z");
  assertEquals(instant("2026-10-06", NOW), "2026-10-06T00:00:00.000Z");
  assertEquals(query([]).since, "2026-10-06T12:00:00.000Z");
  assertEquals(parseOptions(["--stats=route", "--limit", "5"], NOW).stats, "route");
  assertThrows(() => parseOptions(["--frobnicate"], NOW));
  assertThrows(() => parseOptions(["--error", "fan-out"], NOW));
  assertThrows(() => parseOptions(["--stats", "weekday"], NOW));
  assertThrows(() => parseOptions(["--status", "5x"], NOW));
  assertThrows(() => parseOptions(["--since", "yesterday"], NOW));
});

Deno.test("filters → time, status class and code, method, path, ids, reqid, version, tags, speed", async () => {
  const db = await seeded();
  const read = async (args: string[]) => ids(await readRecords(db, query(args)));
  assertEquals(await read([]), ["get001", "get002", "hlt001", "put001", "bad001"]);
  assertEquals(await read(["--since", "3d"]), [
    "old001",
    "get001",
    "get002",
    "hlt001",
    "put001",
    "bad001",
  ]);
  assertEquals(await read(["--until", "2026-10-07T10:30:00Z"]), ["get001", "get002", "hlt001"]);
  assertEquals(await read(["--status", "5xx"]), ["bad001"]);
  assertEquals(await read(["--status", "201"]), ["put001"]);
  assertEquals(await read(["--method", "put"]), ["put001"]);
  assertEquals(await read(["--path", "/health"]), ["hlt001"]);
  assertEquals(await read(["--device", DEV]), ["put001"]);
  assertEquals(await read(["--event", EV, "--status", "2xx"]), ["get001", "get002", "put001"]);
  assertEquals(await read(["--reqid", "get002"]), ["get002"]);
  assertEquals(await read(["--v", "0.13"]), ["get002"]);
  assertEquals(await read(["--error", "fanout"]), ["put001"]);
  assertEquals(await read(["--errors"]), ["put001", "bad001"]);
  assertEquals(await read(["--slow", "50"]), ["get002", "put001"]);
  assertEquals(await read(["--route", "/api/v2/events/:id/files"]), ["get001", "get002", "bad001"]);
  db.close();
});

Deno.test("lines → the newest `limit`, printed oldest first", async () => {
  const db = await seeded();
  assertEquals(ids(await readRecords(db, query([]), 2)), ["put001", "bad001"]);
  db.close();
});

Deno.test("routeOf → ids become :id, the byte routes' asset and role :asset/:role", () => {
  assertEquals(routeOf(`https://x/api/v2/events/${EV}/files?cursor=1`), "/api/v2/events/:id/files");
  assertEquals(
    routeOf(`https://x/api/v2/events/${EV}/files/devices/${DEV}/IMG_1/live?filename=a`),
    "/api/v2/events/:id/files/devices/:id/:asset/:role",
  );
  assertEquals(
    routeOf(`https://x/api/v2/events/${EV}/files/devices/${DEV}`),
    "/api/v2/events/:id/files/devices/:id",
  );
  assertEquals(routeOf(`https://x/join/${EV}`), "/join/:id");
  assertEquals(routeOf("https://x/_astro/index.Bx1.css"), "/_astro/*");
  assertEquals(routeOf("https://x/"), "/");
});

Deno.test("stats → per group: count, status classes, latency percentiles, bytes; errors name their routes", async () => {
  const db = await seeded();
  const records = await readRecords(db, query([]));
  const byRoute = stats(records, "route");
  assertEquals(byRoute[0], {
    key: "GET /api/v2/events/:id/files",
    count: 3,
    ok: 2,
    clientErrors: 0,
    serverErrors: 1,
    p50: 10,
    p95: 50,
    max: 50,
    bytesIn: 0,
    bytesOut: 300,
  });
  assertEquals(stats(records, "hour").map((r) => [r.key, r.count]), [
    ["2026-10-07T10:00", 3],
    ["2026-10-07T11:00", 2],
  ]);
  const byError = stats(records, "error");
  assertEquals(byError.map((r) => [r.key, r.routes]), [
    ["fanout", ["PUT /api/v2/events/:id/files/devices/:id/:asset/:role (1)"]],
    ["upstream", ["GET /api/v2/events/:id/files (1)"]],
  ]);
  assertEquals(stats(records, "version").map((r) => [r.key, r.count]), [["0.14", 4], ["0.13", 1]]);
  const table = formatStats(byRoute, "route");
  assertEquals(table.split("\n")[0].trim().split(/\s+/), [
    "route",
    "count",
    "2xx/3xx",
    "4xx",
    "5xx",
    "p50ms",
    "p95ms",
    "maxms",
    "in",
    "out",
  ]);
  db.close();
});

Deno.test("percentile → nearest rank", () => {
  assertEquals(percentile([], 50), 0);
  assertEquals(percentile([1, 2, 3, 4], 50), 2);
  assertEquals(percentile([1, 2, 3, 4], 95), 4);
  assertEquals(percentile([7], 95), 7);
});
