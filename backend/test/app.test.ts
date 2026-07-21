import { assert, assertEquals } from "@std/assert";
import { classifyEvent, createApp as createRealApp, type Deps, type FetchLike } from "../src/app.ts";
import { mintToken } from "../src/attest.ts";

// The whole API is gated on a device token (capability `device-attestation`), so every request in this
// file needs one. Rather than thread a header through ~100 call sites, `createApp` is shadowed here by a
// wrapper that pins the clock and attaches a valid token to each request — leaving every pre-existing
// test reading exactly as it did, and testing exactly what it did.
//
// The GATE ITSELF is tested against the real, unwrapped app in attest.test.ts (an unauthenticated request
// must be refused). Both halves are needed: this file proves the gate does not break the routes; that one
// proves the gate is actually there.
const NOW = Date.parse("2026-07-14T12:00:00Z");

const E = "7a3f9c21-0000-4000-8000-000000000001"; // an eventId
const D = "11111111-0000-4000-8000-000000000002"; // a deviceId
const URLBASE = "https://edge.example";

const CONFIG = {
  zone: "snapsync-zone",
  host: "storage.bunnycdn.com",
  accessKey: "zone-password",
  s3Region: "de",
  s3Host: "de-s3.storage.bunnycdn.com",
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
  eventDurationSeconds: 30 * 24 * 60 * 60,
  eventGraceSeconds: 24 * 60 * 60,
};

const TOKEN = await mintToken(CONFIG, "11111111-0000-4000-8000-000000000002", NOW);

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

const ZONE = `https://storage.bunnycdn.com/snapsync-zone`;
// The presigned-download S3 endpoint (path-style: `<s3Host>/<zone>/<key>`).
const S3_ZONE = `https://${CONFIG.s3Host}/${CONFIG.zone}`;
const MARKER_URL = `${ZONE}/events/${E}/metadata.json`; // event registry marker
// `startsAt` is the CANONICAL cutoff shape (second precision, no fraction) while `createdAt` is whatever
// `toISOString()` mints — the two are different facts and deliberately different shapes. `endsAt` and
// `capacity` are the limits POST /events stamps (capability `event-limits`); NOW (2026-07-14) is inside
// [startsAt, endsAt], so this marker is LIVE for every pre-existing test.
const STARTS_AT = "2026-06-27T18:00:00Z";
const ENDS_AT = "2026-07-27T18:00:00Z"; // startsAt + the configured 30 days
const MARKER_BODY = {
  eventId: E,
  name: "Party",
  createdAt: "2026-06-27T00:00:00Z",
  startsAt: STARTS_AT,
  endsAt: ENDS_AT,
  capacity: 10,
};
const markerPresent = { [MARKER_URL]: { body: MARKER_BODY } };
// Lifecycle variants for the event-limits tests: endsAt 12h before NOW (inside the 1-day grace) and
// 4 days before NOW (expired — past endsAt + grace).
const GRACE_MARKER = { ...MARKER_BODY, endsAt: "2026-07-14T00:00:00Z" };
const EXPIRED_MARKER = { ...MARKER_BODY, endsAt: "2026-07-10T00:00:00Z" };

/**
 * Assert `url` is a presigned S3 GET for the bare object key `key` (e.g. `files/devices/<D>/A-primary.heic`):
 * path-style origin+path against the S3 endpoint, 7-day expiry, and an AWS4-HMAC-SHA256 signature. The
 * signature itself is time-dependent (X-Amz-Date), so we assert the shape, not an exact string.
 */
function assertPresigned(url: string, key: string) {
  const u = new URL(url);
  assertEquals(`${u.origin}${u.pathname}`, `${S3_ZONE}/${key}`);
  assertEquals(u.searchParams.get("X-Amz-Algorithm"), "AWS4-HMAC-SHA256");
  assertEquals(u.searchParams.get("X-Amz-Expires"), "604800");
  assertEquals(u.searchParams.get("X-Amz-SignedHeaders"), "host");
  assert(u.searchParams.get("X-Amz-Credential")?.includes("/de/s3/aws4_request"));
  assert((u.searchParams.get("X-Amz-Signature") ?? "").length > 0);
}

// Byte WRITE route (`bunny-upload-endpoint`): device-partitioned, event-independent.
const BYTE_PATH = `/files/devices/${D}/IMG_0001-photo.jpg`;
const BYTE_OBJ_URL = `${ZONE}/files/devices/${D}/IMG_0001-photo.jpg`;
// Per-device list (`bunny-list-endpoint`).
const DEVLIST_PATH = `/files/devices/${D}`;
const DEVDIR_URL = `${ZONE}/files/devices/${D}/`;
// Device-manifest write (`bunny-upload-endpoint`, gated).
const DEVMANIFEST_PATH = `/events/${E}/devices/${D}`;
const DEVMANIFEST_URL = `${ZONE}/events/${E}/devices/${D}.json`;

type Call = { url: string; init: RequestInit };

const putCall = (calls: Call[]) => calls.find((c) => c.init.method === "PUT")!;

/**
 * Records upstream calls. By default a GET of the event marker returns a present, LIVE marker and a GET
 * of a `devices/` directory returns an empty LIST (→ the writing device is NEW, well under capacity —
 * the limits gate passes); a PUT returns `status` (201) — or throws if `throws`. Set `marker` to model
 * an absent ("absent" → 404) or failing ("fail" → 500) marker read.
 */
function recorder(
  opts: { status?: number; throws?: boolean; marker?: "present" | "absent" | "fail" } = {},
) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    if (init.method === "GET") {
      if (url.endsWith("/devices/")) {
        return Promise.resolve(
          new Response("[]", { status: 200, headers: { "content-type": "application/json" } }),
        );
      }
      const m = opts.marker ?? "present";
      if (m === "absent") return Promise.resolve(new Response(null, { status: 404 }));
      if (m === "fail") return Promise.resolve(new Response("boom", { status: 500 }));
      return Promise.resolve(
        new Response(JSON.stringify(MARKER_BODY), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
    }
    if (opts.throws) return Promise.reject(new Error("network boom"));
    return Promise.resolve(new Response(null, { status: opts.status ?? 201 }));
  };
  return { calls, fetchImpl };
}

// ── PUT /files/devices/:deviceId/:filename (byte upload, UNGATED) ──────────────────────────────────

Deno.test("byte PUT → forwards once with bare files/devices/<device>/<name> key, AccessKey, content-type, body", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "hello-bytes",
    headers: { "content-type": "image/jpeg" },
  });

  assertEquals(res.status, 201);
  assertEquals(calls.length, 1); // UNGATED: exactly one object PUT, NO marker GET
  const call = putCall(calls);
  assertEquals(call.url, BYTE_OBJ_URL);
  const h = new Headers(call.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "image/jpeg");
  assertEquals(await new Response(call.init.body as BodyInit).text(), "hello-bytes");
});

Deno.test("byte PUT → encoded filename round-trips to an encoded, flat key on the wire", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/devices/${D}/IMG%20001.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 201);
  assertEquals(putCall(calls).url, `${ZONE}/files/devices/${D}/IMG%20001.jpg`);
});

