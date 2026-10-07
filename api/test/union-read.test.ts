// The INCREMENTAL union read and the DOWNLOAD REDIRECT (decision record `changes/incremental-union`,
// D1–D5; capability `privacy-security`): `/files`' additive parameters (`urls=false`, `cursor`), the
// position header, the optional token and the `fetch` row each read leaves, and the stable per-resource
// address that answers `302` to a presign. What the log records for a gain or a removal is
// `union-log.test.ts`'s.
//
// Paths are literal per version, like every version test file.

import { assert, assertEquals } from "@std/assert";
import type { Db } from "../src/db.ts";
import { eventBytePath } from "../src/storage.ts";
import {
  as,
  assertPresigned,
  CONFIG,
  createApp,
  createRealApp,
  D,
  D2,
  E,
  NOW,
  recorder,
  rows,
  storeWithEvent,
  VERSION_HEADER,
} from "./support/harness.ts";

const V2_UNION = `/api/v2/events/${E}/files`;
const redirectPath = (v: number, asset: string, role = "primary", device = D) =>
  `/api/v${v}/events/${E}/files/devices/${device}/${asset}/${role}`;
const ORIGIN = "https://snapsync.stho.net"; // CONFIG.linkDomain, not loopback → https

const asset = (id: string, roles: string[] = ["primary"]) => ({
  assetId: id,
  creationDate: "2026-07-01T00:00:00Z",
  resources: roles.map((role) => ({
    role,
    contentType: "image/heic",
    key: `${id}-${role}`,
    filename: `Capture ${id}`,
  })),
});

/** A store whose event has D's assets `ids` declared and their bytes landed through the v2 routes. */
async function eventWith(ids: string[]) {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const h = { [VERSION_HEADER]: "0.1" };
  await app.request(`/api/v2/events/${E}/devices/${D}`, { method: "PUT", headers: h });
  await app.request(`/api/v2/events/${E}/devices/${D}/manifest`, {
    method: "PUT",
    headers: h,
    body: JSON.stringify({ deviceId: D, assets: ids.map((id) => asset(id)) }),
  });
  for (const id of ids) {
    await app.request(`/api/v2/files/devices/${D}/${id}/primary?filename=IMG_${id}.HEIC`, {
      method: "PUT",
      headers: h,
      body: "b",
    });
  }
  const publish = (next: string[]) =>
    app.request(`/api/v2/events/${E}/devices/${D}/manifest`, {
      method: "PUT",
      headers: h,
      body: JSON.stringify({ deviceId: D, assets: next.map((id) => asset(id)) }),
    });
  const upload = (id: string) =>
    app.request(`/api/v2/files/devices/${D}/${id}/primary?filename=IMG_${id}.HEIC`, {
      method: "PUT",
      headers: h,
      body: "b",
    });
  return { db, publish, upload };
}

/** The real app with NOTHING attached: no token, no version header — a browser, or an OS transport. */
const bare = (db: Db, lines: string[] = []) =>
  createRealApp({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    now: () => NOW,
    buildSha: "x",
    logSink: (l) => lines.push(l),
  });
/** The app as a current v2 build sends it: the version header, plus whatever `headers` add. */
const app2 = (db: Db, headers: Record<string, string> = {}, lines: string[] = []) => {
  const app = bare(db, lines);
  return (path: string) => app.request(path, { headers: { [VERSION_HEADER]: "0.1", ...headers } });
};

type Asset = { assetId: string; resources: Record<string, unknown>[] };

// ── The download redirect (D1–D2) ─────────────────────────────────────────────────────────────────

Deno.test("redirect → 302 to a 7-day presign of the stored path, uncached, with no token and no version", async () => {
  const { db } = await eventWith(["A"]);
  const res = await bare(db).request(redirectPath(2, "A"));
  assertEquals(res.status, 302);
  assertPresigned(res.headers.get("location")!, await eventBytePath(E, D, "A", "primary"));
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  db.close();
});

Deno.test("redirect → 404 once the asset is withdrawn, for an unknown event, role or device", async () => {
  const { db, publish } = await eventWith(["A", "B"]);
  assertEquals((await bare(db).request(redirectPath(2, "B"))).status, 302);
  await publish(["A"]);
  assertEquals((await bare(db).request(redirectPath(2, "B"))).status, 404, "withdrawn");
  assertEquals(
    (await bare(db).request(redirectPath(2, "A", "live"))).status,
    404,
    "undeclared role",
  );
  assertEquals((await bare(db).request(redirectPath(2, "A", "primary", D2))).status, 404);
  const other = `/api/v2/events/7a3f9c21-0000-4000-8000-0000000000ff/files/devices/${D}/A/primary`;
  assertEquals((await bare(db).request(other)).status, 404, "unknown event");
  db.close();
});

