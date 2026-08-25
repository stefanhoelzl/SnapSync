import { assert, assertEquals } from "@std/assert";
import { createApp as createRealApp, type Deps, type FetchLike } from "../src/app.ts";
import { deleteByMs } from "../src/lifecycle.ts";
import { mintToken } from "../src/attest.ts";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { type Db, insertEvent, migrate } from "../src/db.ts";

// The whole API is gated on a device token (capability `device-attestation`), so every request in this
// file needs one. Rather than thread a header through every call site, `createApp` is shadowed here by a
// wrapper that pins the clock and attaches a valid token to each request.
//
// The GATE ITSELF is tested against the real, unwrapped app in attest.test.ts (an unauthenticated request
// must be refused). Both halves are needed: this file proves the gate does not break the routes; that one
// proves the gate is actually there.
//
// WHAT CHANGED WITH THE RELATIONAL STORE. These tests used to assert the SHAPE OF STORAGE TRAFFIC — which
// object key a route wrote, which directory it listed — because that traffic WAS the state. Relational
// state is observable directly, so a test now seeds rows and asserts rows. Storage traffic is still
// asserted for the one thing that still lives there: the photo BYTES.
//
// The store under test is a real SQLite (`node:sqlite`, in-process). Every invariant this change moved
// into the database — the cascade, the atomic publish, the exact capacity gate, `uploaded`'s monotonicity
// — is a property of SQL rather than of our code, and a hand-written double would assert our
// understanding of those statements instead of their behaviour. It needs no network, which matters: the
// test task withholds `--allow-net` deliberately, and that absence is what guarantees no test can reach a
// live store.
const NOW = Date.parse("2026-07-14T12:00:00Z");

const E = "7a3f9c21-0000-4000-8000-000000000001"; // an eventId
const D = "11111111-0000-4000-8000-000000000002"; // a deviceId
const D2 = "22222222-0000-4000-8000-000000000003"; // a second deviceId

const CONFIG = {
  zone: "snapsync-zone",
  host: "storage.bunnycdn.com",
  accessKey: "zone-password",
  s3Region: "de",
  s3Host: "de-s3.storage.bunnycdn.com",
  s3Scheme: "https",
  apnsKeyId: "ABC123KEYID",
  apnsTeamId: "E9Z8BADH58",
  apnsPrivateKey: "-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----\n",
  apnsTopic: "app.snapsync",
  attestTokenKey: "test-attest-token-key",
  appAttestRootCa: "",
  attestTokenTtlSeconds: 30 * 24 * 60 * 60,
  attestAppId: "E9Z8BADH58.app.snapsync",
  linkDomain: "snapsync.stho.net",
  appStoreUrl: "https://apps.apple.com/app/id6781692480",
  eventCapacity: 10,
  eventWindowMaxSeconds: 30 * 24 * 60 * 60,
  eventLifetimeSeconds: 30 * 24 * 60 * 60,
  databaseUrl: "",
  databaseToken: "",
};

const TOKEN = await mintToken(CONFIG, D, NOW);

const ZONE = `https://storage.bunnycdn.com/snapsync-zone`;
const S3_ZONE = `${CONFIG.s3Scheme}://${CONFIG.s3Host}/${CONFIG.zone}`;

const STARTS_AT = "2026-06-27T18:00:00Z";
const ENDS_AT = "2026-07-27T18:00:00Z"; // startsAt + the configured 30 days
const EVENT = {
  eventId: E,
  name: "Party",
  createdAt: "2026-06-27T00:00:00Z",
  startsAt: STARTS_AT,
  endsAt: ENDS_AT,
  capacity: 10,
  lifetimeSeconds: 30 * 24 * 60 * 60,
};

const BYTE_PATH = `/api/v1/files/devices/${D}/IMG_0001-photo.jpg`;
const BYTE_OBJ_URL = `${ZONE}/files/devices/${D}/IMG_0001-photo.jpg`;
const DEVLIST_PATH = `/api/v1/files/devices/${D}`;
const MANIFEST_PATH = `/api/v1/events/${E}/devices/${D}`;
const UNION_PATH = `/api/v1/events/${E}/files`;

type Call = { url: string; init: RequestInit };

/**
 * Records upstream STORAGE calls. Only the byte objects and the attestation record live there now, so the
 * fake is correspondingly small: a PUT answers `status` (201 by default), or throws when `throws`.
 */
function recorder(opts: { status?: number; throws?: boolean } = {}) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    if (opts.throws) return Promise.reject(new Error("network boom"));
    return Promise.resolve(new Response(null, { status: opts.status ?? 201 }));
  };
  return { calls, fetchImpl };
}

/** A migrated in-memory store. Every test gets its own, so none can observe another's rows. */
async function store(): Promise<Db & { close(): void }> {
  const db = sqliteDb(":memory:");
  await migrate(db);
  return db;
}

