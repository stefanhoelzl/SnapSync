// The routes v2 SHARES (`src/routes/shared.ts`): event create, read and rename, the union read, leave, and the
// push registration — their status codes and response shapes. They were pinned in v1's frozen contract file
// until v1 was retired (decision record `changes/separate-event-page-from-device-api`); they are pinned here
// now, as v2 serves them.
//
// Every request carries a device token (the harness's `createApp`) and a current app version (`v2` below);
// the gates themselves are tested in `attest.test.ts` and `app-version.test.ts`.

import { assert, assertEquals } from "@std/assert";
import type { Db } from "../src/db.ts";
import {
  as,
  CONFIG,
  createApp,
  createRealApp,
  D,
  D2,
  E,
  ENDS_AT,
  enrolDevice,
  NOW,
  recorder,
  rows,
  STARTS_AT,
  store,
  storeWithEvent,
  V2,
} from "./support/harness.ts";

const UNION_PATH = `/api/v2/events/${E}/files`;
const ORIGIN = "https://snapsync.stho.net"; // CONFIG.linkDomain, not loopback → https

/** The harness's app (pinned clock, a device token) with every request declaring a current app version. */
function v2(deps: Parameters<typeof createApp>[0]) {
  const app = createApp(deps);
  const request = app.request.bind(app);
  return {
    request: (path: string, init: RequestInit = {}) =>
      request(path, { ...init, headers: { ...V2, ...(init.headers ?? {}) } }),
  };
}

// ── POST /events (create) ──────────────────────────────────────────────────────────────────────────

Deno.test("POST /events → 201 with the event, and one row written", async () => {
  const db = await store();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v2/events",
    { method: "POST", body: JSON.stringify({ name: "Party", startsAt: STARTS_AT }) },
  );
  assertEquals(res.status, 201);
  const body = await res.json() as Record<string, unknown>;
  assertEquals(Object.keys(body).sort(), [
    "capacity",
    "closedAt",
    "completedAt",
    "createdAt",
    "deletesAt",
    "endsAt",
    "eventId",
    "name",
    "startsAt",
  ]);
  // A fresh event is neither closed nor completed (capability `event-lifetime`).
  assertEquals(body.closedAt, null);
  assertEquals(body.completedAt, null);
  assertEquals(body.name, "Party");
  assertEquals(body.startsAt, STARTS_AT);
  assertEquals(body.capacity, 10);
  const stored = await rows(db, `SELECT * FROM events`);
  assertEquals(stored.length, 1);
  assertEquals(stored[0].id, body.eventId);
  assertEquals(Number(stored[0].lifetime_seconds), CONFIG.eventLifetimeSeconds);
  db.close();
});

Deno.test("POST /events → the client cannot supply the id; the server mints it", async () => {
  const db = await store();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v2/events",
    {
      method: "POST",
      body: JSON.stringify({ name: "Party", startsAt: STARTS_AT, eventId: E, capacity: 999 }),
    },
  );
  const body = await res.json() as Record<string, unknown>;
  assert(body.eventId !== E);
  assertEquals(body.capacity, 10);
  db.close();
});

Deno.test("POST /events → an absent endsAt falls back to startsAt + the window maximum", async () => {
  const db = await store();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v2/events",
    { method: "POST", body: JSON.stringify({ name: "Party", startsAt: STARTS_AT }) },
  );
  assertEquals((await res.json() as Record<string, unknown>).endsAt, ENDS_AT);
  db.close();
});

Deno.test("POST /events → a creator-supplied endsAt within the maximum is stamped verbatim", async () => {
  const db = await store();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v2/events",
    {
      method: "POST",
      body: JSON.stringify({
        name: "Party",
        startsAt: STARTS_AT,
        endsAt: "2026-07-04T18:00:00Z",
      }),
    },
  );
  assertEquals((await res.json() as Record<string, unknown>).endsAt, "2026-07-04T18:00:00Z");
  db.close();
});

Deno.test("POST /events → a body violating a name or window rule → 400, nothing written", async () => {
  const db = await store();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const bodies = [
    "not json",
    JSON.stringify({ startsAt: STARTS_AT }), // no name
    JSON.stringify({ name: "   ", startsAt: STARTS_AT }), // empty after trimming
    JSON.stringify({ name: "x".repeat(101), startsAt: STARTS_AT }), // too long
    JSON.stringify({ name: "Party" }), // no startsAt
    JSON.stringify({ name: "Party", startsAt: "2026-06-27T18:00:00.000Z" }), // non-canonical
    JSON.stringify({ name: "Party", startsAt: STARTS_AT, endsAt: STARTS_AT }), // not after
    JSON.stringify({ name: "Party", startsAt: STARTS_AT, endsAt: "2026-07-28T18:00:00Z" }), // > 30d
  ];
  for (const body of bodies) {
    assertEquals((await app.request("/api/v2/events", { method: "POST", body })).status, 400, body);
  }
  assertEquals((await rows(db, `SELECT * FROM events`)).length, 0);
  db.close();
});

