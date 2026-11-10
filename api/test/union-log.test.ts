// The event's UNION LOG (capability `privacy-security`; decision record `changes/incremental-union`, D4):
// what makes a union read incremental. These tests assert the log's ROWS — when an asset is recorded as
// gained or removed, that a refused publish records nothing, that completing an event deletes the record —
// and the delta read `unionRows(after)` serves over them. The `/files` route's parameters and its read record
// rows are `union-read.test.ts`'s.
//
// v2 paths and bodies are literal here, like every version test file: a fixture shared with `v2.test.ts`
// could let a change made for one file move what the other asserts.

import { assertEquals } from "@std/assert";
import { completeEvent, unionPosition, unionRows } from "../src/db.ts";
import type { FetchLike } from "../src/app.ts";
import {
  apnsConfig,
  as,
  CONFIG,
  createApp,
  D,
  D2,
  E,
  enrolDevice,
  lineSink,
  recorder,
  rows,
  storeWithEvent,
  VERSION_HEADER,
} from "./support/harness.ts";

const JOIN_PATH = `/api/v2/events/${E}/devices/${D}`;
const MANIFEST_PATH = `/api/v2/events/${E}/devices/${D}/manifest`;
const bytePath = (asset: string, role = "primary") =>
  `/api/v2/files/devices/${D}/${asset}/${role}?filename=IMG_${asset}.HEIC`;

const RES = (key: string, role = "primary") => ({
  role,
  contentType: "image/heic",
  key,
  filename: `Capture ${key}`,
});
const asset = (id: string, roles: string[] = ["primary"]) => ({
  assetId: id,
  creationDate: "2026-07-01T00:00:00Z",
  resources: roles.map((r) => RES(`${id}-${r}`, r)),
});
const body = (assets: ReturnType<typeof asset>[], version?: number) =>
  JSON.stringify({ deviceId: D, version: version ?? 0, final: false, assets });

/** A joined v2 member D over a fresh store holding the event; `withdrawn` reads what its last publish withdrew. */
async function member() {
  const db = await storeWithEvent();
  const lines: string[] = [];
  const app = createApp({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    logSink: lineSink(lines),
  });
  const req = (path: string, init: RequestInit = {}) =>
    app.request(path, { ...init, headers: { [VERSION_HEADER]: "0.1", ...(init.headers ?? {}) } });
  await req(JOIN_PATH, { method: "PUT" });
  return {
    db,
    upload: (id: string, role = "primary") => req(bytePath(id, role), { method: "PUT", body: "b" }),
    publish: (assets: ReturnType<typeof asset>[], version?: number) =>
      req(MANIFEST_PATH, { method: "PUT", body: body(assets, version) }),
    /** What the last publish withdrew, as its delivery record says (`withdrawn=`, once per photo). */
    withdrawn: () => [...(lines.at(-1) ?? "").matchAll(/ withdrawn=(\S+)/g)].map((m) => m[1]),
  };
}

async function log(db: Awaited<ReturnType<typeof storeWithEvent>>) {
  return (await rows(db, `SELECT kind, asset_id FROM union_log ORDER BY seq`))
    .map((r) => `${r.kind} ${r.asset_id}`);
}

// ── gained, by the byte route ─────────────────────────────────────────────────────────────────────

Deno.test("union log → the byte that completes an asset logs it gained, once", async () => {
  const m = await member();
  await m.publish([asset("A", ["primary", "live"])]);
  await m.upload("A", "primary");
  assertEquals(await log(m.db), [], "half an asset is not servable");
  await m.upload("A", "live");
  assertEquals(await log(m.db), ["gained A"]);
  await m.upload("A", "live");
  assertEquals(await log(m.db), ["gained A"], "a re-upload of a landed role gains nothing");
  m.db.close();
});

// ── gained and removed, by the publish ────────────────────────────────────────────────────────────

// A withdrawal is the delivery record's (`withdrawn=` on the publish's line), never a union-log row: nothing reads one
// (decision record `changes/delivery-record`, D9).
Deno.test("union log → a narrowing records a withdrawal, a widening logs gained, an unchanged set logs nothing", async () => {
  const m = await member();
  await m.publish([asset("A"), asset("B")]);
  await m.upload("A");
  await m.upload("B");
  assertEquals(await log(m.db), ["gained A", "gained B"]);

  await m.publish([asset("A")]);
  assertEquals(await log(m.db), ["gained A", "gained B"]);
  assertEquals(m.withdrawn(), ["B"]);

  await m.publish([asset("A"), asset("B")]);
  assertEquals(await log(m.db), ["gained A", "gained B", "gained B"]);
  assertEquals(m.withdrawn(), []);

  await m.publish([asset("A"), asset("B")]);
  assertEquals((await log(m.db)).length, 3, "republishing the same complete set changes nothing");
  m.db.close();
});