/** A store already holding {@link EVENT} — the starting point for every event-scoped route's tests. */
async function storeWithEvent(overrides: Partial<typeof EVENT> = {}) {
  const db = await store();
  await insertEvent(db, { ...EVENT, ...overrides });
  return db;
}

/** The real app, with the clock pinned and a valid device token attached to every request. */
function createApp(deps: Omit<Deps, "now">) {
  const app = createRealApp({ ...deps, now: () => NOW });
  const request = app.request.bind(app);
  return Object.assign(app, {
    request: (path: string, init: RequestInit = {}) =>
      request(path, {
        ...init,
        headers: { authorization: `Bearer ${TOKEN}`, ...(init.headers ?? {}) },
      }),
  });
}

/** One manifest body — the wire format (capability `device-manifest`), unchanged by this move. */
function manifest(
  assets: { assetId: string; creationDate: string; resources: Record<string, unknown>[] }[],
) {
  return JSON.stringify({ deviceId: D, assets });
}

// `key` is the BARE stored object name and `filename` the human capture name — different facts, and the
// download URL is built from the key. The fixtures keep them distinguishable so a route that confused
// them would fail here rather than at a 404 on someone's phone.
const RES = (key: string, role = "primary") => ({
  role,
  contentType: "image/heic",
  key,
  filename: `Capture ${key}`,
});

async function rows(db: Db, sql: string, args: unknown[] = []) {
  return (await db.execute(sql, args)).rows;
}

/**
 * Assert `url` is a presigned S3 GET for the bare object key `key`: path-style origin+path against the S3
 * endpoint, 7-day expiry, and an AWS4-HMAC-SHA256 signature. The signature is time-dependent, so this
 * asserts the shape, not an exact string.
 */
function assertPresigned(url: string, key: string) {
  const u = new URL(url);
  assertEquals(`${u.origin}${u.pathname}`, `${S3_ZONE}/${key}`);
  assertEquals(u.searchParams.get("X-Amz-Algorithm"), "AWS4-HMAC-SHA256");
  assertEquals(u.searchParams.get("X-Amz-Expires"), "604800");
  assert((u.searchParams.get("X-Amz-Signature") ?? "").length > 0);
}

// ── PUT /files/devices/:deviceId/:filename (byte upload) ───────────────────────────────────────────

Deno.test("byte PUT → forwards once with the bare key, AccessKey, content-type and body", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    headers: { "content-type": "image/jpeg" },
    body: "bytes",
  });
  assertEquals(res.status, 201);
  const put = calls.find((c) => c.init.method === "PUT")!;
  assertEquals(put.url, BYTE_OBJ_URL);
  assertEquals((put.init.headers as Record<string, string>).AccessKey, "zone-password");
  assertEquals((put.init.headers as Record<string, string>)["Content-Type"], "image/jpeg");
  db.close();
});

Deno.test("byte PUT → records the upload, so the listing and the union can see it", async () => {
  const db = await store();
  const { fetchImpl } = recorder();
  await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "bytes",
  });
  const stored = await rows(db, `SELECT device_id, key, uploaded FROM resources`);
  assertEquals(stored.length, 1);
  assertEquals(stored[0].device_id, D);
  // The BARE object name — the same key the manifest publish upserts on, so the repair path lands on
  // this row rather than creating a second one beside it.
  assertEquals(stored[0].key, "IMG_0001-photo.jpg");
  assertEquals(Number(stored[0].uploaded), 1);
  db.close();
});

Deno.test("byte PUT → a store failure does NOT fail the upload (best-effort record)", async () => {
  // The route's success is "the bytes landed". Failing it because a bookkeeping row did not land would
  // turn a successful upload into a retried one; the next manifest publish repairs the record.
  const db = await store();
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
    foreignKeysEnabled: () => Promise.resolve(true),
  };
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db: broken, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "bytes",
  });
  assertEquals(res.status, 201);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 1);
  db.close();
});

Deno.test("byte PUT → an encoded filename round-trips to an encoded, flat key", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
    `/api/v1/files/devices/${D}/IMG%200001%20photo.jpg`,
    { method: "PUT", body: "b" },
  );
  assertEquals(
    calls.find((c) => c.init.method === "PUT")!.url,
    `${ZONE}/files/devices/${D}/IMG%200001%20photo.jpg`,
  );
  db.close();
});

Deno.test("byte PUT → an encoded slash (%2F) in the filename → 400, no upstream request", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
    `/api/v1/files/devices/${D}/a%2Fb.jpg`,
    { method: "PUT", body: "b" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
  db.close();
});

Deno.test("byte PUT → missing content-type defaults to application/octet-stream", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  // A Blob with no `type` is the one body shape that reaches the handler with no content-type header at
  // all — a string body makes fetch supply `text/plain`, which would test fetch rather than the route.
  await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: new Blob(["b"]),
  });
  assertEquals(
    (calls.find((c) => c.init.method === "PUT")!.init.headers as Record<string, string>)[
      "Content-Type"
    ],
    "application/octet-stream",
  );
  db.close();
});