Deno.test("byte PUT → encoded slash (%2F) in filename → 400 (would un-flatten the key)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/devices/${D}/a%2Fb.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("byte PUT → missing content-type defaults to application/octet-stream", async () => {
  const { calls, fetchImpl } = recorder();
  const req = new Request(`${URLBASE}${BYTE_PATH}`, {
    method: "PUT",
    body: new Uint8Array([1, 2, 3]),
  });
  assertEquals(req.headers.get("content-type"), null);
  await createApp({ config: CONFIG, fetch: fetchImpl }).request(req);
  assertEquals(
    new Headers(putCall(calls).init.headers).get("Content-Type"),
    "application/octet-stream",
  );
});

Deno.test("byte PUT → one unconditional object PUT, no existence check, no marker read", async () => {
  const { calls, fetchImpl } = recorder();
  await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(calls.length, 1);
  assertEquals(calls[0].init.method, "PUT");
  assertEquals(calls[0].url, BYTE_OBJ_URL);
});

Deno.test("byte PUT → bunny error → 502 (never a false 2xx)", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 502);
});

Deno.test("byte PUT → upstream throw/abort → 502", async () => {
  const { fetchImpl } = recorder({ throws: true });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 502);
});

Deno.test("byte PUT → non-UUID device segment → 400, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/devices/nope/a.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("byte PUT → unmatched path → 404, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/nope", {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("byte PUT → empty filename (no resource) → 404 (route doesn't match)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/devices/${D}/`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("byte OPTIONS → 204, no resumable advertised, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "OPTIONS",
  });
  assertEquals(res.status, 204);
  assertEquals(res.headers.get("Allow"), "PUT, OPTIONS");
  assertEquals(calls.length, 0);
  for (const [name] of res.headers) {
    if (name.toLowerCase().startsWith("upload-")) {
      throw new Error(`unexpected resumable header advertised: ${name}`);
    }
  }
});

// ── PUT /events/:eventId/devices/:deviceId (device-manifest write, GATED) ───────────────────────────

Deno.test("device-manifest PUT → marker GET + devices LIST + one object PUT to events/<e>/devices/<d>.json, 201", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: JSON.stringify({ deviceId: D, assets: [] }),
    headers: { "content-type": "application/json" },
  });
  assertEquals(res.status, 201);
  // Exactly the gate's two reads + the object PUT: marker GET (existence + lifecycle) and the single
  // devices/ LIST the limits gate needs (known-vs-new + the capacity count) — no other upstream call.
  assertEquals(calls.length, 3);
  assertEquals(calls[0].url, MARKER_URL);
  assertEquals(calls[1].url, `${ZONE}/events/${E}/devices/`);
  const put = putCall(calls);
  assertEquals(put.url, DEVMANIFEST_URL);
  const h = new Headers(put.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "application/json");
});

Deno.test("device-manifest PUT → missing event (marker absent) → 404, no object PUT", async () => {
  const { calls, fetchImpl } = recorder({ marker: "absent" });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
});

Deno.test("device-manifest PUT → marker read failure → 502 (not absence), no object PUT", async () => {
  const { calls, fetchImpl } = recorder({ marker: "fail" });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 502);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
});

Deno.test("device-manifest PUT → bunny PUT error → 502", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 502);
});

Deno.test("device-manifest PUT → non-UUID event or device → 400, no upstream", async () => {
  for (const path of [`/events/nope/devices/${D}`, `/events/${E}/devices/nope`]) {
    const { calls, fetchImpl } = recorder();
    const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(path, {
      method: "PUT",
      body: "{}",
    });
    assertEquals(res.status, 400);
    assertEquals(calls.length, 0);
  }
});

// ── GET /files/devices/:deviceId (per-device raw listing) ──────────────────────────────────────────

const dir = (name: string) => ({ ObjectName: name, IsDirectory: true, Length: 0 });
const file = (name: string, length: number) => ({
  ObjectName: name,
  IsDirectory: false,
  Length: length,
});

/** Serves canned bunny LIST JSON keyed by URL; any unmapped URL → 404 (→ "no objects"). */
function listFake(routes: Record<string, { status?: number; body?: unknown }>) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    const r = routes[url];
    if (!r) return Promise.resolve(new Response("not found", { status: 404 }));
    const body = r.body === undefined ? null : JSON.stringify(r.body);
    return Promise.resolve(
      new Response(body, {
        status: r.status ?? 200,
        headers: { "content-type": "application/json" },
      }),
    );
  };
  return { calls, fetchImpl };
}

Deno.test("device list → raw files as { filename, size, url }, one LIST, no further reads", async () => {
  const { calls, fetchImpl } = listFake({
    [DEVDIR_URL]: {
      body: [file("A-primary.heic", 100), file("A-motion.mov", 200), dir("nested")],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  // All three directives: the pull zone fronting the script honors `no-cache`, not `no-store`.
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  const body = await res.json();
  assertEquals(
    body.map((e: { filename: string; size: number }) => ({ filename: e.filename, size: e.size })),
    [{ filename: "A-primary.heic", size: 100 }, { filename: "A-motion.mov", size: 200 }],
  ); // directory entry filtered out
  assertPresigned(body[0].url, `files/devices/${D}/A-primary.heic`);
  assertPresigned(body[1].url, `files/devices/${D}/A-motion.mov`);
  assertEquals(calls.length, 1); // single LIST, no manifest/content reads
  assertEquals(new Headers(calls[0].init.headers).get("AccessKey"), "zone-password");
});

Deno.test("device list → empty dir (200 []) → 200 []", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { body: [] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("device list → empty dir (bunny 404) → 200 []", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { status: 404 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("device list → LIST failure (500) → 502 (never partial)", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { status: 500 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 502);
});

Deno.test("device list → non-UUID device id → 400, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/files/devices/nope");
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("device list → wrong method (POST) → 404, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({ [DEVDIR_URL]: { body: [] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH, {
    method: "POST",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("device list → a percent-encoded filename round-trips and re-encodes into the presigned url", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { body: [file("A-primary%201.heic", 7)] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  const entry = (await res.json())[0];
  assertEquals(entry.filename, "A-primary 1.heic"); // decoded back to the uploaded name
  assertPresigned(entry.url, `files/devices/${D}/A-primary%201.heic`); // re-encoded, flat key in the presigned url
});

// ── POST /events + GET /events/:eventId (capability `event-creation`) ───────────────────────────────

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.test("POST /events → 201 {eventId,name,createdAt,startsAt,endsAt,capacity} + one marker PUT to events/<id>/metadata.json", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({ name: "Birthday", startsAt: STARTS_AT }),
    headers: { "content-type": "application/json" },
  });
  assertEquals(res.status, 201);
  const json = await res.json();
  assertEquals(json.name, "Birthday");
  assertEquals(UUID_RE.test(json.eventId), true);
  assertEquals(typeof json.createdAt, "string");
  assertEquals(json.startsAt, STARTS_AT); // honored VERBATIM, not re-derived
  // The limits are STAMPED at mint from config (capability `event-limits`), in the canonical shape.
  assertEquals(json.endsAt, ENDS_AT); // startsAt + the configured 30 days
  assertEquals(json.capacity, 10);
  assertEquals(calls.length, 1);
  const put = calls[0];
  assertEquals(put.init.method, "PUT");
  assertEquals(put.url, `${ZONE}/events/${json.eventId}/metadata.json`);
  const h = new Headers(put.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "application/json");
  assertEquals(JSON.parse(put.init.body as string), json);
});

Deno.test("POST /events → createdAt and startsAt are independent facts", async () => {
  const { fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({ name: "Wedding", startsAt: "2001-01-01T09:00:00Z" }),
  });
  assertEquals(res.status, 201);
  const json = await res.json();
  // The host says the event began in 2001; the server still stamps its own creation time now.
  assertEquals(json.startsAt, "2001-01-01T09:00:00Z");
  assertEquals(json.createdAt === json.startsAt, false);
});

Deno.test("POST /events → a future startsAt is accepted (event created ahead of time)", async () => {
  const { fetchImpl } = recorder();
  const future = "2099-12-31T23:59:59Z";
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({ name: "NYE", startsAt: future }),
  });
  assertEquals(res.status, 201);
  assertEquals((await res.json()).startsAt, future);
});

Deno.test("POST /events → missing / empty / non-canonical startsAt → 400, no upstream", async () => {
  const starts = [
    undefined, // absent
    "",
    "2026-06-27T18:00:00.000Z", // fractional seconds — a bare NSISO8601DateFormatter rejects these
    "2026-06-27T18:00:00+02:00", // offset — breaks the lexicographic compare
    "2026-06-27T18:00:00", // no Z
    "2026-06-27", // date only
    "2026-13-45T99:99:99Z", // right shape, not a real instant
    "yesterday",
    12345,
  ];
  for (const startsAt of starts) {
    const { calls, fetchImpl } = recorder();
    const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
      method: "POST",
      body: JSON.stringify({ name: "X", startsAt }),
    });
    assertEquals(res.status, 400, `startsAt=${JSON.stringify(startsAt)} should be rejected`);
    assertEquals(calls.length, 0);
  }
});

