// The INCREMENTAL union read and the DOWNLOAD REDIRECT (decision record `changes/incremental-union`,
// D1–D5; capability `privacy-security`): `/files`' additive parameters (`urls=false`, `cursor`), the
// position header, the optional token and the `fetch` row each read leaves, and the stable per-resource
// address that answers `302` to a presign. What the log records for a gain or a removal is
// `union-log.test.ts`'s.
//
// Paths are literal per version, like every version test file.

import { assert, assertEquals, assertMatch } from "@std/assert";
import type { Db } from "../src/db.ts";
import { eventBytePath } from "../src/storage.ts";
import { completeEvent } from "../src/db.ts";
import { OPERATIONS } from "../src/gen/wire.ts";
import {
  as,
  assertPresigned,
  CONFIG,
  createApp,
  createRealApp,
  D,
  D2,
  E,
  lineSink,
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
    body: JSON.stringify({
      deviceId: D,
      version: 0,
      final: false,
      assets: ids.map((id) => asset(id)),
    }),
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
      body: JSON.stringify({
        deviceId: D,
        version: 0,
        final: false,
        assets: next.map((id) => asset(id)),
      }),
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
    logSink: lineSink(lines),
  });
/** The app as a current v2 build sends it: the version header, plus whatever `headers` add. */
const app2 = (db: Db, headers: Record<string, string> = {}, lines: string[] = []) => {
  const app = bare(db, lines);
  return (path: string) => app.request(path, { headers: { [VERSION_HEADER]: "0.1", ...headers } });
};

type Asset = { assetId: string; resources: Record<string, unknown>[] };

// ── The download redirect (D1–D2) ─────────────────────────────────────────────────────────────────

// @Verifies(spec = "privacy-security", requirement = "A link to a single photo lasts only as long as the photo is shared", scenario = "A copied photo link while the photo is shared")
Deno.test("redirect → 302 to a 7-day presign of the stored path, uncached, with no token and no version", async () => {
  const { db } = await eventWith(["A"]);
  const res = await bare(db).request(redirectPath(2, "A"));
  assertEquals(res.status, 302);
  assertPresigned(res.headers.get("location")!, await eventBytePath(E, D, "A", "primary"));
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  db.close();
});

// @Verifies(spec = "privacy-security", requirement = "A link to a single photo lasts only as long as the photo is shared", scenario = "A copied photo link after the photo is withdrawn")
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

// @Verifies(spec = "privacy-security", requirement = "A link to a single photo lasts only as long as the photo is shared", scenario = "A copied photo link after the event's photos are deleted")
Deno.test("redirect → 404 once the sweep has completed the event, its row kept", async () => {
  const { db } = await eventWith(["A"]);
  assertEquals((await bare(db).request(redirectPath(2, "A"))).status, 302);
  await completeEvent(db, E, new Date(NOW).toISOString());
  assertEquals(
    (await rows(db, `SELECT id FROM events WHERE id = ?`, [E])).length,
    1,
    "the row is kept",
  );
  assertEquals((await bare(db).request(redirectPath(2, "A"))).status, 404);
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

// @Verifies(spec = "privacy-security", requirement = "The service records how photos are delivered", scenario = "A member's app is woken for a new photo")
// The api's part: it records which phone read the list and the reason the app gave (`push`, a wake for a new photo),
// in the request log — the delivery record (decision record `changes/delivery-record`). The app's part, that a woken
// read gives that reason, is `UnionReaderIntegrationTest`'s.
Deno.test("reads → a verified token names the reader and its trigger; a delta records where it started", async () => {
  const { db } = await eventWith(["A"]);
  const lines: string[] = [];
  const read = app2(db, { ...await as(D2), "SnapSync-Trigger": "push" }, lines);
  await (await read(V2_UNION)).text();
  await (await read(`${V2_UNION}?cursor=0`)).text();
  assertMatch(
    lines[0],
    new RegExp(` reader=${D2} served=1 trigger=push to=\\d+$`),
    "a full read starts nowhere",
  );
  assertMatch(lines[1], new RegExp(` reader=${D2} served=\\d+ trigger=push from=0 to=\\d+$`));
  assertEquals(await rows(db, `SELECT kind FROM union_log WHERE kind <> 'gained'`), []);
  db.close();
});

Deno.test("reads → a tokenless read is recorded anonymously; an unknown trigger is recorded as unknown", async () => {
  const { db } = await eventWith(["A"]);
  const lines: string[] = [];
  const res = await app2(db, { "SnapSync-Trigger": "x" }, lines)(V2_UNION);
  assertEquals(res.status, 200);
  await res.text();
  assertMatch(lines[0], / reader=- served=1 trigger=- to=\d+$/);
  db.close();
});

Deno.test("reads → a read a push caused records when the push arrived and the position it announced", async () => {
  const { db } = await eventWith(["A"]);
  const lines: string[] = [];
  const read = app2(db, {
    ...await as(D2),
    "SnapSync-Trigger": "push",
    "x-snapsync-notified": "2026-07-14T11:59:58Z",
    "x-snapsync-announced": "1",
  }, lines);
  await (await read(V2_UNION)).text();
  assert(lines[0].endsWith(" notified=2026-07-14T11:59:58Z announced=1"), lines[0]);
  const odd = app2(db, { "x-snapsync-notified": "yesterday", "x-snapsync-announced": "-1" }, lines);
  const res = await odd(V2_UNION);
  assertEquals(res.status, 200, "an unreadable fact is dropped, never refused");
  await res.text();
  assert(!lines[1].includes("notified=") && !lines[1].includes("announced="), lines[1]);
  db.close();
});

Deno.test("reads → a token that does not verify is 401, so the app re-attests", async () => {
  const { db } = await eventWith(["A"]);
  const lines: string[] = [];
  const res = await app2(db, { authorization: "Bearer not-a-token" }, lines)(V2_UNION);
  assertEquals(res.status, 401);
  await res.body?.cancel();
  assert(!lines[0].includes(" served="), lines[0]);
  db.close();
});

// @Verifies(spec = "privacy-security", requirement = "The service records how photos are delivered", scenario = "Members do not see each other's activity")
// The record of reads is the operator's: no read a member's app can make of the service answers with when, why or by
// whom another member read the list.
Deno.test("reads → no read an app can make answers with another member's read", async () => {
  const { db } = await eventWith(["A"]);
  const lines: string[] = [];
  await (await app2(db, { ...await as(D2), "SnapSync-Trigger": "push" }, lines)(V2_UNION)).text();
  assert(lines[0].includes(` reader=${D2} `), "the other member's read is recorded");
  const read = app2(db, { ...await as(D), "SnapSync-Trigger": "foreground" });
  const gets = OPERATIONS.filter((op) => op.method === "GET");
  assert(gets.some((op) => op.id === "eventFiles"), "the photo list is among them");
  for (const op of gets) {
    const path = `/api/v2${op.path}`.replace("{eventId}", E).replace("{deviceId}", D)
      .replace("{assetId}", "A").replace("{role}", "primary");
    const res = await read(path);
    const answer = `${res.headers.get("location") ?? ""} ${await res.text()}`;
    for (const trace of [D2, "push", "trigger", "cursor_from"]) {
      assert(!answer.includes(trace), `${op.id} answers with ${trace}: ${answer}`);
    }
  }
  db.close();
});