Deno.test("byte PUT → bunny error → 502, and nothing is recorded", async () => {
  const db = await store();
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "b",
  });
  assertEquals(res.status, 502);
  assertEquals((await rows(db, `SELECT * FROM resources`)).length, 0);
  db.close();
});

Deno.test("byte PUT → upstream throw → 502", async () => {
  const db = await store();
  const { fetchImpl } = recorder({ throws: true });
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "b",
  });
  assertEquals(res.status, 502);
  db.close();
});

Deno.test("byte PUT → non-UUID device segment → 400, no upstream request", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
    `/api/v1/files/devices/not-a-uuid/x.jpg`,
    { method: "PUT", body: "b" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
  db.close();
});

Deno.test("byte OPTIONS → 204, no resumable advertised, no upstream request", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "OPTIONS",
  });
  assertEquals(res.status, 204);
  assertEquals(res.headers.get("Allow"), "PUT, OPTIONS");
  assertEquals(calls.length, 0);
  db.close();
});

// ── PUT /events/:eventId/devices/:deviceId (the manifest publish) ──────────────────────────────────

Deno.test("manifest publish → enrolls the device and records its assets, 201", async () => {
  const db = await storeWithEvent();
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([{
      assetId: "A",
      creationDate: "2026-07-01T00:00:00Z",
      resources: [RES("a.heic")],
    }]),
  });
  assertEquals(res.status, 201);
  assertEquals(
    await rows(db, `SELECT state FROM memberships WHERE event_id=? AND device_id=?`, [
      E,
      D,
    ]),
    [{ state: "active" }],
  );
  assertEquals(
    (await rows(db, `SELECT asset_id FROM event_assets WHERE event_id=?`, [E])).length,
    1,
  );
  // The manifest is a wire format now: it is recorded, not stored. Nothing goes to the object store.
  assertEquals(calls.length, 0);
  db.close();
});

Deno.test("manifest publish → is a FULL-STATE replace: an omitted asset is removed", async () => {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const publish = (assetIds: string[]) =>
    app.request(MANIFEST_PATH, {
      method: "PUT",
      body: manifest(
        assetIds.map((id) => ({
          assetId: id,
          creationDate: "2026-07-01T00:00:00Z",
          resources: [RES(`${id}.heic`)],
        })),
      ),
    });
  await publish(["A", "C"]);
  await publish(["A", "B"]);
  const kept =
    (await rows(db, `SELECT asset_id FROM event_assets WHERE event_id=? ORDER BY asset_id`, [
      E,
    ])).map((r) => r.asset_id);
  assertEquals(kept, ["A", "B"]);
  db.close();
});

Deno.test("manifest publish → repairs an upload record the byte route lost", async () => {
  // The byte route's write is best-effort. This is the repair path that makes that collapse safe: the
  // manifest lists only COMPLETED resources, so an entry that does not say otherwise means `uploaded`.
  const db = await storeWithEvent();
  await db.execute(
    `INSERT INTO resources (device_id, key, asset_id, role, content_type, filename, uploaded)
     VALUES (?, ?, 'A', 'primary', 'image/heic', 'a.heic', 0)`,
    [D, "a.heic"],
  );
  await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(MANIFEST_PATH, {
    method: "PUT",
    body: manifest([{
      assetId: "A",
      creationDate: "2026-07-01T00:00:00Z",
      resources: [RES("a.heic")],
    }]),
  });
  assertEquals(
    Number((await rows(db, `SELECT uploaded FROM resources WHERE device_id=?`, [D]))[0].uploaded),
    1,
  );
  db.close();
});

Deno.test("manifest publish → `uploaded` is monotone: a false entry cannot un-say an upload", async () => {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const body = (uploaded: boolean) =>
    manifest([{
      assetId: "A",
      creationDate: "2026-07-01T00:00:00Z",
      resources: [{ ...RES("a.heic"), uploaded }],
    }]);
  await app.request(MANIFEST_PATH, { method: "PUT", body: body(true) });
  await app.request(MANIFEST_PATH, { method: "PUT", body: body(false) });
  assertEquals(
    Number((await rows(db, `SELECT uploaded FROM resources WHERE device_id=?`, [D]))[0].uploaded),
    1,
  );
  db.close();
});

Deno.test("manifest publish → missing event → 404, nothing written", async () => {
  const db = await store();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    MANIFEST_PATH,
    { method: "PUT", body: manifest([]) },
  );
  assertEquals(res.status, 404);
  assertEquals((await rows(db, `SELECT * FROM memberships`)).length, 0);
  db.close();
});