Deno.test("union log → an asset that declares a role not yet landed is withdrawn until it lands", async () => {
  const m = await member();
  await m.publish([asset("A")]);
  await m.upload("A");
  await m.publish([asset("A", ["primary", "live"])]);
  assertEquals(await log(m.db), ["gained A"]);
  assertEquals(m.withdrawn(), ["A"]);
  await m.upload("A", "live");
  assertEquals(await log(m.db), ["gained A", "gained A"]);
  m.db.close();
});

Deno.test("union log → a refused (older) publish logs nothing", async () => {
  const m = await member();
  await m.upload("A");
  await m.upload("B");
  await m.publish([asset("A")], 5);
  assertEquals(await log(m.db), ["gained A"]);
  assertEquals((await m.publish([asset("B")], 4)).status, 200);
  assertEquals(
    await log(m.db),
    ["gained A"],
    "the stale snapshot changed nothing, so it logs nothing",
  );
  m.db.close();
});

// ── the delta read ────────────────────────────────────────────────────────────────────────────────

Deno.test("union log → a delta serves what was gained after the position, through the union's filter", async () => {
  const m = await member();
  await m.publish([asset("A"), asset("B")]);
  await m.upload("A");
  const pos = await unionPosition(m.db, E);
  await m.upload("B");

  const ids = async (
    after?: number,
  ) => [...new Set((await unionRows(m.db, E, after)).map((r) => r.assetId))];
  assertEquals(await ids(), ["A", "B"]);
  assertEquals(await ids(pos), ["B"]);
  assertEquals(await ids(await unionPosition(m.db, E)), [], "nothing past the current position");

  // Removed after its gain → not served; re-gained → served once.
  await m.publish([asset("A")]);
  assertEquals(await ids(pos), []);
  await m.publish([asset("A"), asset("B")]);
  assertEquals(await ids(pos), ["B"]);
  assertEquals((await unionRows(m.db, E, pos)).length, 1, "two gains of one asset serve it once");
  m.db.close();
});

Deno.test("union log → the position is 0 for an event with nothing gained", async () => {
  const db = await storeWithEvent();
  assertEquals(await unionPosition(db, E), 0);
  db.close();
});

// ── the record ends with the event's photos ───────────────────────────────────────────────────────

// @Verifies(spec = "privacy-security", requirement = "The service records who reads an event's photo list", scenario = "The record ends with the event")
Deno.test("union log → completing an event deletes its record", async () => {
  const m = await member();
  await m.publish([asset("A")]);
  await m.upload("A");
  assertEquals((await log(m.db)).length, 1);
  await m.db.transaction((tx) => completeEvent(tx, E, "2026-07-30T00:00:00Z"));
  assertEquals(await log(m.db), []);
  m.db.close();
});

// ── the wake names the position it announces (D6) ─────────────────────────────────────────────────

Deno.test("union log → the byte route's wake names the gained position", async () => {
  const db = await storeWithEvent();
  const bodies: Record<string, unknown>[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    if (url.includes("push.apple.com")) bodies.push(JSON.parse(String(init.body)));
    return Promise.resolve(
      new Response(null, { status: url.includes("push.apple.com") ? 200 : 201 }),
    );
  };
  const app = createApp({ config: await apnsConfig(), db, fetch: fetchImpl });
  const h = { [VERSION_HEADER]: "0.1" };
  await app.request(JOIN_PATH, { method: "PUT", headers: h });
  await app.request(`/api/v2/events/${E}/devices/${D2}`, {
    method: "PUT",
    headers: { ...h, ...await as(D2) },
  });
  await enrolDevice(db, D2);
  await db.execute(
    `UPDATE devices SET push_kind = 'apns', push_token = 'r', push_env = 'sandbox'
                        WHERE device_id = ?`,
    [D2],
  );
  await app.request(MANIFEST_PATH, { method: "PUT", headers: h, body: body([asset("A")]) });
  await app.request(bytePath("A"), { method: "PUT", headers: h, body: "b" });
  assertEquals(bodies.length, 1);
  assertEquals(bodies[0].seq, await unionPosition(db, E));
  db.close();
});