Deno.test("POST /events → name is trimmed before store and echo", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({ name: "  Birthday  ", startsAt: STARTS_AT }),
  });
  assertEquals(res.status, 201);
  assertEquals((await res.json()).name, "Birthday");
  assertEquals(JSON.parse(putCall(calls).init.body as string).name, "Birthday");
});

Deno.test("POST /events → client-supplied id ignored, server mints a fresh UUID", async () => {
  const { fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({
      name: "X",
      startsAt: STARTS_AT,
      eventId: "client-supplied",
      id: "also-ignored",
    }),
  });
  assertEquals(res.status, 201);
  const json = await res.json();
  assertEquals(UUID_RE.test(json.eventId), true);
  assertEquals(json.eventId === "client-supplied", false);
});

Deno.test("POST /events → client-supplied endsAt/capacity ignored, server stamps its own", async () => {
  const { fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({
      name: "X",
      startsAt: STARTS_AT,
      endsAt: "2099-01-01T00:00:00Z", // a client may not extend its own event
      capacity: 9999, // nor widen it
    }),
  });
  assertEquals(res.status, 201);
  const json = await res.json();
  assertEquals(json.endsAt, ENDS_AT);
  assertEquals(json.capacity, 10);
});

Deno.test("POST /events → 100-char name accepted (boundary)", async () => {
  const { fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({ name: "a".repeat(100), startsAt: STARTS_AT }),
  });
  assertEquals(res.status, 201);
});

Deno.test("POST /events → empty / whitespace / over-long / missing / non-JSON → 400, no upstream", async () => {
  // Each body carries a VALID startsAt, so these still test NAME validation and not the new field.
  const bodies = [
    `{"name":"","startsAt":"${STARTS_AT}"}`,
    `{"name":"   ","startsAt":"${STARTS_AT}"}`,
    `{"name":"${"a".repeat(101)}","startsAt":"${STARTS_AT}"}`,
    "{}",
    "not json",
  ];
  for (const body of bodies) {
    const { calls, fetchImpl } = recorder();
    const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
      method: "POST",
      body,
    });
    assertEquals(res.status, 400);
    assertEquals(calls.length, 0);
  }
});

Deno.test("POST /events → marker PUT fails (500) → 502 (faithful create)", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({ name: "X", startsAt: STARTS_AT }),
  });
  assertEquals(res.status, 502);
});

Deno.test("POST /events → marker PUT throws → 502", async () => {
  const { fetchImpl } = recorder({ throws: true });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "POST",
    body: JSON.stringify({ name: "X", startsAt: STARTS_AT }),
  });
  assertEquals(res.status, 502);
});

Deno.test("non-POST on /events → 404 (no route), no upstream", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events", {
    method: "GET",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("GET /events/:id → 200 marker (events/<id>/metadata.json) when present", async () => {
  const { calls, fetchImpl } = listFake({ ...markerPresent });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}`);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), MARKER_BODY);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, MARKER_URL);
});

Deno.test("GET /events/:id → a legacy marker (no limit fields) is EXPIRED: reaped, 404, never patched", async () => {
  // A marker written before `event-limits`. The former read-time `startsAt` synthesis is gone: a marker
  // old enough to lack `startsAt` also lacks `endsAt`, so it is expired by definition and reaped on
  // this touch (capability `event-limits`) — answered exactly like a never-created event.
  const legacy = { eventId: E, name: "Party", createdAt: "2026-06-27T00:00:00.182Z" };
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: legacy },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}`);
  assertEquals(res.status, 404);
  assert(!store.has(`events/${E}/metadata.json`)); // reaped, not patched
});

Deno.test("GET /events/:id → 404 when marker absent", async () => {
  const { calls, fetchImpl } = listFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}`);
  assertEquals(res.status, 404);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, MARKER_URL);
});

Deno.test("GET /events/:id → 400 on non-UUID, no upstream", async () => {
  const { calls, fetchImpl } = listFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events/nope");
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("GET /events/:id → 502 on non-404 marker read failure", async () => {
  const { fetchImpl } = listFake({ [MARKER_URL]: { status: 500 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}`);
  assertEquals(res.status, 502);
});

// ── GET /events/:eventId/files (event-wide UNION read, capability `bunny-list-endpoint`) ────────────

const D2 = "22222222-0000-4000-8000-000000000003"; // a second contributing deviceId
const MANIFEST_DIR_URL = `${ZONE}/events/${E}/devices/`; // the device-manifest directory LIST
const manifestUrl = (d: string) => `${ZONE}/events/${E}/devices/${d}.json`;
const fileDirUrl = (d: string) => `${ZONE}/files/devices/${d}/`;

// A device manifest object (post-rename: resources carry `key` + `filename`).
const manifest = (deviceId: string, assets: unknown[]) => ({ body: { deviceId, assets } });
const resource = (role: string, contentType: string, key: string, filename: string) => ({
  role,
  contentType,
  key,
  filename,
});
const asset = (assetId: string, creationDate: string, resources: unknown[]) => ({
  assetId,
  creationDate,
  resources,
});