Deno.test("manifest publish → a body that is not a manifest → 400, nothing written", async () => {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  for (
    const body of [
      "not json",
      JSON.stringify({}),
      JSON.stringify({ assets: [{ assetId: "A" }] }),
      JSON.stringify({ assets: [{ assetId: "A", creationDate: "x", resources: [] }] }),
      JSON.stringify({
        assets: [{ assetId: "A", creationDate: "x", resources: [{ role: "primary" }] }],
      }),
    ]
  ) {
    const res = await app.request(MANIFEST_PATH, { method: "PUT", body });
    assertEquals(res.status, 400, `body: ${body}`);
  }
  assertEquals((await rows(db, `SELECT * FROM event_assets`)).length, 0);
  db.close();
});

Deno.test("manifest publish → an unknown resource field is ignored, not refused", async () => {
  // A backend that refused a field a future client adds would break every device the moment that client
  // shipped.
  const db = await storeWithEvent();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    MANIFEST_PATH,
    {
      method: "PUT",
      body: manifest([{
        assetId: "A",
        creationDate: "2026-07-01T00:00:00Z",
        resources: [{ ...RES("a.heic"), somethingNew: 42 }],
      }]),
    },
  );
  assertEquals(res.status, 201);
  db.close();
});

Deno.test("manifest publish → non-UUID event or device → 400", async () => {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  for (
    const path of [`/api/v1/events/nope/devices/${D}`, `/api/v1/events/${E}/devices/nope`]
  ) {
    assertEquals((await app.request(path, { method: "PUT", body: manifest([]) })).status, 400);
  }
  db.close();
});

// ── Capacity (capability `event-limits`) ───────────────────────────────────────────────────────────

Deno.test("capacity → a NEW device at capacity → 409, nothing written", async () => {
  const db = await storeWithEvent({ capacity: 1 });
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(`/api/v1/events/${E}/devices/${D2}`, { method: "PUT", body: manifest([]) });
  const res = await app.request(MANIFEST_PATH, { method: "PUT", body: manifest([]) });
  assertEquals(res.status, 409);
  assertEquals((await rows(db, `SELECT * FROM memberships WHERE device_id=?`, [D])).length, 0);
  db.close();
});

Deno.test("capacity → a KNOWN device always passes, at capacity or not", async () => {
  const db = await storeWithEvent({ capacity: 1 });
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await app.request(MANIFEST_PATH, { method: "PUT", body: manifest([]) })).status,
    201,
  );
  assertEquals(
    (await app.request(MANIFEST_PATH, { method: "PUT", body: manifest([]) })).status,
    201,
  );
  db.close();
});

Deno.test("capacity → leaving frees NO slot, and a rejoin reuses the departed device's own", async () => {
  const db = await storeWithEvent({ capacity: 1 });
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(MANIFEST_PATH, { method: "PUT", body: manifest([]) });
  await app.request(MANIFEST_PATH, { method: "DELETE" });
  // A different, never-seen device is still refused …
  assertEquals(
    (await app.request(`/api/v1/events/${E}/devices/${D2}`, { method: "PUT", body: manifest([]) }))
      .status,
    409,
  );
  // … while the departed device rejoins into the slot it already held.
  assertEquals(
    (await app.request(MANIFEST_PATH, { method: "PUT", body: manifest([]) })).status,
    201,
  );
  assertEquals((await rows(db, `SELECT * FROM memberships WHERE event_id=?`, [E])).length, 1);
  db.close();
});

Deno.test("capacity → concurrent first enrollments do NOT overshoot", async () => {
  // The old read-then-write gate admitted every racer; the conditional statement admits exactly the
  // remaining capacity. This is the property that let `event-limits`' accepted-overshoot caveat be
  // retired rather than carried forward.
  const db = await storeWithEvent({ capacity: 3 });
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const devices = Array.from(
    { length: 10 },
    (_, i) => `33333333-0000-4000-8000-0000000000${String(i).padStart(2, "0")}`,
  );
  const results = await Promise.all(
    devices.map((d) =>
      app.request(`/api/v1/events/${E}/devices/${d}`, { method: "PUT", body: manifest([]) })
    ),
  );
  assertEquals(results.filter((r) => r.status === 201).length, 3);
  assertEquals(results.filter((r) => r.status === 409).length, 7);
  assertEquals((await rows(db, `SELECT * FROM memberships WHERE event_id=?`, [E])).length, 3);
  db.close();
});

Deno.test("capacity → a zero-row enrollment tells `full` and `no such event` apart", async () => {
  // Both reach the statement as "zero rows affected", because the capacity subquery yields NULL for a
  // missing event. Collapsing them would answer 409 for an event that does not exist.
  const empty = await store();
  const full = await storeWithEvent({ capacity: 0 });
  const app = (db: Db) => createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await app(empty).request(MANIFEST_PATH, { method: "PUT", body: manifest([]) })).status,
    404,
  );
  assertEquals(
    (await app(full).request(MANIFEST_PATH, { method: "PUT", body: manifest([]) })).status,
    409,
  );
  empty.close();
  full.close();
});

// ── GET /files/devices/:deviceId (per-device listing) ──────────────────────────────────────────────