Deno.test("POST /events → the host's zone is stored, never echoed, and an unusable one stores null", async () => {
  const app = (db: Db) => v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const cases: [unknown, string | null][] = [
    ["Europe/Berlin", "Europe/Berlin"],
    [undefined, null], // an older client sends none
    ["Mars/Olympus_Mons", null], // a name this runtime does not know
    ["+02:00", null], // an offset is not a zone name
    [42, null],
  ];
  for (const [zone, stored] of cases) {
    const db = await store();
    const res = await app(db).request("/api/v2/events", {
      method: "POST",
      body: JSON.stringify({ name: "Party", startsAt: STARTS_AT, zone }),
    });
    assertEquals(res.status, 201, String(zone)); // a zone never refuses the create
    assert(!("zone" in (await res.json() as Record<string, unknown>)));
    assertEquals((await rows(db, `SELECT zone FROM events`))[0].zone, stored, String(zone));
    db.close();
  }
});

Deno.test("POST /events → the name is trimmed before it is stored and echoed", async () => {
  const db = await store();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v2/events",
    { method: "POST", body: JSON.stringify({ name: "  Birthday  ", startsAt: STARTS_AT }) },
  );
  assertEquals((await res.json() as Record<string, unknown>).name, "Birthday");
  assertEquals((await rows(db, `SELECT name FROM events`))[0].name, "Birthday");
  db.close();
});

Deno.test("POST /events → a store failure → 502 (faithful create)", async () => {
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
  };
  const res = await v2({ config: CONFIG, db: broken, fetch: recorder().fetchImpl }).request(
    "/api/v2/events",
    { method: "POST", body: JSON.stringify({ name: "Party", startsAt: STARTS_AT }) },
  );
  assertEquals(res.status, 502);
});

// ── GET /events/:eventId (metadata and existence) ──────────────────────────────────────────────────

Deno.test("GET /events/:id → 200 with the stored fields and the derived deletesAt", async () => {
  const db = await storeWithEvent();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}`,
  );
  assertEquals(res.status, 200);
  const body = await res.json() as Record<string, unknown>;
  assertEquals(body.eventId, E);
  assertEquals(body.endsAt, ENDS_AT);
  assertEquals(body.deletesAt, "2026-07-27T18:00:00Z"); // max(createdAt, startsAt) + 30d
  assert(!("lifetimeSeconds" in body)); // the duration is internal; the instant is the wire fact
  db.close();
});

Deno.test("GET /events/:id → 404 when the event does not exist, 400 on a non-UUID", async () => {
  const db = await store();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals((await app.request(`/api/v2/events/${E}`)).status, 404);
  assertEquals((await app.request(`/api/v2/events/nope`)).status, 400);
  db.close();
});

Deno.test("GET /events/:id → a store failure is 502, never 404", async () => {
  // The distinction is load-bearing outside this file: `manage-membership`'s two-witness teardown acts on a
  // 404, so a transient fault reported as absence would tear a live membership down.
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
  };
  const res = await v2({ config: CONFIG, db: broken, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}`,
  );
  assertEquals(res.status, 502);
});

Deno.test("GET /events/:id → an event past its delete-by is still served (no route reaps on touch)", async () => {
  const db = await storeWithEvent({ lifetimeSeconds: 24 * 60 * 60 });
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}`,
  );
  assertEquals(res.status, 200);
  assertEquals((await rows(db, `SELECT * FROM events`)).length, 1);
  db.close();
});

// ── PATCH /events/:eventId (rename) ────────────────────────────────────────────────────────────────

Deno.test("PATCH /events/:id → 200, and ONLY the name changes", async () => {
  const db = await storeWithEvent();
  const before = (await rows(db, `SELECT * FROM events WHERE id=?`, [E]))[0];
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}`,
    { method: "PATCH", body: JSON.stringify({ name: "  Renamed  " }) },
  );
  assertEquals(res.status, 200);
  assertEquals((await res.json() as Record<string, unknown>).name, "Renamed");
  const after = (await rows(db, `SELECT * FROM events WHERE id=?`, [E]))[0];
  assertEquals(after.name, "Renamed");
  // Every other column verbatim — this is what makes a race with the sweep self-defusing: a rename
  // cannot restamp `createdAt`/`startsAt`/`lifetime_seconds` and resurrect the event for a fresh life.
  for (
    const column of ["id", "created_at", "starts_at", "ends_at", "capacity", "lifetime_seconds"]
  ) {
    assertEquals(after[column], before[column], column);
  }
  db.close();
});