Deno.test("union → two devices' complete assets, flattened, tagged by deviceId, with no-store", async () => {
  const { calls, fetchImpl } = listFake({
    ...markerPresent,
    [MANIFEST_DIR_URL]: { body: [file(`${D}.json`, 0), file(`${D2}.json`, 0)] },
    [manifestUrl(D)]: manifest(D, [
      asset("A", "2026-06-27T10:00:00Z", [
        resource("primary", "image/heic", "A-primary.heic", "IMG_1.HEIC"),
        resource("motion", "video/quicktime", "A-motion.mov", "IMG_1.MOV"),
      ]),
    ]),
    [manifestUrl(D2)]: manifest(D2, [
      asset("B", "2026-06-27T11:00:00Z", [
        resource("primary", "image/jpeg", "B-primary.jpg", "IMG_2.JPG"),
      ]),
    ]),
    [fileDirUrl(D)]: { body: [file("A-primary.heic", 100), file("A-motion.mov", 200)] },
    [fileDirUrl(D2)]: { body: [file("B-primary.jpg", 50)] },
  });
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 200);
  assertEquals(r.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  const union = await r.json();
  // Structure + non-url fields (each `url` is a dynamic presigned URL, asserted separately below).
  const stripUrls = (a: { resources: { url: string }[] }) => ({
    ...a,
    resources: a.resources.map(({ url: _u, ...rest }) => rest),
  });
  assertEquals(union.map(stripUrls), [
    {
      deviceId: D,
      assetId: "A",
      creationDate: "2026-06-27T10:00:00Z",
      resources: [
        {
          role: "primary",
          contentType: "image/heic",
          key: "A-primary.heic",
          filename: "IMG_1.HEIC",
          size: 100,
        },
        {
          role: "motion",
          contentType: "video/quicktime",
          key: "A-motion.mov",
          filename: "IMG_1.MOV",
          size: 200,
        },
      ],
    },
    {
      deviceId: D2,
      assetId: "B",
      creationDate: "2026-06-27T11:00:00Z",
      resources: [
        {
          role: "primary",
          contentType: "image/jpeg",
          key: "B-primary.jpg",
          filename: "IMG_2.JPG",
          size: 50,
        },
      ],
    },
  ]);
  // Each resource's `url` is a presigned S3 GET for its owning device's bare key.
  assertPresigned(union[0].resources[0].url, `files/devices/${D}/A-primary.heic`);
  assertPresigned(union[0].resources[1].url, `files/devices/${D}/A-motion.mov`);
  assertPresigned(union[1].resources[0].url, `files/devices/${D2}/B-primary.jpg`);
  // Every upstream read carries the AccessKey; the account API key never appears.
  for (const c of calls) {
    assertEquals(new Headers(c.init.headers).get("AccessKey"), "zone-password");
  }
});

Deno.test("union → incomplete asset (a named resource missing from /files) is omitted", async () => {
  const { fetchImpl } = listFake({
    ...markerPresent,
    [MANIFEST_DIR_URL]: { body: [file(`${D}.json`, 0)] },
    [manifestUrl(D)]: manifest(D, [
      // complete (both present)
      asset("A", "t1", [
        resource("primary", "image/heic", "A-primary.heic", "IMG_1.HEIC"),
        resource("motion", "video/quicktime", "A-motion.mov", "IMG_1.MOV"),
      ]),
      // incomplete (motion bytes not yet uploaded)
      asset("C", "t2", [
        resource("primary", "image/heic", "C-primary.heic", "IMG_3.HEIC"),
        resource("motion", "video/quicktime", "C-motion.mov", "IMG_3.MOV"),
      ]),
    ]),
    [fileDirUrl(D)]: {
      body: [file("A-primary.heic", 100), file("A-motion.mov", 200), file("C-primary.heic", 300)],
    },
  });
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 200);
  const union = await r.json();
  assertEquals(union.map((a: { assetId: string }) => a.assetId), ["A"]); // C omitted
});

Deno.test("union → a device with no bytes (file dir 404) contributes nothing, still 200", async () => {
  const { fetchImpl } = listFake({
    ...markerPresent,
    [MANIFEST_DIR_URL]: { body: [file(`${D}.json`, 0)] },
    [manifestUrl(D)]: manifest(D, [
      asset("A", "t1", [resource("primary", "image/heic", "A-primary.heic", "IMG_1.HEIC")]),
    ]),
    // no fileDirUrl(D) mapping → listFake returns 404 → no bytes present
  });
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 200);
  assertEquals(await r.json(), []);
});

Deno.test("union → unknown event (marker absent) → 404, no device enumeration", async () => {
  const { calls, fetchImpl } = listFake({}); // marker URL unmapped → 404
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 404);
  assertEquals(calls.length, 1); // only the marker read
  assertEquals(calls[0].url, MARKER_URL);
});

Deno.test("union → non-404 marker read failure → 502", async () => {
  const { fetchImpl } = listFake({ [MARKER_URL]: { status: 500 } });
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 502);
});

Deno.test("union → existing event, empty manifest dir → 200 []", async () => {
  for (const dirRoute of [{ body: [] }, { status: 404 }]) {
    const { fetchImpl } = listFake({ ...markerPresent, [MANIFEST_DIR_URL]: dirRoute });
    const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
    assertEquals(r.status, 200);
    assertEquals(await r.json(), []);
  }
});