Deno.test("device list → { filename, url } only, and only for uploaded resources", async () => {
  const db = await store();
  await db.execute(
    `INSERT INTO resources (device_id, key, asset_id, role, content_type, filename, uploaded)
     VALUES (?, 'a.heic', 'A', 'primary', 'image/heic', 'Capture a.heic', 1),
            (?, 'b.heic', 'B', 'primary', 'image/heic', 'Capture b.heic', 0)`,
    [D, D],
  );
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  const body = await res.json() as Record<string, unknown>[];
  assertEquals(body.length, 1);
  assertEquals(Object.keys(body[0]).sort(), ["filename", "url"]);
  assertEquals(body[0].filename, "a.heic");
  assertPresigned(String(body[0].url), `files/devices/${D}/a.heic`);
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  // Served from the record; storage is not enumerated.
  assertEquals(calls.length, 0);
  db.close();
});

Deno.test("device list → a device with nothing stored → 200 []", async () => {
  const db = await store();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    DEVLIST_PATH,
  );
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
  db.close();
});

Deno.test("device list → a store failure → 502, never a partial list", async () => {
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
    foreignKeysEnabled: () => Promise.resolve(true),
  };
  const res = await createApp({ config: CONFIG, db: broken, fetch: recorder().fetchImpl }).request(
    DEVLIST_PATH,
  );
  assertEquals(res.status, 502);
});

Deno.test("device list → non-UUID device → 400; wrong method → 404", async () => {
  const db = await store();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals((await app.request(`/api/v1/files/devices/nope`)).status, 400);
  assertEquals((await app.request(DEVLIST_PATH, { method: "POST" })).status, 404);
  db.close();
});

// ── POST /events (create) ──────────────────────────────────────────────────────────────────────────

Deno.test("POST /events → 201 with the event, and one row written", async () => {
  const db = await store();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v1/events",
    { method: "POST", body: JSON.stringify({ name: "Party", startsAt: STARTS_AT }) },
  );
  assertEquals(res.status, 201);
  const body = await res.json() as Record<string, unknown>;
  assertEquals(Object.keys(body).sort(), [
    "capacity",
    "createdAt",
    "deletesAt",
    "endsAt",
    "eventId",
    "name",
    "startsAt",
  ]);
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
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v1/events",
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
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v1/events",
    { method: "POST", body: JSON.stringify({ name: "Party", startsAt: STARTS_AT }) },
  );
  assertEquals((await res.json() as Record<string, unknown>).endsAt, ENDS_AT);
  db.close();
});

Deno.test("POST /events → a creator-supplied endsAt within the maximum is stamped verbatim", async () => {
  const db = await store();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v1/events",
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
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
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
    assertEquals((await app.request("/api/v1/events", { method: "POST", body })).status, 400, body);
  }
  assertEquals((await rows(db, `SELECT * FROM events`)).length, 0);
  db.close();
});

Deno.test("POST /events → the name is trimmed before it is stored and echoed", async () => {
  const db = await store();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/api/v1/events",
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
    foreignKeysEnabled: () => Promise.resolve(true),
  };
  const res = await createApp({ config: CONFIG, db: broken, fetch: recorder().fetchImpl }).request(
    "/api/v1/events",
    { method: "POST", body: JSON.stringify({ name: "Party", startsAt: STARTS_AT }) },
  );
  assertEquals(res.status, 502);
});

// ── GET /events/:eventId (metadata and existence) ──────────────────────────────────────────────────

Deno.test("GET /events/:id → 200 with the stored fields and the derived deletesAt", async () => {
  const db = await storeWithEvent();
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v1/events/${E}`,
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
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals((await app.request(`/api/v1/events/${E}`)).status, 404);
  assertEquals((await app.request(`/api/v1/events/nope`)).status, 400);
  db.close();
});

Deno.test("GET /events/:id → a store failure is 502, never 404", async () => {
  // The distinction is load-bearing outside this file: `leave-event`'s two-witness teardown acts on a
  // 404, so a transient fault reported as absence would tear a live membership down.
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
    foreignKeysEnabled: () => Promise.resolve(true),
  };
  const res = await createApp({ config: CONFIG, db: broken, fetch: recorder().fetchImpl }).request(
    `/api/v1/events/${E}`,
  );
  assertEquals(res.status, 502);
});

Deno.test("GET /events/:id → an event past its delete-by is still served (no route reaps on touch)", async () => {
  const db = await storeWithEvent({ lifetimeSeconds: 24 * 60 * 60 });
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v1/events/${E}`,
  );
  assertEquals(res.status, 200);
  assertEquals((await rows(db, `SELECT * FROM events`)).length, 1);
  db.close();
});

// ── PATCH /events/:eventId (rename) ────────────────────────────────────────────────────────────────

