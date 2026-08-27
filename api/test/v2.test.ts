// The `/api/v2` device-API surface.
//
// Fixtures here are deliberately LITERAL and local rather than shared with `v1.test.ts`, even where the
// two versions would spell them identically today. v1 is a frozen contract; if it imported a fixture
// builder this file also used, a change made for v2 could silently move what v1 asserts. Only machinery
// is shared (`support/harness.ts`). The duplication is the point.

import { assert, assertEquals } from "@std/assert";
import {
  CONFIG,
  createApp,
  D,
  D2,
  E,
  recorder,
  rows,
  store,
  storeWithEvent,
} from "./support/harness.ts";

// ── v2 fixtures ────────────────────────────────────────────────────────────────────────────────────

const VERSION_HEADER = "x-snapsync-app-version";
const CURRENT = "0.1"; // at the configured minimum

const BYTE_PATH = `/api/v2/files/devices/${D}/ASSET1/primary?filename=IMG_0001.HEIC`;
const BYTE_OBJ_URL =
  `https://storage.bunnycdn.com/snapsync-zone/files/devices/${D}/ASSET1-primary.heic`;
const DEVLIST_PATH = `/api/v2/files/devices/${D}`;
const JOIN_PATH = `/api/v2/events/${E}/devices/${D}`;
const MANIFEST_PATH = `/api/v2/events/${E}/devices/${D}/manifest`;
const UNION_PATH = `/api/v2/events/${E}/files`;

/** A v2 app: the harness attaches the token, this adds the version header every v2 route requires. */
function v2(deps: Parameters<typeof createApp>[0]) {
  const app = createApp(deps);
  const request = app.request.bind(app);
  return {
    request: (path: string, init: RequestInit = {}) =>
      request(path, { ...init, headers: { [VERSION_HEADER]: CURRENT, ...(init.headers ?? {}) } }),
  };
}

const manifest = (
  assets: { assetId: string; creationDate: string; resources: Record<string, unknown>[] }[],
) => JSON.stringify({ deviceId: D, assets });

const RES = (key: string, role = "primary") => ({
  role,
  contentType: "image/heic",
  key,
  filename: `Capture ${key}`,
});

/** Join, then publish — the v2 order, since a manifest no longer enrolls anyone. */
async function joinAndPublish(
  db: Awaited<ReturnType<typeof storeWithEvent>>,
  assets: { assetId: string; creationDate: string; resources: Record<string, unknown>[] }[],
) {
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN_PATH, { method: "PUT" });
  return await app.request(MANIFEST_PATH, { method: "PUT", body: manifest(assets) });
}

// ── The version gate (capability `min-app-version`) ────────────────────────────────────────────────

Deno.test("version gate → a request with no version header is refused 426 with the minimum", async () => {
  const db = await store();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    DEVLIST_PATH,
  );
  assertEquals(res.status, 426);
  assertEquals((await res.json() as Record<string, unknown>).minAppVersion, "0.1");
  db.close();
});

Deno.test("version gate → a too-old version is refused 426", async () => {
  const db = await store();
  const res = await createApp({
    config: { ...CONFIG, minAppVersion: "0.12" },
    db,
    fetch: recorder().fetchImpl,
  })
    .request(DEVLIST_PATH, { headers: { [VERSION_HEADER]: "0.11" } });
  assertEquals(res.status, 426);
  db.close();
});

Deno.test("version gate → 0.10 is NEWER than 0.9, though string order disagrees", async () => {
  const db = await store();
  const res = await createApp({
    config: { ...CONFIG, minAppVersion: "0.9" },
    db,
    fetch: recorder().fetchImpl,
  })
    .request(DEVLIST_PATH, { headers: { [VERSION_HEADER]: "0.10" } });
  assertEquals(res.status, 200);
  db.close();
});

Deno.test("version gate → an unparseable version is refused like a too-old one", async () => {
  const db = await store();
  for (const bad of ["", "abc", "v1.2"]) {
    const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl })
      .request(DEVLIST_PATH, { headers: { [VERSION_HEADER]: bad } });
    assertEquals(res.status, 426, `expected ${JSON.stringify(bad)} to be refused`);
  }
  db.close();
});

Deno.test("version gate → decided BEFORE the token, so an old build is not told 'unauthenticated'", async () => {
  // An old build holding an expired token must be told to UPDATE, not that its credentials are wrong —
  // the second is a message the user cannot act on.
  const { createRealApp } = await import("./support/harness.ts");
  const db = await store();
  const res = await createRealApp({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    buildSha: "x",
  })
    .request(DEVLIST_PATH); // no token AND no version header
  assertEquals(res.status, 426);
  db.close();
});