Deno.test("redirect → a mutating method on its path stays gated", async () => {
  const { db } = await eventWith(["A"]);
  const res = await bare(db).request(redirectPath(2, "A"), { method: "PUT", body: "x" });
  assert(res.status === 401 || res.status === 426, `expected a gate refusal, got ${res.status}`);
  db.close();
});

// ── urls and the cursor (D3) ──────────────────────────────────────────────────────────────────────

Deno.test("urls → the default url is the stable address; urls=false omits it", async () => {
  const { db } = await eventWith(["A"]);
  const dflt = await (await app2(db)(V2_UNION)).json() as Asset[];
  assertEquals(dflt[0].resources[0].url, `${ORIGIN}${redirectPath(2, "A")}`);

  const none = await (await app2(db)(`${V2_UNION}?urls=false`)).json() as Asset[];
  assertEquals(Object.keys(none[0].resources[0]).sort(), [
    "contentType",
    "filename",
    "key",
    "role",
  ]);
  db.close();
});

Deno.test("cursor → a full read names its position; a delta from it serves only what was gained since", async () => {
  const { db, upload, publish } = await eventWith(["A"]);
  const full = await app2(db)(V2_UNION);
  const cursor = full.headers.get("SnapSync-Cursor")!;
  assert(/^\d+$/.test(cursor), `a position, got ${cursor}`);

  const nothing = await app2(db)(`${V2_UNION}?cursor=${cursor}`);
  assertEquals(await nothing.json(), []);
  assertEquals(nothing.headers.get("SnapSync-Cursor"), cursor, "no gain, no move");

  await publish(["A", "B"]);
  await upload("B");
  const delta = await app2(db)(`${V2_UNION}?cursor=${cursor}&urls=false`);
  assertEquals((await delta.json() as Asset[]).map((a) => a.assetId), ["B"]);
  assert(Number(delta.headers.get("SnapSync-Cursor")) > Number(cursor));
  db.close();
});

Deno.test("cursor → a malformed cursor is 400", async () => {
  const { db } = await eventWith(["A"]);
  for (const bad of ["x", "-1", "1.5", "", "9999999999999999"]) {
    assertEquals((await app2(db)(`${V2_UNION}?cursor=${bad}`)).status, 400, bad);
  }
  db.close();
});

// ── Who reads, and why (D5; capability `privacy-security`) ────────────────────────────────────────

async function fetches(db: Db) {
  return await rows(
    db,
    `SELECT device_id, trigger, cursor_from, cursor_to, served FROM union_log WHERE kind = 'fetch'
      ORDER BY seq`,
  );
}

Deno.test("reads → a verified token names the reader and its trigger; a delta records where it started", async () => {
  const { db } = await eventWith(["A"]);
  const lines: string[] = [];
  const read = app2(db, { ...await as(D2), "SnapSync-Trigger": "push" }, lines);
  await (await read(V2_UNION)).text();
  await (await read(`${V2_UNION}?cursor=0`)).text();
  assert(lines[0].endsWith(" served=1 trigger=push"), lines[0]);
  const log = await fetches(db);
  assertEquals(log.length, 2);
  assertEquals(log[0].device_id, D2);
  assertEquals(log[0].trigger, "push");
  assertEquals(log[0].cursor_from, null, "a full read starts nowhere");
  assertEquals(log[0].served, 1);
  assertEquals(log[1].cursor_from, 0);
  db.close();
});

Deno.test("reads → a tokenless read is recorded anonymously; an unknown trigger is recorded as unknown", async () => {
  const { db } = await eventWith(["A"]);
  assertEquals((await app2(db, { "SnapSync-Trigger": "x" })(V2_UNION)).status, 200);
  const log = await fetches(db);
  assertEquals(log.length, 1);
  assertEquals(log[0].device_id, null);
  assertEquals(log[0].trigger, null);
  db.close();
});

Deno.test("reads → a token that does not verify is 401, so the app re-attests", async () => {
  const { db } = await eventWith(["A"]);
  const res = await app2(db, { authorization: "Bearer not-a-token" })(V2_UNION);
  assertEquals(res.status, 401);
  assertEquals(await fetches(db), []);
  db.close();
});

Deno.test("reads → a failing log write never fails the read", async () => {
  const { db } = await eventWith(["A"]);
  const failingLog: Db = {
    execute: (sql, args) =>
      /INSERT INTO union_log/.test(sql)
        ? Promise.reject(new Error("log down"))
        : db.execute(sql, args),
    batch: (s) => db.batch(s),
    transaction: (fn) => db.transaction(fn),
  };
  const lines: string[] = [];
  const res = await app2(failingLog, {}, lines)(V2_UNION);
  assertEquals(res.status, 200);
  assertEquals((await res.json() as Asset[]).length, 1);
  assert(
    lines[0].includes(' err="union: could not log the read of ') && lines[0].includes("log down"),
    lines[0],
  );
  db.close();
});