Deno.test("PATCH /events/:id → 200, and ONLY the name changes", async () => {
  const db = await storeWithEvent();
  const before = (await rows(db, `SELECT * FROM events WHERE id=?`, [E]))[0];
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v1/events/${E}`,
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
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await createApp({ config: CONFIG, db: empty, fetch: recorder().fetchImpl }).request(
      `/api/v1/events/${E}`,
      { method: "PATCH", body: JSON.stringify({ name: "x" }) },
    )).status,
    404,
  );
  for (const body of ["not json", JSON.stringify({}), JSON.stringify({ name: "  " })]) {
    assertEquals((await app.request(`/api/v1/events/${E}`, { method: "PATCH", body })).status, 400);
  }
  assertEquals(
    (await app.request(`/api/v1/events/nope`, {
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
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v1/events/${E}`,
    { method: "PUT", body: "{}" },
  );
  assertEquals(res.status, 404);
  db.close();
});

// ── GET /events/:eventId/files (the union) ─────────────────────────────────────────────────────────

/** Publish a manifest for `deviceId` into the event under test. */
async function publish(
  db: Db,
  deviceId: string,
  assets: { assetId: string; creationDate: string; keys: string[] }[],
) {
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const res = await app.request(`/api/v1/events/${E}/devices/${deviceId}`, {
    method: "PUT",
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
  assertEquals(res.status, 201);
}

Deno.test("union → both devices' assets, flattened, tagged by deviceId, uncacheable", async () => {
  const db = await storeWithEvent();
  await publish(db, D, [{
    assetId: "A",
    creationDate: "2026-07-01T00:00:00Z",
    keys: ["a.heic", "a.mov"],
  }]);
  await publish(db, D2, [{ assetId: "B", creationDate: "2026-07-02T00:00:00Z", keys: ["b.heic"] }]);

  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
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
  assertPresigned(String(resources[0].url), `files/devices/${D}/a.heic`);
  db.close();
});

Deno.test("union → an asset naming an unrecorded resource is omitted entirely", async () => {
  // Defense-in-depth: the manifest lists only uploaded resources, so this catches the residual case.
  const db = await storeWithEvent();
  await publish(db, D, [
    { assetId: "A", creationDate: "2026-07-01T00:00:00Z", keys: ["a.heic"] },
    { assetId: "B", creationDate: "2026-07-02T00:00:00Z", keys: ["b.heic", "b.mov"] },
  ]);
  await db.execute(`UPDATE resources SET uploaded = 0 WHERE key = ?`, ["b.mov"]);
  const body = await (await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    UNION_PATH,
  )).json() as Record<string, unknown>[];
  assertEquals(body.map((a) => a.assetId), ["A"]);
  db.close();
});

Deno.test("union → a departed member's photos remain until the event is deleted", async () => {
  const db = await storeWithEvent();
  await publish(db, D, [{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", keys: ["a.heic"] }]);
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await app.request(`/api/v1/events/${E}/devices/${D}`, { method: "DELETE" })).status,
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
    (await createApp({ config: CONFIG, db: empty, fetch: recorder().fetchImpl }).request(
      UNION_PATH,
    ))
      .status,
    404,
  );
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    UNION_PATH,
  );
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
  empty.close();
  db.close();
});

Deno.test("union → non-UUID event → 400; wrong method → 404", async () => {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals((await app.request(`/api/v1/events/nope/files`)).status, 400);
  assertEquals((await app.request(UNION_PATH, { method: "POST" })).status, 404);
  db.close();
});

Deno.test("union → a store failure → 502, never a partial union", async () => {
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
    foreignKeysEnabled: () => Promise.resolve(true),
  };
  const res = await createApp({ config: CONFIG, db: broken, fetch: recorder().fetchImpl }).request(
    UNION_PATH,
  );
  assertEquals(res.status, 502);
});

// ── DELETE /events/:eventId/devices/:deviceId (leave) ──────────────────────────────────────────────