Deno.test("version gate → v1 is exempt: it predates the header and cannot be updated to send it", async () => {
  const db = await store();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl })
    .request(`/api/v1/files/devices/${D}`);
  assertEquals(res.status, 200);
  db.close();
});

// ── The route tables are closed, per version ───────────────────────────────────────────────────────

Deno.test("closed tables → a v1-only path is 404 under v2, and a v2-only path is 404 under v1", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  // notify exists only in v1; the manifest sub-resource only in v2.
  assertEquals((await app.request(`/api/v2/events/${E}/notify`, { method: "POST" })).status, 404);
  const v1res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl })
    .request(`/api/v1/events/${E}/devices/${D}/manifest`, { method: "PUT", body: "{}" });
  assertEquals(v1res.status, 404);
  db.close();
});

// ── PUT byte upload ────────────────────────────────────────────────────────────────────────────────

Deno.test("byte PUT → identity comes from the path, and the object name matches v1's exactly", async () => {
  // The SHARED-OBJECT property: v1 and v2 must address one stored object for one resource, or the first
  // v2 build re-uploads every library it meets and an event with a member on each version needs two ways
  // to name one photo.
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await v2({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    headers: { "content-type": "image/heic" },
    body: "bytes",
  });
  assertEquals(res.status, 201);
  assertEquals(calls.find((c) => c.init.method === "PUT")!.url, BYTE_OBJ_URL);
  const stored = await rows(db, `SELECT device_id, asset_id, role, key, filename FROM resources`);
  assertEquals(stored.length, 1);
  assertEquals(stored[0].asset_id, "ASSET1");
  assertEquals(stored[0].role, "primary");
  assertEquals(stored[0].key, "ASSET1-primary.heic");
  assertEquals(stored[0].filename, "IMG_0001.HEIC"); // the capture name, kept as metadata only
  db.close();
});

Deno.test("byte PUT → a role outside the closed vocabulary → 400, no upstream request", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await v2({ config: CONFIG, db, fetch: fetchImpl }).request(
    `/api/v2/files/devices/${D}/ASSET1/thumbnail?filename=IMG.HEIC`,
    { method: "PUT", body: "b" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
  db.close();
});

Deno.test("byte PUT → a missing or empty filename → 400", async () => {
  const db = await store();
  for (
    const path of [
      `/api/v2/files/devices/${D}/ASSET1/primary`,
      `/api/v2/files/devices/${D}/ASSET1/primary?filename=`,
    ]
  ) {
    const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl })
      .request(path, { method: "PUT", body: "b" });
    assertEquals(res.status, 400, path);
  }
  db.close();
});

Deno.test("byte PUT → a filename needing no path-safety check: separators never reach the key", async () => {
  // The value travels in the QUERY, so it cannot traverse the storage path — the v1 rule is not relaxed
  // but made unnecessary. It is metadata: it shapes only the extension and the stored capture name.
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await v2({ config: CONFIG, db, fetch: fetchImpl }).request(
    `/api/v2/files/devices/${D}/ASSET1/primary?filename=${
      encodeURIComponent("../../etc/passwd.HEIC")
    }`,
    { method: "PUT", body: "b" },
  );
  assertEquals(res.status, 201);
  assertEquals(calls.find((c) => c.init.method === "PUT")!.url, BYTE_OBJ_URL);
  db.close();
});

Deno.test("byte PUT → a re-upload with a different filename updates metadata, not identity", async () => {
  const db = await store();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(BYTE_PATH, { method: "PUT", body: "b" });
  await app.request(`/api/v2/files/devices/${D}/ASSET1/primary?filename=RENAMED.HEIC`, {
    method: "PUT",
    body: "b",
  });
  const stored = await rows(db, `SELECT filename FROM resources`);
  assertEquals(stored.length, 1); // one resource, not two
  assertEquals(stored[0].filename, "RENAMED.HEIC");
  db.close();
});

Deno.test("byte PUT → a database failure FAILS the request (nothing repairs it under v2)", async () => {
  // The inverse of v1, and deliberately: v1's manifest publish re-creates a missing row, which is what
  // makes swallowing the failure safe there. v2's manifest writes no resource row, so a swallowed failure
  // would leave stored bytes the backend never learns about, and the device would never re-upload them.
  const broken = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
  };
  const res = await v2({ config: CONFIG, db: broken, fetch: recorder().fetchImpl })
    .request(BYTE_PATH, { method: "PUT", body: "b" });
  assertEquals(res.status, 502);
});