Deno.test("union → non-UUID event → 400, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({});
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events/nope/files");
  assertEquals(r.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("union → wrong method (POST) → 404, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({ ...markerPresent });
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`, {
    method: "POST",
  });
  assertEquals(r.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("union → a per-device manifest read failure (500) → 502, no partial union", async () => {
  const { fetchImpl } = listFake({
    ...markerPresent,
    [MANIFEST_DIR_URL]: { body: [file(`${D}.json`, 0)] },
    [manifestUrl(D)]: { status: 500 },
    [fileDirUrl(D)]: { body: [] },
  });
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 502);
});

Deno.test("union → a manifest that is unparseable JSON → 502", async () => {
  // listFake JSON-stringifies bodies; inject a raw non-JSON body via a bespoke fake.
  const fetchImpl: FetchLike = (url) => {
    if (url === MARKER_URL) {
      return Promise.resolve(
        new Response(JSON.stringify(MARKER_BODY), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
    }
    if (url === MANIFEST_DIR_URL) {
      return Promise.resolve(
        new Response(JSON.stringify([file(`${D}.json`, 0)]), { status: 200 }),
      );
    }
    if (url === manifestUrl(D)) return Promise.resolve(new Response("not json{", { status: 200 }));
    return Promise.resolve(new Response("not found", { status: 404 }));
  };
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 502);
});

Deno.test("union → a per-device file LIST failure (500) → 502, no partial union", async () => {
  const { fetchImpl } = listFake({
    ...markerPresent,
    [MANIFEST_DIR_URL]: { body: [file(`${D}.json`, 0)] },
    [manifestUrl(D)]: manifest(D, [
      asset("A", "t1", [resource("primary", "image/heic", "A-primary.heic", "IMG_1.HEIC")]),
    ]),
    [fileDirUrl(D)]: { status: 500 },
  });
  const r = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(r.status, 502);
});

// ── PUT /devices/:deviceId (device config write, DEVICE-ID gated) ───────────────────────────

const CONFIG_PATH = `/devices/${D}`;
const CONFIG_OBJ_URL = `${ZONE}/devices/${D}.json`;
const CONFIG_BODY = JSON.stringify({ pushToken: { kind: "apns", token: "TOK", env: "sandbox" } });

Deno.test("device config PUT → one unconditional PUT to devices/<id>.json (json), 201", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(CONFIG_PATH, {
    method: "PUT",
    body: CONFIG_BODY,
    headers: { "content-type": "application/json" },
  });
  assertEquals(res.status, 201);
  assertEquals(calls.length, 1); // UNGATED by event: no marker read, exactly one object PUT
  const put = putCall(calls);
  assertEquals(put.url, CONFIG_OBJ_URL);
  const h = new Headers(put.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "application/json");
  assertEquals(await new Response(put.init.body as BodyInit).text(), CONFIG_BODY);
});

Deno.test("device config PUT → non-UUID device → 400, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    "/devices/nope",
    {
      method: "PUT",
      body: "{}",
    },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("device config PUT → bunny error → 502 (never a false 2xx)", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(CONFIG_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 502);
});

Deno.test("device config PUT → upstream throw → 502", async () => {
  const { fetchImpl } = recorder({ throws: true });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(CONFIG_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 502);
});

Deno.test("device config → wrong method (GET) → 404 (no route), no upstream", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(CONFIG_PATH, {
    method: "GET",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

// ── POST /events/:eventId/notify (silent fan-out to members, marker-gated) ──────────────────────────

// A real P-256 key so the APNs sender actually signs and posts (the fan-out tests observe the POSTs).
async function configWithApnsKey() {
  const kp = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
    "sign",
    "verify",
  ]);
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", kp.privateKey));
  let bin = "";
  for (const b of pkcs8) bin += String.fromCharCode(b);
  const pem = `-----BEGIN PRIVATE KEY-----\n${
    btoa(bin).match(/.{1,64}/g)!.join("\n")
  }\n-----END PRIVATE KEY-----\n`;
  return { ...CONFIG, apnsPrivateKey: pem };
}

type TokenSpec = { kind: string; token: string; env: string } | "absent" | "notoken";

function notifyFake(opts: {
  marker?: "present" | "absent" | "fail";
  members?: string[];
  memberDirFails?: boolean;
  tokens?: Record<string, TokenSpec>;
  apnsStatus?: number;
}) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    if (url.includes("push.apple.com/3/device/")) {
      return Promise.resolve(new Response(null, { status: opts.apnsStatus ?? 200 }));
    }
    if (url === MARKER_URL) {
      const m = opts.marker ?? "present";
      if (m === "absent") return Promise.resolve(new Response(null, { status: 404 }));
      if (m === "fail") return Promise.resolve(new Response("boom", { status: 500 }));
      return Promise.resolve(new Response(JSON.stringify(MARKER_BODY), { status: 200 }));
    }
    if (url === MANIFEST_DIR_URL) {
      if (opts.memberDirFails) return Promise.resolve(new Response("boom", { status: 500 }));
      const entries = (opts.members ?? []).map((d) => file(`${d}.json`, 0));
      return Promise.resolve(new Response(JSON.stringify(entries), { status: 200 }));
    }
    const cfg = url.match(/\/devices\/([^/]+)\.json$/);
    if (cfg) {
      const t = opts.tokens?.[cfg[1]];
      if (!t || t === "absent") return Promise.resolve(new Response("nf", { status: 404 }));
      if (t === "notoken") {
        return Promise.resolve(new Response(JSON.stringify({ other: 1 }), { status: 200 }));
      }
      return Promise.resolve(new Response(JSON.stringify({ pushToken: t }), { status: 200 }));
    }
    return Promise.resolve(new Response("nf", { status: 404 }));
  };
  return { calls, fetchImpl };
}

const apnsCalls = (calls: Call[]) =>
  calls.filter((c) => c.url.includes("push.apple.com")).map((c) => c.url);

Deno.test("notify → all members with a token receive a silent push; 202", async () => {
  const config = await configWithApnsKey();
  const { calls, fetchImpl } = notifyFake({
    members: [D, D2],
    tokens: {
      [D]: { kind: "apns", token: "TOKA", env: "production" },
      [D2]: { kind: "apns", token: "TOKB", env: "sandbox" },
    },
  });
  const res = await createApp({ config, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 202);
  assertEquals(await res.text(), ""); // bare body
  assertEquals(
    apnsCalls(calls).sort(),
    [
      "https://api.push.apple.com/3/device/TOKA",
      "https://api.sandbox.push.apple.com/3/device/TOKB",
    ].sort(),
  );
  // Every dispatched push carries the route's eventId alongside the silent aps object.
  for (const c of calls.filter((c) => c.url.includes("push.apple.com"))) {
    const body = JSON.parse(c.init.body as string);
    assertEquals(body.eventId, E);
    assertEquals(body.aps, { "content-available": 1 });
  }
});

Deno.test("notify → a member without a registered token is skipped; others still pushed; 202", async () => {
  const config = await configWithApnsKey();
  const { calls, fetchImpl } = notifyFake({
    members: [D, D2],
    tokens: { [D]: { kind: "apns", token: "TOKA", env: "production" }, [D2]: "absent" },
  });
  const res = await createApp({ config, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 202);
  assertEquals(apnsCalls(calls), ["https://api.push.apple.com/3/device/TOKA"]);
});

Deno.test("notify → a config with no pushToken is skipped; 202", async () => {
  const config = await configWithApnsKey();
  const { calls, fetchImpl } = notifyFake({ members: [D], tokens: { [D]: "notoken" } });
  const res = await createApp({ config, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 202);
  assertEquals(apnsCalls(calls).length, 0);
});

Deno.test("notify → an individual APNs rejection (410) still yields 202", async () => {
  const config = await configWithApnsKey();
  const { fetchImpl } = notifyFake({
    members: [D],
    tokens: { [D]: { kind: "apns", token: "TOKA", env: "production" } },
    apnsStatus: 410,
  });
  const res = await createApp({ config, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 202);
});

Deno.test("notify → empty member directory notifies vacuously; 202, no push", async () => {
  const { calls, fetchImpl } = notifyFake({ members: [] });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 202);
  assertEquals(apnsCalls(calls).length, 0);
});

Deno.test("notify → unknown event (marker absent) → 404, no enumeration or push", async () => {
  const { calls, fetchImpl } = notifyFake({ marker: "absent", members: [D] });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 1); // only the marker read
  assertEquals(apnsCalls(calls).length, 0);
});

Deno.test("notify → non-404 marker read failure → 502", async () => {
  const { fetchImpl } = notifyFake({ marker: "fail" });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 502);
});

Deno.test("notify → member-directory LIST failure → 502, no push", async () => {
  const { calls, fetchImpl } = notifyFake({ memberDirFails: true });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 502);
  assertEquals(apnsCalls(calls).length, 0);
});

Deno.test("notify → non-UUID event → 400, no upstream request", async () => {
  const { calls, fetchImpl } = notifyFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/events/nope/notify", {
    method: "POST",
  });
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("notify → wrong method (GET) → 404, no upstream request", async () => {
  const { calls, fetchImpl } = notifyFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/notify`);
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

// ── DELETE /events/:eventId/devices/:deviceId (leave cascade) + LWW membership ──────────────────────

const E2 = "7a3f9c21-0000-4000-8000-000000000010"; // a second eventId
const G = "33333333-0000-4000-8000-000000000004"; // a third deviceId (keeps E2 alive)

let leaveClock = Date.parse("2026-06-27T12:00:00.000Z");
/** A monotonically-increasing wall-clock string, so a PUT always mints a newer LastChanged (LWW). */
function tick(): string {
  leaveClock += 1000;
  return new Date(leaveClock).toISOString();
}

/** Strip `${ZONE}/` from a full upstream URL to recover the storage key. */
const keyOf = (url: string) => (url.startsWith(ZONE + "/") ? url.slice(ZONE.length + 1) : url);

/** A minimal device manifest with one complete single-resource asset keyed on `key`. */
const mkManifest = (deviceId: string, assetId: string, key: string) => ({
  deviceId,
  assets: [{
    assetId,
    creationDate: "2026-06-27T10:00:00Z",
    resources: [{ role: "primary", contentType: "image/heic", key, filename: key }],
  }],
});

/**
 * An in-memory bunny native-Storage fake: GET an object (or, for a trailing-slash key, a directory LIST
 * of direct children with `LastChanged`), PUT (stores + mints a fresh LastChanged), DELETE (idempotent).
 * Keys in `failDelete` return 500 (to model a partial-rename failure). APNs POSTs succeed. Seeded from
 * `{ key: { json, lc } }`.
 */
function storageFake(
  initial: Record<string, { json?: unknown; lc?: string }>,
  failDelete?: Set<string>,
) {
  const store = new Map<string, { body: string; lc: string }>();
  for (const [k, v] of Object.entries(initial)) {
    store.set(k, {
      body: v.json === undefined ? "" : JSON.stringify(v.json),
      lc: v.lc ?? "2026-06-27T00:00:00.000Z",
    });
  }
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    if (url.includes("push.apple.com")) return Promise.resolve(new Response(null, { status: 200 }));
    const method = init.method ?? "GET";
    const key = keyOf(url);
    if (method === "GET" && key.endsWith("/")) {
      const children = new Map<string, { name: string; dir: boolean; len: number; lc: string }>();
      let any = false;
      for (const [k, v] of store) {
        if (!k.startsWith(key)) continue;
        any = true;
        const rest = k.slice(key.length);
        const slash = rest.indexOf("/");
        if (slash === -1) {
          children.set(rest, { name: rest, dir: false, len: v.body.length, lc: v.lc });
        } else if (!children.has(rest.slice(0, slash))) {
          children.set(rest.slice(0, slash), {
            name: rest.slice(0, slash),
            dir: true,
            len: 0,
            lc: "",
          });
        }
      }
      if (!any) return Promise.resolve(new Response("nf", { status: 404 }));
      const entries = [...children.values()].map((e) => ({
        ObjectName: e.name,
        IsDirectory: e.dir,
        Length: e.len,
        LastChanged: e.lc,
      }));
      return Promise.resolve(new Response(JSON.stringify(entries), { status: 200 }));
    }
    if (method === "GET") {
      const v = store.get(key);
      return Promise.resolve(
        v ? new Response(v.body, { status: 200 }) : new Response("nf", { status: 404 }),
      );
    }
    if (method === "PUT") {
      // Drain string OR stream bodies (the manifest route streams `c.req.raw.body`), like a real store.
      const body = typeof init.body === "string"
        ? Promise.resolve(init.body)
        : init.body
        ? new Response(init.body as BodyInit).text()
        : Promise.resolve("");
      return body.then((b) => {
        store.set(key, { body: b, lc: tick() });
        return new Response(null, { status: 201 });
      });
    }
    if (method === "DELETE") {
      if (failDelete?.has(key)) return Promise.resolve(new Response("boom", { status: 500 }));
      return Promise.resolve(new Response(null, { status: store.delete(key) ? 200 : 404 }));
    }
    return Promise.resolve(new Response(null, { status: 405 }));
  };
  return { store, calls, fetchImpl };
}

const del = (app: ReturnType<typeof createApp>, e: string, d: string) =>
  app.request(`/events/${e}/devices/${d}`, { method: "DELETE" });

Deno.test("leave → non-UUID id → 400, no upstream", async () => {
  const { calls, fetchImpl } = storageFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl })
    .request(`/events/not-a-uuid/devices/${D}`, { method: "DELETE" });
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("leave → absent event marker → 404, no manifest writes", async () => {
  const { calls, fetchImpl } = storageFake({}); // no marker seeded
  const res = await del(createApp({ config: CONFIG, fetch: fetchImpl }), E, D);
  assertEquals(res.status, 404);
  assert(!calls.some((c) => c.init.method === "PUT" || c.init.method === "DELETE"));
});

Deno.test("leave with another active member → renames to .left.json, keeps the event", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
  });
  const res = await del(createApp({ config: CONFIG, fetch: fetchImpl }), E, D);
  assertEquals(res.status, 200);
  assert(!store.has(`events/${E}/devices/${D}.json`)); // active removed
  assert(store.has(`events/${E}/devices/${D}.left.json`)); // departed written
  assert(store.has(`events/${E}/devices/${D2}.json`)); // other member intact
  assert(store.has(`events/${E}/metadata.json`)); // event kept
});

