// The request log, kept (`src/request-log-store.ts`, migration 0012): one row per request beside its console
// line, a refused row said and never fatal, the response's end waiting for the row, and the reads the
// nightly sweep and `deno task logs` make.

import { assert, assertEquals, assertMatch, assertRejects } from "@std/assert";
import { Hono } from "hono";
import { errorHandler, requestLogMiddleware, type RequestRecord } from "../src/request-log.ts";
import {
  deleteRequestLogBefore,
  fromRow,
  insertRequestRecord,
  requestCounts,
  requestLogSize,
  serverErrorRecords,
  storeSink,
} from "../src/request-log-store.ts";
import { CONFIG, createRealApp, E, recorder, rows, store, V2 } from "./support/harness.ts";

/** A record as the middleware would hand it over, `overrides` applied. */
function record(overrides: Partial<RequestRecord> = {}): RequestRecord {
  return {
    at: new Date("2026-10-07T09:14:02.311Z"),
    reqid: "a91f3c",
    method: "PUT",
    url: `https://x/api/v2/events/${E}/files`,
    status: 201,
    ms: 842,
    version: "0.14",
    bytesIn: 512,
    bytesOut: 0,
    fields: {},
    errors: [],
    ...overrides,
  };
}

Deno.test("storeSink → the line first, then one row holding the record's facts", async () => {
  const db = await store();
  const lines: string[] = [];
  await storeSink(db, (l) => lines.push(l))(record({
    fields: { completed: 1, recipients: [3, 2], fanout: "notify: Error: timed out" },
    errors: ["fanout"],
  }));
  assertEquals(lines.length, 1);
  assertMatch(
    lines[0],
    /^2026-10-07T09:14:02.311Z \[a91f3c\] PUT .* 201 842ms v=0.14 in=512 out=0 /,
  );
  const [row] = await rows(db, "SELECT * FROM request_log");
  assertEquals(
    row.fields,
    '{"completed":1,"recipients":[3,2],"fanout":"notify: Error: timed out"}',
  );
  assertEquals(row.errors, "fanout");
  assertEquals(row.version, "0.14");
  const back = fromRow(row);
  assertEquals(back.fields.recipients, [3, 2]);
  assertEquals(back.errors, ["fanout"]);
  db.close();
});

Deno.test("storeSink → a request with no fault keeps errors NULL, several are space-joined", async () => {
  const db = await store();
  const sink = storeSink(db, () => {});
  await sink(record({ reqid: "000001" }));
  await sink(record({ reqid: "000002", errors: ["completion-lookup", "fanout"] }));
  const got = await rows(db, "SELECT reqid, errors FROM request_log ORDER BY reqid");
  assertEquals(got.map((r) => r.errors), [null, "completion-lookup fanout"]);
  const tagged = await rows(
    db,
    `SELECT reqid FROM request_log WHERE ' ' || errors || ' ' LIKE '% fanout %'`,
  );
  assertEquals(tagged.map((r) => r.reqid), ["000002"]);
  db.close();
});

Deno.test("storeSink → a row the store refuses is said on a second line and never thrown", async () => {
  const lines: string[] = [];
  const down = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
  };
  await storeSink(down, (l) => lines.push(l))(record());
  assertEquals(lines.length, 2);
  assertMatch(lines[1], /^\S+Z \[a91f3c\] logdb=failed detail="Error: store down"$/);
});

Deno.test("the table refuses fields that are not one JSON object, and an empty errors", async () => {
  const db = await store();
  await assertRejects(async () =>
    await db.execute(
      `INSERT INTO request_log (at, reqid, method, url, status, ms, bytes_out, fields)
       VALUES ('t', 'r', 'GET', 'u', 200, 1, 0, '[1]')`,
    )
  );
  await assertRejects(async () =>
    await db.execute(
      `INSERT INTO request_log (at, reqid, method, url, status, ms, bytes_out, fields, errors)
       VALUES ('t', 'r', 'GET', 'u', 200, 1, 0, '{}', '')`,
    )
  );
  db.close();
});

Deno.test("middleware → the response's end waits for the sink's write", async () => {
  let release!: () => void;
  const written = new Promise<void>((r) => (release = r));
  let stored = false;
  const app = new Hono();
  app.use(
    "*",
    requestLogMiddleware({
      sink: async () => {
        await written;
        stored = true;
      },
    }),
  );
  app.onError(errorHandler());
  app.get("/x", (c) => c.text("ok"));
  const res = await app.request("/x");
  const body = res.text();
  await new Promise((r) => setTimeout(r, 20));
  assert(!stored);
  release();
  assertEquals(await body, "ok");
  assert(stored);
});

Deno.test("createApp → with the store sink, every request is one row", async () => {
  const db = await store();
  const app = createRealApp({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    logSink: storeSink(db, () => {}),
  });
  await (await app.request(`/api/v2/events`, { method: "POST", body: "{}", headers: V2 })).text();
  const got = await rows(db, "SELECT method, url, status, version, bytes_in FROM request_log");
  assertEquals(got, [{
    method: "POST",
    url: "http://localhost/api/v2/events",
    status: 401,
    version: "99.0",
    bytes_in: 2,
  }]);
  db.close();
});

Deno.test("the sweep's reads → a window's counts, its 5xx newest first, the age limit, the size", async () => {
  const db = await store();
  const at = (iso: string) => new Date(iso);
  for (
    const r of [
      record({ reqid: "old001", at: at("2026-09-01T00:00:00Z"), status: 502 }),
      record({ reqid: "day001", at: at("2026-10-06T01:00:00Z"), status: 200 }),
      record({ reqid: "day002", at: at("2026-10-06T02:00:00Z"), status: 404 }),
      record({
        reqid: "day003",
        at: at("2026-10-06T03:00:00Z"),
        status: 502,
        errors: ["upstream"],
        fields: { upstream: "x" },
      }),
      record({ reqid: "day004", at: at("2026-10-06T04:00:00Z"), status: 500 }),
      record({ reqid: "day005", at: at("2026-10-06T05:00:00Z"), status: 200, errors: ["fanout"] }),
      record({ reqid: "nxt001", at: at("2026-10-07T00:00:00Z"), status: 503 }),
    ]
  ) await insertRequestRecord(db, r);

  const from = "2026-10-06T00:00:00.000Z";
  const to = "2026-10-07T00:00:00.000Z";
  assertEquals(await requestCounts(db, from, to), {
    total: 5,
    clientErrors: 1,
    serverErrors: 2,
    withErrors: 2,
  });
  assertEquals((await serverErrorRecords(db, from, to)).map((r) => r.reqid), ["day004", "day003"]);

  assertEquals((await requestLogSize(db)).rows, 7);
  assertEquals(await deleteRequestLogBefore(db, "2026-09-07T00:00:00.000Z"), 1);
  assertEquals((await requestLogSize(db)).rows, 6);
  assert((await requestLogSize(db)).bytes > 0);
  db.close();
});