// ── GET the per-device listing ─────────────────────────────────────────────────────────────────────

Deno.test("listing → answers in identity terms and mints no url", async () => {
  const db = await store();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(BYTE_PATH, { method: "PUT", body: "b" });
  const body = await (await app.request(DEVLIST_PATH)).json() as Record<string, unknown>[];
  assertEquals(body.length, 1);
  assertEquals(Object.keys(body[0]).sort(), ["assetId", "filename", "role"]);
  assertEquals(body[0].assetId, "ASSET1");
  db.close();
});

Deno.test("listing → a declared but unuploaded resource is absent, so pending is a difference", async () => {
  const db = await storeWithEvent();
  await joinAndPublish(db, [{
    assetId: "A",
    creationDate: "2026-07-01T00:00:00Z",
    resources: [RES("A-primary.heic"), RES("A-live.mov", "live")],
  }]);
  // The manifest DECLARED two roles; no bytes have arrived for either.
  const body = await (await v2({ config: CONFIG, db, fetch: recorder().fetchImpl })
    .request(DEVLIST_PATH)).json() as unknown[];
  assertEquals(body, []);
  db.close();
});

// ── PUT join ───────────────────────────────────────────────────────────────────────────────────────

Deno.test("join → enrolls the device, and joining twice is harmless", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals((await app.request(JOIN_PATH, { method: "PUT" })).status, 200);
  assertEquals((await app.request(JOIN_PATH, { method: "PUT" })).status, 200);
  const m = await rows(db, `SELECT state FROM memberships WHERE event_id=? AND device_id=?`, [
    E,
    D,
  ]);
  assertEquals(m.length, 1);
  assertEquals(m[0].state, "active");
  db.close();
});

Deno.test("join → a missing event is 404, a full event is 409 — told apart, never collapsed", async () => {
  const missing = await store();
  assertEquals(
    (await v2({ config: CONFIG, db: missing, fetch: recorder().fetchImpl })
      .request(JOIN_PATH, { method: "PUT" })).status,
    404,
  );
  missing.close();

  const db = await storeWithEvent({ capacity: 1 });
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN_PATH, { method: "PUT" });
  const second = await app.request(`/api/v2/events/${E}/devices/${D2}`, { method: "PUT" });
  assertEquals(second.status, 409);
  db.close();
});

// ── PUT the manifest ───────────────────────────────────────────────────────────────────────────────

Deno.test("manifest → refuses a non-member rather than silently enrolling it", async () => {
  const db = await storeWithEvent();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", resources: [RES("k")] }]),
  });
  assertEquals(res.status, 409);
  assertEquals((await rows(db, `SELECT 1 FROM memberships`)).length, 0);
  db.close();
});

Deno.test("manifest → records the declared roles and writes NO resource row", async () => {
  const db = await storeWithEvent();
  const res = await joinAndPublish(db, [{
    assetId: "A",
    creationDate: "2026-07-01T00:00:00Z",
    resources: [RES("A-primary.heic"), RES("A-live.mov", "live")],
  }]);
  assertEquals(res.status, 200);
  const ea = await rows(db, `SELECT roles FROM event_assets WHERE asset_id='A'`);
  assertEquals(JSON.parse(String(ea[0].roles)), ["primary", "live"]);
  // The byte upload is the sole writer of `resources` under v2.
  assertEquals((await rows(db, `SELECT 1 FROM resources`)).length, 0);
  db.close();
});

Deno.test("manifest → an omitted asset is RETRACTED, not merged", async () => {
  const db = await storeWithEvent();
  await joinAndPublish(db, [
    { assetId: "A", creationDate: "2026-07-01T00:00:00Z", resources: [RES("A-primary.heic")] },
    { assetId: "B", creationDate: "2026-07-02T00:00:00Z", resources: [RES("B-primary.heic")] },
  ]);
  await joinAndPublish(db, [
    { assetId: "A", creationDate: "2026-07-01T00:00:00Z", resources: [RES("A-primary.heic")] },
  ]);
  const kept = (await rows(db, `SELECT asset_id FROM event_assets ORDER BY asset_id`))
    .map((r) => r.asset_id);
  assertEquals(kept, ["A"]);
  db.close();
});