Deno.test("leave → marks the membership departed and keeps its assets", async () => {
  const db = await storeWithEvent();
  await publish(db, D, [{ assetId: "A", creationDate: "2026-07-01T00:00:00Z", keys: ["a.heic"] }]);
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v1/events/${E}/devices/${D}`,
    { method: "DELETE" },
  );
  assertEquals(res.status, 200);
  assertEquals(await rows(db, `SELECT state FROM memberships WHERE device_id=?`, [D]), [{
    state: "departed",
  }]);
  assertEquals((await rows(db, `SELECT * FROM event_assets WHERE device_id=?`, [D])).length, 1);
  db.close();
});

Deno.test("leave → is idempotent, and a leave by a non-member changes nothing", async () => {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const path = `/api/v1/events/${E}/devices/${D}`;
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
    (await createApp({ config: CONFIG, db: empty, fetch: recorder().fetchImpl }).request(
      `/api/v1/events/${E}/devices/${D}`,
      { method: "DELETE" },
    )).status,
    404,
  );
  assertEquals(
    (await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
      `/api/v1/events/nope/devices/${D}`,
      { method: "DELETE" },
    )).status,
    400,
  );
  empty.close();
  db.close();
});

// ── PUT /devices/:deviceId (the device config document) ────────────────────────────────────────────

Deno.test("device config → the document is recorded verbatim, last-write-wins", async () => {
  const db = await store();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const doc = (token: string) =>
    JSON.stringify({ pushToken: { kind: "apns", token, env: "sandbox" } });
  assertEquals(
    (await app.request(`/api/v1/devices/${D}`, { method: "PUT", body: doc("first") })).status,
    201,
  );
  assertEquals(
    (await app.request(`/api/v1/devices/${D}`, { method: "PUT", body: doc("second") })).status,
    201,
  );
  const stored = await rows(db, `SELECT push_token FROM device_records WHERE device_id=?`, [D]);
  assertEquals(stored.length, 1);
  assertEquals(JSON.parse(String(stored[0].push_token)).pushToken.token, "second");
  db.close();
});

Deno.test("device config → non-UUID → 400; non-JSON → 400; wrong method → 404", async () => {
  const db = await store();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals(
    (await app.request(`/api/v1/devices/nope`, { method: "PUT", body: "{}" })).status,
    400,
  );
  assertEquals(
    (await app.request(`/api/v1/devices/${D}`, { method: "PUT", body: "not json" })).status,
    400,
  );
  assertEquals((await app.request(`/api/v1/devices/${D}`)).status, 404);
  db.close();
});

Deno.test("device config → the document is not a resource: it never reaches the listing", async () => {
  const db = await store();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(`/api/v1/devices/${D}`, { method: "PUT", body: JSON.stringify({ a: 1 }) });
  assertEquals(await (await app.request(DEVLIST_PATH)).json(), []);
  db.close();
});

// ── POST /events/:eventId/notify ───────────────────────────────────────────────────────────────────

/**
 * A REAL ES256 key, generated per run. The APNs sender signs its provider JWT lazily and catches a
 * signing failure PER TOKEN — so with the placeholder PEM the rest of this file uses, every push is
 * reported failed and none is ever sent. That is faithful to the route's best-effort contract, but it
 * would make a fan-out test pass while asserting nothing.
 */
async function apnsConfig() {
  const kp = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", kp.privateKey));
  let bin = "";
  for (const b of pkcs8) bin += String.fromCharCode(b);
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(bin)}\n-----END PRIVATE KEY-----\n`;
  return { ...CONFIG, apnsPrivateKey: pem };
}

/** An APNs-shaped fetch fake: records the pushes and answers each with `status`. */
function apnsRecorder(status = 200) {
  const pushed: string[] = [];
  const fetchImpl: FetchLike = (url) => {
    if (url.includes("api.push.apple.com") || url.includes("api.sandbox.push.apple.com")) {
      pushed.push(url.split("/").pop()!);
      return Promise.resolve(new Response(null, { status }));
    }
    return Promise.resolve(new Response(null, { status: 201 }));
  };
  return { pushed, fetchImpl };
}

async function registerToken(db: Db, deviceId: string, token: string) {
  await db.execute(
    `INSERT INTO device_records (device_id, push_token, updated_at) VALUES (?, ?, '2026-07-14T00:00:00Z')`,
    [deviceId, JSON.stringify({ pushToken: { kind: "apns", token, env: "sandbox" } })],
  );
}

Deno.test("notify → 202, and a departed member is not pushed", async () => {
  const db = await storeWithEvent();
  await publish(db, D, []);
  await publish(db, D2, []);
  await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    `/api/v1/events/${E}/devices/${D2}`,
    { method: "DELETE" },
  );
  await registerToken(db, D, "token-active");
  await registerToken(db, D2, "token-departed");

  const { pushed, fetchImpl } = apnsRecorder();
  const res = await createApp({ config: await apnsConfig(), db, fetch: fetchImpl }).request(
    `/api/v1/events/${E}/notify`,
    { method: "POST" },
  );
  assertEquals(res.status, 202);
  assertEquals(pushed, ["token-active"]);
  db.close();
});

Deno.test("notify → a member with no registered token is skipped; still 202", async () => {
  const db = await storeWithEvent();
  await publish(db, D, []);
  await publish(db, D2, []);
  await registerToken(db, D, "token-active");
  const { pushed, fetchImpl } = apnsRecorder();
  const res = await createApp({ config: await apnsConfig(), db, fetch: fetchImpl }).request(
    `/api/v1/events/${E}/notify`,
    { method: "POST" },
  );
  assertEquals(res.status, 202);
  assertEquals(pushed, ["token-active"]);
  db.close();
});

Deno.test("notify → an APNs rejection still yields 202 (best-effort fan-out)", async () => {
  const db = await storeWithEvent();
  await publish(db, D, []);
  await registerToken(db, D, "token-gone");
  const { fetchImpl } = apnsRecorder(410);
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
    `/api/v1/events/${E}/notify`,
    { method: "POST" },
  );
  assertEquals(res.status, 202);
  db.close();
});