Deno.test("last active member leaves → event reaped + orphaned device GC'd (bytes + config)", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
    [`devices/${D}.json`]: { json: { pushToken: {} } },
  });
  const res = await del(createApp({ config: CONFIG, fetch: fetchImpl }), E, D);
  assertEquals(res.status, 200);
  assert(!store.has(`events/${E}/metadata.json`)); // event reaped
  assert(!store.has(`events/${E}/devices/${D}.left.json`)); // manifest gone
  assert(!store.has(`files/devices/${D}/A-primary.heic`)); // bytes GC'd
  assert(!store.has(`devices/${D}.json`)); // config GC'd
});

Deno.test("last active leaves but device is in another event → bytes/config retained", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E2}/metadata.json`]: { json: { ...MARKER_BODY, eventId: E2 } },
    [`events/${E2}/devices/${G}.json`]: { json: mkManifest(G, "C", "C-primary.heic") },
    [`events/${E2}/devices/${D}.left.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
    [`devices/${D}.json`]: { json: { pushToken: {} } },
  });
  const res = await del(createApp({ config: CONFIG, fetch: fetchImpl }), E, D);
  assertEquals(res.status, 200);
  assert(!store.has(`events/${E}/metadata.json`)); // E reaped
  assert(store.has(`files/devices/${D}/A-primary.heic`)); // bytes retained (E2 refs them)
  assert(store.has(`devices/${D}.json`)); // config retained
  assert(store.has(`events/${E2}/devices/${D}.left.json`)); // still departed in E2
});

Deno.test("leave is idempotent — a duplicate DELETE re-runs harmlessly", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
  });
  const app = createApp({ config: CONFIG, fetch: fetchImpl });
  assertEquals((await del(app, E, D)).status, 200);
  assertEquals((await del(app, E, D)).status, 200); // duplicate
  assert(store.has(`events/${E}/devices/${D}.left.json`));
  assert(!store.has(`events/${E}/devices/${D}.json`));
  assert(store.has(`events/${E}/devices/${D2}.json`)); // untouched
});