Deno.test("manifest → does not reactivate a departed membership", async () => {
  // Only join and leave write membership state under v2. In v1, publishing WAS enrolling.
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN_PATH, { method: "PUT" });
  await app.request(JOIN_PATH, { method: "DELETE" });
  await app.request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", resources: [RES("k")] }]),
  });
  const m = await rows(db, `SELECT state FROM memberships WHERE event_id=? AND device_id=?`, [
    E,
    D,
  ]);
  assertEquals(m[0].state, "departed");
  db.close();
});

// ── GET the union ──────────────────────────────────────────────────────────────────────────────────

Deno.test("union → an asset is served only when every DECLARED role has arrived", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN_PATH, { method: "PUT" });
  // A is complete; B declares two roles and only one arrives.
  for (
    const p of [
      `/api/v2/files/devices/${D}/A/primary?filename=IMG_A.HEIC`,
      `/api/v2/files/devices/${D}/B/primary?filename=IMG_B.HEIC`,
    ]
  ) await app.request(p, { method: "PUT", body: "b" });
  await app.request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([
      { assetId: "A", creationDate: "2026-07-01T00:00:00Z", resources: [RES("A-primary.heic")] },
      {
        assetId: "B",
        creationDate: "2026-07-02T00:00:00Z",
        resources: [RES("B-primary.heic"), RES("B-live.mov", "live")],
      },
    ]),
  });
  const body = await (await app.request(UNION_PATH)).json() as Record<string, unknown>[];
  assertEquals(body.map((a) => a.assetId), ["A"]);
  db.close();
});

Deno.test("union → an EXTRA held role does not make an asset incomplete (count equality would)", async () => {
  // THE case set-inclusion exists for. `resources` is device-scoped and `roles` is event-scoped, so a
  // device may hold a role this event does not declare. Comparing COUNTS would read 2 ≠ 1 and drop the
  // asset from an event it belongs in; comparing SETS asks the right question — is every DECLARED role
  // present — and serves it.
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN_PATH, { method: "PUT" });
  for (
    const p of [
      `/api/v2/files/devices/${D}/A/primary?filename=IMG_A.HEIC`,
      `/api/v2/files/devices/${D}/A/live?filename=IMG_A.MOV`,
    ]
  ) await app.request(p, { method: "PUT", body: "b" });
  // The event declares ONLY primary, while the device holds both.
  await app.request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([{
      assetId: "A",
      creationDate: "2026-07-01T00:00:00Z",
      resources: [RES("A-primary.heic")],
    }]),
  });
  const body = await (await app.request(UNION_PATH)).json() as Record<string, unknown>[];
  assertEquals(body.map((a) => a.assetId), ["A"]);
  assertEquals((body[0].resources as unknown[]).length, 1); // only the declared role is served
  db.close();
});

Deno.test("union → a resource declared but never uploaded does not silently shrink the asset", async () => {
  // An inner join would make the missing resource VANISH and serve the asset as whole. The asset must be
  // withheld entirely instead — a member offered bytes that do not exist is the failure this prevents.
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN_PATH, { method: "PUT" });
  await app.request(`/api/v2/files/devices/${D}/A/primary?filename=IMG_A.HEIC`, {
    method: "PUT",
    body: "b",
  });
  await app.request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([{
      assetId: "A",
      creationDate: "2026-07-01T00:00:00Z",
      resources: [RES("A-primary.heic"), RES("A-live.mov", "live")],
    }]),
  });
  const body = await (await app.request(UNION_PATH)).json() as unknown[];
  assertEquals(body, []);
  db.close();
});

// ── The fan-out ────────────────────────────────────────────────────────────────────────────────────

Deno.test("manifest → a failed fan-out does not fail the publish", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN_PATH, { method: "PUT" });
  // Register a push token for a second member, then break APNs by giving it an unusable key.
  await app.request(`/api/v2/events/${E}/devices/${D2}`, { method: "PUT" });
  await app.request(`/api/v2/devices/${D2}`, {
    method: "PUT",
    body: JSON.stringify({ pushToken: { kind: "apns", token: "t", env: "sandbox" } }),
  });
  const res = await app.request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", resources: [RES("k")] }]),
  });
  assertEquals(res.status, 200); // the publish stands whatever the fan-out did
  assert((await rows(db, `SELECT 1 FROM event_assets`)).length > 0);
  db.close();
});
