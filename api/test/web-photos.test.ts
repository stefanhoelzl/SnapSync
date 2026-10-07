// The EVENT PAGE's read, `/web/events/<id>/photos` (decision record `changes/separate-event-page-from-device-api`;
// capabilities `event-site`, `privacy-security`): its shape, which photos it lists, its 1-hour links, that it
// needs no credential and checks none, the anonymous record each read leaves, and its failure answers.

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

const PHOTOS = `/web/events/${E}/photos`;
const H = { [VERSION_HEADER]: "0.1" };

const asset = (id: string, roles: string[]) => ({
  assetId: id,
  creationDate: "2026-07-01T00:00:00Z",
  resources: roles.map((role) => ({
    role,
    contentType: "image/heic",
    key: `${id}-${role}`,
    filename: `Capture ${id}`,
  })),
});

/**
 * A store whose event has `device` join, declare `declared` (asset id → roles) and upload the `uploaded` roles,
 * all through the v2 routes a current build calls.
 */
async function share(
  db: Db,
  device: string,
  declared: Record<string, string[]>,
  uploaded: [string, string][],
) {
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const auth = await as(device);
  const h = { ...H, ...auth };
  await app.request(`/api/v2/events/${E}/devices/${device}`, { method: "PUT", headers: h });
  await app.request(`/api/v2/events/${E}/devices/${device}/manifest`, {
    method: "PUT",
    headers: h,
    body: JSON.stringify({
      deviceId: device,
      assets: Object.entries(declared).map(([id, roles]) => asset(id, roles)),
    }),
  });
  for (const [id, role] of uploaded) {
    await app.request(`/api/v2/files/devices/${device}/${id}/${role}?filename=IMG_${id}.${role}`, {
      method: "PUT",
      headers: h,
      body: "b",
    });
  }
  return {
    leave: () =>
      app.request(`/api/v2/events/${E}/devices/${device}?received=false`, {
        method: "DELETE",
        headers: h,
      }),
  };
}

/** The real app with NOTHING attached — a browser. */
const browser = (db: Db) =>
  createRealApp({ config: CONFIG, db, fetch: recorder().fetchImpl, now: () => NOW, buildSha: "x" });

type Photo = {
  deviceId: string;
  assetId: string;
  resources: { role: string; filename: string; url: string }[];
};

Deno.test("web photos → the page's own shape: each complete photo with its resources' names and 1-hour links", async () => {
  const db = await storeWithEvent();
  await share(db, D, { A: ["primary", "live"] }, [["A", "primary"], ["A", "live"]]);
  const res = await browser(db).request(PHOTOS);
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  const photos = await res.json() as Photo[];
  assertEquals(photos.length, 1);
  assertEquals(Object.keys(photos[0]).sort(), ["assetId", "deviceId", "resources"]);
  assertEquals(photos[0].deviceId, D);
  assertEquals(photos[0].assetId, "A");
  assertEquals(photos[0].resources.map((r) => r.role), ["primary", "live"]);
  for (const r of photos[0].resources) {
    assertEquals(Object.keys(r).sort(), ["filename", "role", "url"]);
    assertEquals(r.filename, `IMG_A.${r.role}`);
    assertPresigned(r.url, await eventBytePath(E, D, "A", r.role), "3600");
  }
  db.close();
});

Deno.test("web photos → an incomplete photo is left out; a departed member's photos stay", async () => {
  const db = await storeWithEvent();
  const gone = await share(db, D2, { G: ["primary"] }, [["G", "primary"]]);
  await gone.leave();
  await share(db, D, { A: ["primary"], B: ["primary", "live"] }, [["A", "primary"], [
    "B",
    "primary",
  ]]);
  const photos = await (await browser(db).request(PHOTOS)).json() as Photo[];
  assertEquals(photos.map((p) => `${p.deviceId}/${p.assetId}`).sort(), [`${D}/A`, `${D2}/G`]);
  db.close();
});