Deno.test("partial rename (active delete fails) → 502, contribution preserved, retry completes", async () => {
  const failing = new Set<string>([`events/${E}/devices/${D}.json`]);
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
  }, failing);
  const app = createApp({ config: CONFIG, fetch: fetchImpl });
  assertEquals((await del(app, E, D)).status, 502);
  assert(store.has(`events/${E}/devices/${D}.left.json`)); // written FIRST — contribution preserved
  assert(store.has(`events/${E}/devices/${D}.json`)); // leftover active, inert (LWW: .left is newer)
  failing.clear();
  assertEquals((await del(app, E, D)).status, 200); // retry completes
  assert(!store.has(`events/${E}/devices/${D}.json`)); // now cleaned up
});

Deno.test("union → a departed device's photos remain in the union", async () => {
  const { fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.left.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
    [`files/devices/${D2}/B-primary.heic`]: { json: {} },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  assertEquals(res.status, 200);
  const body = await res.json() as { deviceId: string }[];
  assertEquals(new Set(body.map((a) => a.deviceId)), new Set([D, D2]));
});

Deno.test("union → device with both siblings counted once (LWW departed wins)", async () => {
  const { fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.json`]: {
      json: mkManifest(D, "OLD", "A-primary.heic"),
      lc: "2026-06-27T00:00:00.000Z",
    },
    [`events/${E}/devices/${D}.left.json`]: {
      json: mkManifest(D, "NEW", "A-primary.heic"),
      lc: "2026-06-27T06:00:00.000Z",
    },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/files`);
  const body = await res.json() as { deviceId: string; assetId: string }[];
  assertEquals(body.length, 1); // counted once
  assertEquals(body[0].assetId, "NEW"); // read the newer (departed) manifest, not the stale active
});

Deno.test("notify → excludes a departed device, targets active members", async () => {
  const { calls, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.left.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
    [`devices/${D}.json`]: { json: { pushToken: { kind: "apns", token: "tokD", env: "sandbox" } } },
    [`devices/${D2}.json`]: {
      json: { pushToken: { kind: "apns", token: "tokD2", env: "sandbox" } },
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}/notify`, {
    method: "POST",
  });
  assertEquals(res.status, 202);
  assert(calls.some((c) => keyOf(c.url) === `devices/${D2}.json`)); // active member's token read
  assert(!calls.some((c) => keyOf(c.url) === `devices/${D}.json`)); // departed member's config NOT read
});

Deno.test("rejoin → fresh active manifest supersedes .left.json (union + notify treat as active)", async () => {
  const { calls, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: MARKER_BODY },
    [`events/${E}/devices/${D}.left.json`]: {
      json: mkManifest(D, "OLD", "A-primary.heic"),
      lc: "2026-06-27T00:00:00.000Z",
    },
    [`events/${E}/devices/${D}.json`]: {
      json: mkManifest(D, "NEW", "A-primary.heic"),
      lc: "2026-06-27T06:00:00.000Z",
    },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
    [`devices/${D}.json`]: { json: { pushToken: { kind: "apns", token: "tokD", env: "sandbox" } } },
  });
  const app = createApp({ config: CONFIG, fetch: fetchImpl });
  const union = await (await app.request(`/events/${E}/files`)).json() as { assetId: string }[];
  assertEquals(union.length, 1);
  assertEquals(union[0].assetId, "NEW"); // active manifest wins the LWW
  assertEquals((await app.request(`/events/${E}/notify`, { method: "POST" })).status, 202);
  assert(calls.some((c) => c.init.method === "GET" && keyOf(c.url) === `devices/${D}.json`)); // D notified (active)
});

// ── event limits: lifecycle, capacity, grace, expiry reap (capability `event-limits`) ───────────────

const GRACE_S = CONFIG.eventGraceSeconds;

Deno.test("classifyEvent → live/grace/expired boundaries are exact", () => {
  const endsMs = Date.parse(ENDS_AT);
  // now == endsAt is still LIVE (the window is inclusive)…
  assertEquals(classifyEvent(MARKER_BODY, endsMs, GRACE_S).phase, "live");
  // …one ms past endsAt is GRACE…
  assertEquals(classifyEvent(MARKER_BODY, endsMs + 1, GRACE_S).phase, "grace");
  // …now == endsAt + grace is still GRACE (inclusive)…
  assertEquals(classifyEvent(MARKER_BODY, endsMs + GRACE_S * 1000, GRACE_S).phase, "grace");
  // …and one ms past that is EXPIRED.
  assertEquals(classifyEvent(MARKER_BODY, endsMs + GRACE_S * 1000 + 1, GRACE_S).phase, "expired");
});

Deno.test("classifyEvent → a marker missing any limit field is expired (legacy = no grandfathering)", () => {
  const nowMs = Date.parse(STARTS_AT); // well inside what WOULD be the window
  const { endsAt: _e, ...noEnds } = MARKER_BODY;
  const { capacity: _c, ...noCap } = MARKER_BODY;
  const { startsAt: _s, ...noStarts } = MARKER_BODY;
  assertEquals(classifyEvent(noEnds, nowMs, GRACE_S).phase, "expired");
  assertEquals(classifyEvent(noCap, nowMs, GRACE_S).phase, "expired");
  assertEquals(classifyEvent(noStarts, nowMs, GRACE_S).phase, "expired");
  // An unparseable endsAt (not producible by our own mint) fails closed the same way.
  assertEquals(classifyEvent({ ...MARKER_BODY, endsAt: "nonsense" }, nowMs, GRACE_S).phase, "expired");
});

Deno.test("classifyEvent → live/grace narrow to a complete marker (all limit fields present)", () => {
  const cls = classifyEvent(MARKER_BODY, Date.parse(STARTS_AT), GRACE_S);
  assert(cls.phase === "live");
  assertEquals(cls.marker, MARKER_BODY);
});

// A capacity-2 marker: capacity checks want a cap small enough to fill with two devices.
const CAP2 = { ...MARKER_BODY, capacity: 2 };
const manifestPut = (app: ReturnType<typeof createApp>, e: string, d: string) =>
  app.request(`/events/${e}/devices/${d}`, {
    method: "PUT",
    body: JSON.stringify({ deviceId: d, assets: [] }),
    headers: { "content-type": "application/json" },
  });

Deno.test("enroll → a NEW device at capacity → 409, nothing written", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: CAP2 },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
  });
  const res = await manifestPut(createApp({ config: CONFIG, fetch: fetchImpl }), E, G);
  assertEquals(res.status, 409);
  assert(!store.has(`events/${E}/devices/${G}.json`));
});

Deno.test("enroll → leaving frees NO slot: a departed manifest still counts toward capacity", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: CAP2 },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.left.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
  });
  const res = await manifestPut(createApp({ config: CONFIG, fetch: fetchImpl }), E, G);
  assertEquals(res.status, 409);
  assert(!store.has(`events/${E}/devices/${G}.json`));
});

Deno.test("enroll → a KNOWN device passes the capacity check at capacity (manifest update)", async () => {
  const { fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: CAP2 },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
  });
  assertEquals((await manifestPut(createApp({ config: CONFIG, fetch: fetchImpl }), E, D)).status, 201);
});

Deno.test("enroll → a rejoin reuses the departed device's own slot at capacity", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: CAP2 },
    [`events/${E}/devices/${D}.left.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
  });
  const res = await manifestPut(createApp({ config: CONFIG, fetch: fetchImpl }), E, D);
  assertEquals(res.status, 201);
  assert(store.has(`events/${E}/devices/${D}.json`)); // active again
});