Deno.test("PATCH /events/:id → 404 when absent; 400 on a bad name or id", async () => {
  const empty = await store();
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await v2({ config: CONFIG, db: empty, fetch: recorder().fetchImpl }).request(
      `/api/v2/events/${E}`,
      { method: "PATCH", body: JSON.stringify({ name: "x" }) },
    )).status,
    404,
  );
  for (const body of ["not json", JSON.stringify({}), JSON.stringify({ name: "  " })]) {
    assertEquals((await app.request(`/api/v2/events/${E}`, { method: "PATCH", body })).status, 400);
  }
  assertEquals(
    (await app.request(`/api/v2/events/nope`, {
      method: "PATCH",
      body: JSON.stringify({ name: "x" }),
    }))
      .status,
    400,
  );
  assertEquals((await rows(db, `SELECT name FROM events`))[0].name, "Party");
  empty.close();
  db.close();
});

Deno.test("PUT /events/:id → 404 (only GET and PATCH are served on the event path)", async () => {
  const db = await storeWithEvent();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}`,
    { method: "PUT", body: "{}" },
  );
  assertEquals(res.status, 404);
  db.close();
});

// ── GET /events/:eventId/files (the union) ─────────────────────────────────────────────────────────

/**
 * `deviceId` joins the event under test, publishes `assets` and uploads every resource they name — what a v2
 * build does, in its three calls. The first key of an asset is its `primary`, the second its `live`.
 */
async function publish(
  db: Db,
  deviceId: string,
  assets: { assetId: string; creationDate: string; keys: string[] }[],
) {
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const h = await as(deviceId);
  const join = await app.request(`/api/v2/events/${E}/devices/${deviceId}`, {
    method: "PUT",
    headers: h,
  });
  assert(join.ok, `join ${join.status}`);
  const res = await app.request(`/api/v2/events/${E}/devices/${deviceId}/manifest`, {
    method: "PUT",
    headers: h,
    body: JSON.stringify({
      deviceId,
      assets: assets.map((a) => ({
        assetId: a.assetId,
        creationDate: a.creationDate,
        resources: a.keys.map((k, i) => ({
          role: i === 0 ? "primary" : "live",
          contentType: i === 0 ? "image/heic" : "video/quicktime",
          key: k,
          filename: `Capture ${k}`,
        })),
      })),
    }),
  });
  assert(res.status < 300, `manifest ${res.status}`);
  for (const a of assets) {
    for (const [i, k] of a.keys.entries()) {
      const role = i === 0 ? "primary" : "live";
      const up = await app.request(
        `/api/v2/events/${E}/files/devices/${deviceId}/${a.assetId}/${role}?filename=${
          encodeURIComponent(`Capture ${k}`)
        }`,
        { method: "PUT", headers: { ...h, "content-type": "image/heic" }, body: "bytes" },
      );
      assertEquals(up.status, 201);
    }
  }
}

Deno.test("union → both devices' assets, flattened, tagged by deviceId, uncacheable", async () => {
  const db = await storeWithEvent();
  await publish(db, D, [{
    assetId: "A",
    creationDate: "2026-07-01T00:00:00Z",
    keys: ["a.heic", "a.mov"],
  }]);
  await publish(db, D2, [{ assetId: "B", creationDate: "2026-07-02T00:00:00Z", keys: ["b.heic"] }]);

  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    UNION_PATH,
  );
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  const body = await res.json() as Record<string, unknown>[];
  assertEquals(body.length, 2);
  assertEquals(body.map((a) => a.deviceId).sort(), [D, D2].sort());

  const first = body.find((a) => a.deviceId === D)!;
  assertEquals(Object.keys(first).sort(), ["assetId", "creationDate", "deviceId", "resources"]);
  const resources = first.resources as Record<string, unknown>[];
  assertEquals(resources.length, 2);
  // The closed resource shape — five fields, and `size` is NOT among them.
  assertEquals(Object.keys(resources[0]).sort(), [
    "contentType",
    "filename",
    "key",
    "role",
    "url",
  ]);
  // Each url is the stable download address, which redirects to a fresh presign per download.
  assertEquals(resources[0].url, `${ORIGIN}/api/v2/events/${E}/files/devices/${D}/A/primary`);
  db.close();
});

Deno.test("union → an asset naming an unrecorded resource is omitted entirely", async () => {
  // Defense-in-depth: the manifest lists only uploaded resources, so this catches the residual case.
  const db = await storeWithEvent();
  await publish(db, D, [
    { assetId: "A", creationDate: "2026-07-01T00:00:00Z", keys: ["a.heic"] },
    { assetId: "B", creationDate: "2026-07-02T00:00:00Z", keys: ["b.heic", "b.mov"] },
  ]);
  // An UNRECORDED resource is an ABSENT row — the manifest still declares the `live` role for asset B,
  // so the union sees a declared role with no arrival and drops the asset whole.
  await db.execute(`DELETE FROM resources WHERE asset_id = 'B' AND role = 'live'`);
  const body = await (await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    UNION_PATH,
  )).json() as Record<string, unknown>[];
  assertEquals(body.map((a) => a.assetId), ["A"]);
  db.close();
});

Deno.test("union → a departed member's photos remain until the event is deleted", async () => {
  const db = await storeWithEvent();
  await publish(db, D, [{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", keys: ["a.heic"] }]);
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await app.request(`/api/v2/events/${E}/devices/${D}`, { method: "DELETE" })).status,
    200,
  );
  const body = await (await app.request(UNION_PATH)).json() as Record<string, unknown>[];
  assertEquals(body.map((a) => a.assetId), ["A"]);
  db.close();
});

Deno.test("union → unknown event → 404; existing event with no assets → 200 []", async () => {
  const empty = await store();
  const db = await storeWithEvent();
  assertEquals(
    (await v2({ config: CONFIG, db: empty, fetch: recorder().fetchImpl }).request(
      UNION_PATH,
    ))
      .status,
    404,
  );
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    UNION_PATH,
  );
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
  empty.close();
  db.close();
});

Deno.test("union → non-UUID event → 400; wrong method → 404", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals((await app.request(`/api/v2/events/nope/files`)).status, 400);
  assertEquals((await app.request(UNION_PATH, { method: "POST" })).status, 404);
  db.close();
});

Deno.test("union → a store failure → 502, never a partial union", async () => {
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
  };
  const res = await v2({ config: CONFIG, db: broken, fetch: recorder().fetchImpl }).request(
    UNION_PATH,
  );
  assertEquals(res.status, 502);
});

// ── DELETE /events/:eventId/devices/:deviceId (leave) ──────────────────────────────────────────────

Deno.test("leave → marks the membership departed and keeps its assets", async () => {
  const db = await storeWithEvent();
  await publish(db, D, [{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", keys: ["a.heic"] }]);
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}/devices/${D}`,
    { method: "DELETE" },
  );
  assertEquals(res.status, 200);
  assertEquals(await rows(db, `SELECT state FROM memberships WHERE device_id=?`, [D]), [{
    state: "left",
  }]);
  assertEquals((await rows(db, `SELECT * FROM event_assets WHERE device_id=?`, [D])).length, 1);
  db.close();
});