Deno.test("web photos → a withdrawn photo is no longer listed", async () => {
  const db = await storeWithEvent();
  await share(db, D, { A: ["primary"], B: ["primary"] }, [["A", "primary"], ["B", "primary"]]);
  assertEquals(((await (await browser(db).request(PHOTOS)).json()) as Photo[]).length, 2);
  await share(db, D, { A: ["primary"] }, []);
  const photos = await (await browser(db).request(PHOTOS)).json() as Photo[];
  assertEquals(photos.map((p) => p.assetId), ["A"]);
  db.close();
});

Deno.test("web photos → no credential needed, and a bogus one is ignored rather than checked", async () => {
  const db = await storeWithEvent();
  await share(db, D, { A: ["primary"] }, [["A", "primary"]]);
  const res = await browser(db).request(PHOTOS, {
    headers: { authorization: "Bearer not-a-token" },
  });
  assertEquals(res.status, 200);
  assertEquals(((await res.json()) as Photo[]).length, 1);
  db.close();
});

Deno.test("web photos → each read leaves one anonymous record: no device, no trigger", async () => {
  const db = await storeWithEvent();
  await share(db, D, { A: ["primary"] }, [["A", "primary"]]);
  await browser(db).request(PHOTOS, { headers: { "SnapSync-Trigger": "push" } });
  const log = await rows(
    db,
    `SELECT device_id, trigger, cursor_from, served FROM union_log WHERE kind = 'fetch'`,
  );
  assertEquals(log, [{ device_id: null, trigger: null, cursor_from: null, served: 1 }]);
  db.close();
});

Deno.test("web photos → 404 for an absent or malformed event; HEAD answers with no body", async () => {
  const db = await storeWithEvent();
  assertEquals(
    (await browser(db).request(`/web/events/7a3f9c21-0000-4000-8000-0000000000ff/photos`)).status,
    404,
  );
  assertEquals((await browser(db).request(`/web/events/not-a-uuid/photos`)).status, 404);
  const head = await browser(db).request(PHOTOS, { method: "HEAD" });
  assertEquals(head.status, 200);
  assertEquals(await head.text(), "");
  db.close();
});

Deno.test("web photos → a store that cannot be read is 502, never the invalid view's 404", async () => {
  const db = await storeWithEvent();
  const failing: Db = {
    execute: (sql, args) =>
      /event_assets/.test(sql) ? Promise.reject(new Error("store down")) : db.execute(sql, args),
    batch: (s) => db.batch(s),
    transaction: (fn) => db.transaction(fn),
  };
  assertEquals((await browser(failing).request(PHOTOS)).status, 502);
  const down: Db = { ...failing, execute: () => Promise.reject(new Error("store down")) };
  assertEquals((await browser(down).request(PHOTOS)).status, 502);
  db.close();
});

Deno.test("web photos → a failing record never fails the read", async () => {
  const db = await storeWithEvent();
  await share(db, D, { A: ["primary"] }, [["A", "primary"]]);
  const failingLog: Db = {
    execute: (sql, args) =>
      /INSERT INTO union_log/.test(sql)
        ? Promise.reject(new Error("log down"))
        : db.execute(sql, args),
    batch: (s) => db.batch(s),
    transaction: (fn) => db.transaction(fn),
  };
  const res = await browser(failingLog).request(PHOTOS);
  assertEquals(res.status, 200);
  assert(((await res.json()) as Photo[]).length === 1);
  db.close();
});

Deno.test("web photos → refused during a maintenance window, like the device API", async () => {
  const db = await storeWithEvent();
  const app = createRealApp({
    config: { ...CONFIG, maintenance: true },
    db,
    fetch: recorder().fetchImpl,
    now: () => NOW,
    buildSha: "x",
  });
  assertEquals((await app.request(PHOTOS)).status, 503);
  db.close();
});