Deno.test("notify → an event with no members notifies vacuously; 202, no push", async () => {
  const db = await storeWithEvent();
  const { pushed, fetchImpl } = apnsRecorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
    `/api/v1/events/${E}/notify`,
    { method: "POST" },
  );
  assertEquals(res.status, 202);
  assertEquals(pushed, []);
  db.close();
});

Deno.test("notify → unknown event → 404, no push; wrong method → 404", async () => {
  const empty = await store();
  const db = await storeWithEvent();
  const { pushed, fetchImpl } = apnsRecorder();
  assertEquals(
    (await createApp({ config: CONFIG, db: empty, fetch: fetchImpl }).request(
      `/api/v1/events/${E}/notify`,
      { method: "POST" },
    )).status,
    404,
  );
  assertEquals(pushed, []);
  assertEquals(
    (await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
      `/api/v1/events/${E}/notify`,
    ))
      .status,
    404,
  );
  empty.close();
  db.close();
});

// ── The lifecycle derivation (capability `event-limits`) ───────────────────────────────────────────

Deno.test("deleteByMs → anchors at max(createdAt, startsAt) and adds the stamped lifetime", () => {
  const day = 24 * 60 * 60;
  // Back-dated: `startsAt` weeks before `createdAt` → anchored at createdAt, so it is not born expired.
  assertEquals(
    deleteByMs({
      createdAt: "2026-07-01T00:00:00Z",
      startsAt: "2026-06-01T00:00:00Z",
      lifetimeSeconds: day,
    }),
    Date.parse("2026-07-02T00:00:00Z"),
  );
  // Created early: `startsAt` weeks after `createdAt` → anchored at startsAt, so it outlives its window.
  assertEquals(
    deleteByMs({
      createdAt: "2026-06-01T00:00:00Z",
      startsAt: "2026-07-01T00:00:00Z",
      lifetimeSeconds: day,
    }),
    Date.parse("2026-07-02T00:00:00Z"),
  );
});

Deno.test("deleteByMs → NaN when neither anchor parses", () => {
  assert(Number.isNaN(deleteByMs({ createdAt: "nope", startsAt: "nope", lifetimeSeconds: 60 })));
});

// ── GET /health (the deploy boot probe's target) ───────────────────────────────────────────────────

Deno.test("health → reports the bundle's commit and the store's state, uncacheable", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const app = createRealApp({ config: CONFIG, db, fetch: fetchImpl, buildSha: "abc123" });
  const res = await app.request("/health");
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { sha: "abc123", database: "ok" });
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  assertEquals(calls.length, 0); // no storage call
  db.close();
});

Deno.test("health → foreign keys OFF is reported, not hidden behind a 200", async () => {
  // A store serving with constraints silently disabled is a green deploy over a broken invariant. The
  // route stays total — it REPORTS the state and the probe decides what it means.
  const off: Db = {
    execute: () => Promise.resolve({ rows: [], rowsAffected: 0 }),
    batch: () => Promise.resolve(),
    transaction: () => Promise.reject(new Error("unused")),
    foreignKeysEnabled: () => Promise.resolve(false),
  };
  const res = await createRealApp({
    config: CONFIG,
    db: off,
    fetch: recorder().fetchImpl,
    buildSha: "pinned",
  }).request("/health");
  assertEquals(await res.json(), { sha: "pinned", database: "foreign-keys-off" });
});

Deno.test("health → an unreachable store is distinguishable from a misprovisioned one", async () => {
  const down: Db = {
    execute: () => Promise.reject(new Error("no route to host")),
    batch: () => Promise.reject(new Error("no route to host")),
    transaction: () => Promise.reject(new Error("no route to host")),
    foreignKeysEnabled: () => Promise.reject(new Error("no route to host")),
  };
  const res = await createRealApp({ config: CONFIG, db: down, fetch: recorder().fetchImpl })
    .request(
      "/health",
    );
  assertEquals((await res.json() as Record<string, unknown>).database, "unreachable");
});

Deno.test("health → HEAD returns the headers with no body", async () => {
  const db = await store();
  const res = await createRealApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/health",
    { method: "HEAD" },
  );
  assertEquals(res.status, 200);
  assertEquals(await res.text(), "");
  db.close();
});

// ── Presigned URLs ─────────────────────────────────────────────────────────────────────────────────

Deno.test("presigned download URLs carry the configured scheme, not a hardcoded https", async () => {
  const db = await store();
  await db.execute(
    `INSERT INTO resources (device_id, key, asset_id, role, content_type, filename, uploaded)
     VALUES (?, ?, 'A', 'primary', 'image/heic', 'a.heic', 1)`,
    [D, "a.heic"],
  );
  const httpConfig = { ...CONFIG, s3Scheme: "http", s3Host: "127.0.0.1:8080" };
  const res = await createApp({ config: httpConfig, db, fetch: recorder().fetchImpl }).request(
    DEVLIST_PATH,
  );
  const body = await res.json() as Record<string, unknown>[];
  assert(String(body[0].url).startsWith("http://127.0.0.1:8080/"));
  db.close();
});