Deno.test("leave → is idempotent, and a leave by a non-member changes nothing", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const path = `/api/v2/events/${E}/devices/${D}`;
  assertEquals((await app.request(path, { method: "DELETE" })).status, 200); // never a member
  await publish(db, D, []);
  assertEquals((await app.request(path, { method: "DELETE" })).status, 200);
  assertEquals((await app.request(path, { method: "DELETE" })).status, 200);
  assertEquals((await rows(db, `SELECT * FROM memberships WHERE device_id=?`, [D])).length, 1);
  db.close();
});

Deno.test("leave → absent event → 404; non-UUID → 400", async () => {
  const empty = await store();
  const db = await storeWithEvent();
  assertEquals(
    (await v2({ config: CONFIG, db: empty, fetch: recorder().fetchImpl }).request(
      `/api/v2/events/${E}/devices/${D}`,
      { method: "DELETE" },
    )).status,
    404,
  );
  assertEquals(
    (await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
      `/api/v2/events/nope/devices/${D}`,
      { method: "DELETE" },
    )).status,
    400,
  );
  empty.close();
  db.close();
});

// ── PUT /devices/:deviceId (the device config document) ────────────────────────────────────────────

Deno.test("device config → the token lands in its own columns, last-write-wins", async () => {
  const db = await store();
  await enrolDevice(db, D);
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const doc = (token: string) =>
    JSON.stringify({ pushToken: { kind: "apns", token, env: "sandbox" } });
  assertEquals(
    (await app.request(`/api/v2/devices/${D}`, { method: "PUT", body: doc("first") })).status,
    201,
  );
  assertEquals(
    (await app.request(`/api/v2/devices/${D}`, { method: "PUT", body: doc("second") })).status,
    201,
  );
  const stored = await rows(
    db,
    `SELECT push_kind, push_token, push_env FROM devices WHERE device_id=?`,
    [D],
  );
  assertEquals(stored.length, 1);
  assertEquals(stored[0], { push_kind: "apns", push_token: "second", push_env: "sandbox" });
  db.close();
});