Deno.test("grace → a NEW device cannot enroll (410, nothing written)", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: GRACE_MARKER },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
  });
  const res = await manifestPut(createApp({ config: CONFIG, fetch: fetchImpl }), E, G);
  assertEquals(res.status, 410);
  assert(!store.has(`events/${E}/devices/${G}.json`));
});

Deno.test("grace → 410 wins over 409 for a new device on a full, over event (time is THE reason)", async () => {
  const { fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: { ...GRACE_MARKER, capacity: 1 } },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
  });
  const res = await manifestPut(createApp({ config: CONFIG, fetch: fetchImpl }), E, G);
  assertEquals(res.status, 410); // not 409
});

Deno.test("grace → existing members keep FULL sync: manifest PUT, union, notify, metadata, leave", async () => {
  const { fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: GRACE_MARKER },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
  });
  const app = createApp({ config: CONFIG, fetch: fetchImpl });
  assertEquals((await app.request(`/events/${E}`)).status, 200); // metadata still served
  const union = await app.request(`/events/${E}/files`);
  assertEquals(union.status, 200); // union still served
  assertEquals(((await union.json()) as unknown[]).length, 1);
  assertEquals((await app.request(`/events/${E}/notify`, { method: "POST" })).status, 202);
  assertEquals((await manifestPut(app, E, D)).status, 201); // known device still writes
  assertEquals((await del(app, E, D)).status, 200); // leaving an over event still works
});

Deno.test("expiry → first touch reaps: silent push to ACTIVE members, then everything deleted, 404", async () => {
  const config = await configWithApnsKey();
  const { store, calls, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: EXPIRED_MARKER },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E}/devices/${D2}.left.json`]: { json: mkManifest(D2, "B", "B-primary.heic") },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
    [`devices/${D}.json`]: { json: { pushToken: { kind: "apns", token: "TOKA", env: "production" } } },
    [`devices/${D2}.json`]: { json: { pushToken: { kind: "apns", token: "TOKB", env: "sandbox" } } },
  });
  const res = await createApp({ config, fetch: fetchImpl }).request(`/events/${E}`);
  assertEquals(res.status, 404); // answered as absent
  // The push went to the ACTIVE member only, BEFORE its membership/config were deleted.
  assertEquals(apnsCalls(calls), ["https://api.push.apple.com/3/device/TOKA"]);
  // Everything is gone — manifests, bytes, configs, and the marker (no tombstone).
  assertEquals([...store.keys()], []);
});

Deno.test("expiry → reap retains bytes/config a surviving event still references", async () => {
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: EXPIRED_MARKER },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E2}/metadata.json`]: { json: { ...MARKER_BODY, eventId: E2 } }, // live, references D
    [`events/${E2}/devices/${D}.left.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`events/${E2}/devices/${G}.json`]: { json: mkManifest(G, "C", "C-primary.heic") },
    [`files/devices/${D}/A-primary.heic`]: { json: {} },
    [`devices/${D}.json`]: { json: { pushToken: {} } },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}`);
  assertEquals(res.status, 404);
  assert(!store.has(`events/${E}/metadata.json`)); // E reaped
  assert(store.has(`files/devices/${D}/A-primary.heic`)); // bytes retained (E2 refs them)
  assert(store.has(`devices/${D}.json`)); // config retained
  assert(store.has(`events/${E2}/devices/${D}.left.json`)); // E2 untouched
});

Deno.test("expiry → a push fan-out failure does not block the reap", async () => {
  // The default CONFIG carries a garbage APNs PEM, so the send path fails — the reap must not care.
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: EXPIRED_MARKER },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    [`devices/${D}.json`]: { json: { pushToken: { kind: "apns", token: "TOKA", env: "production" } } },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/events/${E}`);
  assertEquals(res.status, 404);
  assert(!store.has(`events/${E}/metadata.json`)); // reap completed anyway
});

Deno.test("expiry → every event-scoped route triggers the reap and answers as absent", async () => {
  for (const touch of [
    (app: ReturnType<typeof createApp>) => manifestPut(app, E, G),
    (app: ReturnType<typeof createApp>) => app.request(`/events/${E}/files`),
    (app: ReturnType<typeof createApp>) => app.request(`/events/${E}/notify`, { method: "POST" }),
    (app: ReturnType<typeof createApp>) => del(app, E, D),
  ]) {
    const { store, fetchImpl } = storageFake({
      [`events/${E}/metadata.json`]: { json: EXPIRED_MARKER },
      [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
    });
    const res = await touch(createApp({ config: CONFIG, fetch: fetchImpl }));
    assertEquals(res.status, 404);
    assert(!store.has(`events/${E}/metadata.json`)); // reaped on this touch
    assert(!store.has(`events/${E}/devices/${D}.json`));
  }
});

Deno.test("expiry → after the reap, responses are byte-for-byte the never-created ones", async () => {
  const reaped = storageFake({
    [`events/${E}/metadata.json`]: { json: EXPIRED_MARKER },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
  });
  const never = storageFake({});
  const reapedApp = createApp({ config: CONFIG, fetch: reaped.fetchImpl });
  const neverApp = createApp({ config: CONFIG, fetch: never.fetchImpl });
  await reapedApp.request(`/events/${E}`); // the reaping touch
  for (
    const req of [
      (app: ReturnType<typeof createApp>) => app.request(`/events/${E}`),
      (app: ReturnType<typeof createApp>) => manifestPut(app, E, D),
      (app: ReturnType<typeof createApp>) => app.request(`/events/${E}/files`),
      (app: ReturnType<typeof createApp>) => app.request(`/events/${E}/notify`, { method: "POST" }),
    ]
  ) {
    const [a, b] = [await req(reapedApp), await req(neverApp)];
    assertEquals(a.status, b.status);
    assertEquals(await a.text(), await b.text());
  }
});

Deno.test("expiry → an interrupted reap keeps the marker and completes on the next touch", async () => {
  const failing = new Set<string>([`events/${E}/devices/${D}.json`]);
  const { store, fetchImpl } = storageFake({
    [`events/${E}/metadata.json`]: { json: EXPIRED_MARKER },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "A", "A-primary.heic") },
  }, failing);
  const app = createApp({ config: CONFIG, fetch: fetchImpl });
  assertEquals((await app.request(`/events/${E}`)).status, 502); // reap failed mid-cascade
  assert(store.has(`events/${E}/metadata.json`)); // marker deleted LAST → still discoverable as expired
  failing.clear();
  assertEquals((await app.request(`/events/${E}`)).status, 404); // next touch completes the reap
  assertEquals([...store.keys()], []);
});