Deno.test("device config → a malformed pushToken is REFUSED at the write, not on the notify path", async () => {
  // The whole point of columns over a document: a bad registration fails at the endpoint that made it,
  // where the caller can be told, instead of being discovered days later by a fan-out that skips it.
  const db = await store();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  for (
    const body of [
      JSON.stringify({ pushToken: { kind: "apns", token: 42, env: "sandbox" } }),
      JSON.stringify({ pushToken: { kind: "apns", token: "t" } }),
      JSON.stringify({ pushToken: "not-an-object" }),
    ]
  ) {
    assertEquals(
      (await app.request(`/api/v2/devices/${D}`, { method: "PUT", body })).status,
      400,
      body,
    );
  }
  assertEquals((await rows(db, `SELECT * FROM devices`)).length, 0);
  db.close();
});

Deno.test("device config → an explicitly absent pushToken is recorded, not refused", async () => {
  // A device saying "I have no registration" is an ordinary state, distinct from a malformed body.
  const db = await store();
  await enrolDevice(db, D);
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v2/devices/${D}`,
    { method: "PUT", body: JSON.stringify({}) },
  );
  assertEquals(res.status, 201);
  const stored = await rows(db, `SELECT push_token FROM devices WHERE device_id=?`, [D]);
  assertEquals(stored, [{ push_token: null }]);
  db.close();
});

Deno.test("device config → non-UUID → 400; non-JSON → 400; wrong method → 404", async () => {
  const db = await store();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await app.request(`/api/v2/devices/nope`, { method: "PUT", body: "{}" })).status,
    400,
  );
  assertEquals(
    (await app.request(`/api/v2/devices/${D}`, { method: "PUT", body: "not json" })).status,
    400,
  );
  assertEquals((await app.request(`/api/v2/devices/${D}`)).status, 404);
  db.close();
});

Deno.test("device config → the document is not a resource: it never reaches the listing", async () => {
  const db = await store();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(`/api/v2/devices/${D}`, { method: "PUT", body: JSON.stringify({ a: 1 }) });
  assertEquals(await (await app.request(`/api/v2/files/devices/${D}`)).json(), []);
  db.close();
});

// ── Presigned URLs ─────────────────────────────────────────────────────────────────────────────────

Deno.test("presigned download URLs carry the configured scheme, not a hardcoded https", async () => {
  const db = await storeWithEvent();
  await publish(db, D, [{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", keys: ["a.heic"] }]);
  const httpConfig = { ...CONFIG, s3Scheme: "http", s3Host: "127.0.0.1:8080" };
  const res = await v2({ config: httpConfig, db, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}/files/devices/${D}/A/primary`,
  );
  assertEquals(res.status, 302);
  assert(String(res.headers.get("location")).startsWith("http://127.0.0.1:8080/"));
  db.close();
});

// ── The event read's optional token (decision record `changes/separate-event-page-from-device-api`, D7) ───

Deno.test("GET /events/:id → a sent token is checked: a valid one is served, a bad one is 401; none is served", async () => {
  const db = await storeWithEvent();
  const raw = createRealApp({ config: CONFIG, db, fetch: recorder().fetchImpl, now: () => NOW });
  const read = (headers: Record<string, string>) =>
    raw.request(`/api/v2/events/${E}`, { headers: { ...V2, ...headers } });
  assertEquals((await read(await as(D))).status, 200);
  assertEquals((await read({ authorization: "Bearer not-a-token" })).status, 401);
  assertEquals((await read({ authorization: "Basic x" })).status, 401);
  assertEquals((await read({})).status, 200, "a build that sends none is still served");
  db.close();
});
