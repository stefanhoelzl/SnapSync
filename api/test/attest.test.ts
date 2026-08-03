import { assert, assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import {
  b64ToBytes,
  challengeIsValid,
  mintChallenge,
  mintToken,
  verifyAttestation,
  verifyToken,
} from "../src/attest.ts";
import { type Config, readConfig } from "../src/config.ts";
import { ATTESTATION_SAMPLE } from "./fixtures/attestation-sample.ts";

// The gate (capability `device-attestation`). app.test.ts wraps `createApp` so every request carries a
// token — it proves the gate does not BREAK the routes. This file uses the app RAW, and proves the gate
// is actually there: that an unauthenticated caller gets nothing, reveals nothing, and writes nothing.

const E = "7a3f9c21-0000-4000-8000-000000000001";
const D = "11111111-0000-4000-8000-000000000002";
const NOW = Date.parse("2026-07-14T12:00:00Z");
const DAY = 24 * 60 * 60 * 1000;

/** A REAL attestation from a REAL device (see the fixture's header). */
const SAMPLE = ATTESTATION_SAMPLE;

/** Apple's root, taken from the source constant it ships as — never restated here. */
const APPLE_ROOT_CA = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
  ADMIN_NOTIFY_KEY: "a",
}).appAttestRootCa;

const CONFIG: Config = {
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
  appAttestRootCa: APPLE_ROOT_CA,
  attestTokenTtlSeconds: 30 * DAY / 1000,
  attestAppId: "E9Z8BADH58.app.snapsync",
  linkDomain: "snapsync.stho.net",
  appStoreUrl: "https://apps.apple.com/app/id6781692480",
  eventCapacity: 10,
  eventWindowMaxSeconds: 30 * 24 * 60 * 60,
  eventLifetimeSeconds: 30 * 24 * 60 * 60,
};

/** The same config, but claiming the FIXTURE's app — so the real attestation's rpIdHash matches. */
const SAMPLE_CONFIG: Config = {
  ...CONFIG,
  attestAppId: `${SAMPLE.teamIdentifier}.${SAMPLE.bundleIdentifier}`,
};
/** The fixture's leaf certificate expired years ago; verify the chain at the instant it was produced. */
const SAMPLE_AT = new Date(SAMPLE.timestamp);
/** The fixture's clientData is the raw string the device hashed into its nonce. */
const SAMPLE_CHALLENGE = new TextDecoder().decode(b64ToBytes(SAMPLE.clientDataBase64));

type Call = { url: string; init: RequestInit };

/** Records upstream calls; every GET 404s and every write succeeds, unless `objects` says otherwise. */
function recorder(objects: Record<string, string> = {}) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    const key = url.split("/snapsync-zone/")[1] ?? "";
    if ((init.method ?? "GET") === "GET") {
      const body = objects[key];
      return Promise.resolve(
        body === undefined
          ? new Response(null, { status: 404 })
          : new Response(body, { status: 200 }),
      );
    }
    return Promise.resolve(new Response(null, { status: 201 }));
  };
  return { calls, fetchImpl };
}

const app = (objects: Record<string, string> = {}) => {
  const { calls, fetchImpl } = recorder(objects);
  return { calls, app: createApp({ config: CONFIG, fetch: fetchImpl, now: () => NOW }) };
};

const token = await mintToken(CONFIG, D, NOW);
const bearer = { authorization: `Bearer ${token}` };

// ── The verifier is not a rubber stamp ────────────────────────────────────────────────────────────

Deno.test("attestation: a REAL attestation from a REAL device verifies", async () => {
  const result = await verifyAttestation(SAMPLE_CONFIG, {
    attestation: b64ToBytes(SAMPLE.attestationBase64),
    challenge: SAMPLE_CHALLENGE,
    keyId: b64ToBytes(SAMPLE.keyIdBase64),
    at: SAMPLE_AT,
  });
  assertEquals(result.environment, "development");
  assertEquals(result.publicKey.length, 65); // a raw uncompressed EC point
  assertEquals(result.publicKey[0], 0x04);
});

// Each of these MUST throw. A verifier that accepts them is worse than no verifier, because it would
// look like a gate while admitting anyone.
const rejects = async (name: string, mutate: (o: Record<string, unknown>) => void) => {
  const opts: Record<string, unknown> = {
    attestation: b64ToBytes(SAMPLE.attestationBase64),
    challenge: SAMPLE_CHALLENGE,
    keyId: b64ToBytes(SAMPLE.keyIdBase64),
    at: SAMPLE_AT,
  };
  let config = SAMPLE_CONFIG;
  if (name === "a different app") config = CONFIG; // our real app id, not the fixture's
  mutate(opts);
  let threw = false;
  try {
    await verifyAttestation(config, opts as never);
  } catch {
    threw = true;
  }
  assert(threw, `attestation with ${name} was ACCEPTED — the verifier is a rubber stamp`);
};

Deno.test("attestation: a different challenge is rejected (no replay against another nonce)", () =>
  rejects("a different challenge", (o) => {
    o.challenge = "not-the-challenge-it-was-made-for";
  }));

Deno.test("attestation: a different app is rejected (rpIdHash is not ours)", () =>
  rejects("a different app", () => {}));

Deno.test("attestation: a mismatched keyId is rejected", () =>
  rejects("a mismatched keyId", (o) => {
    const k = b64ToBytes(SAMPLE.keyIdBase64);
    k[0] ^= 0xff;
    o.keyId = k;
  }));

Deno.test("attestation: a tampered attestation statement is rejected", () =>
  rejects("a tampered attStmt", (o) => {
    const a = b64ToBytes(SAMPLE.attestationBase64);
    a[a.length - 40] ^= 0xff;
    o.attestation = a;
  }));

Deno.test("attestation: an expired certificate chain is rejected", () =>
  rejects("an expired chain", (o) => {
    o.at = new Date(SAMPLE_AT.getTime() + 3650 * DAY);
  }));

// ── The token ─────────────────────────────────────────────────────────────────────────────────────

Deno.test("token: a freshly minted token verifies and names its device", async () => {
  assertEquals(await verifyToken(CONFIG, await mintToken(CONFIG, D, NOW), NOW), D);
});

Deno.test("token: an expired token is refused", async () => {
  const t = await mintToken(CONFIG, D, NOW);
  assertEquals(await verifyToken(CONFIG, t, NOW + 31 * DAY), null); // TTL is 30d
  assertEquals(await verifyToken(CONFIG, t, NOW + 29 * DAY), D); // …but not a day early
});

Deno.test("token: a tampered token is refused", async () => {
  const t = await mintToken(CONFIG, D, NOW);
  const [dev, exp, sig] = t.split(".");
  // Re-pointing the token at another device invalidates the signature — a token cannot be re-aimed.
  assertEquals(await verifyToken(CONFIG, `${E}.${exp}.${sig}`, NOW), null);
  // Nor can its expiry be pushed out.
  assertEquals(await verifyToken(CONFIG, `${dev}.${Number(exp) + 999999}.${sig}`, NOW), null);
});

Deno.test("token: a token signed with a different key is refused", async () => {
  const foreign = await mintToken({ ...CONFIG, attestTokenKey: "someone-elses-key" }, D, NOW);
  assertEquals(await verifyToken(CONFIG, foreign, NOW), null);
});

Deno.test("challenge: ours is valid inside its window, invalid outside it, and forgery fails", async () => {
  const c = await mintChallenge(CONFIG, NOW);
  assert(await challengeIsValid(CONFIG, c, NOW));
  assert(!await challengeIsValid(CONFIG, c, NOW + 10 * 60 * 1000)); // 5-minute window
  assert(!await challengeIsValid(CONFIG, "9999999999.bm90LWEtc2ln", NOW)); // forged
});

// ── The gate ──────────────────────────────────────────────────────────────────────────────────────

// The GATED routes. `GET /events/<id>` and `GET /events/<id>/files` are NOT here: they were moved off the
// gate (capability `web-event-download`) so the no-app download page can read them un-attested — see the
// "served without a token" tests below. Every WRITE, and the per-device raw listing, stays gated.
const GATED: [string, RequestInit][] = [
  ["/api/v1/events", { method: "POST", body: JSON.stringify({ name: "x" }) }],
  // The rename (capability `event-rename`) lands on the SAME path shape as the ungated marker read, so
  // it is the closest call in this list: only the `publicRead` method check separates them. Pinned here
  // so relaxing that check can never silently open a write.
  [`/api/v1/events/${E}`, { method: "PATCH", body: JSON.stringify({ name: "x" }) }],
  [`/api/v1/events/${E}/notify`, { method: "POST" }],
  [`/api/v1/events/${E}/devices/${D}`, { method: "PUT", body: "{}" }],
  [`/api/v1/events/${E}/devices/${D}`, { method: "DELETE" }],
  [`/api/v1/files/devices/${D}`, {}],
  [`/api/v1/files/devices/${D}/IMG_0001-photo.jpg`, { method: "PUT", body: "bytes" }],
  [`/api/v1/devices/${D}`, { method: "PUT", body: "{}" }],
];

Deno.test("gate: EVERY route refuses an unauthenticated request, and touches no storage", async () => {
  for (const [path, init] of GATED) {
    const { calls, app: a } = app();
    const res = await a.request(path, init);
    assertEquals(res.status, 401, `${init.method ?? "GET"} ${path} was not gated`);
    assertEquals(
      calls.length,
      0,
      `${init.method ?? "GET"} ${path} touched storage while unauthenticated`,
    );
  }
});

Deno.test("gate: EVERY route accepts a valid token", async () => {
  for (const [path, init] of GATED) {
    const { app: a } = app({
      [`events/${E}/metadata.json`]: JSON.stringify({ eventId: E, name: "x", createdAt: "t" }),
    });
    const res = await a.request(path, { ...init, headers: bearer });
    assert(res.status !== 401, `${init.method ?? "GET"} ${path} rejected a VALID token`);
  }
});

Deno.test("gate: an expired token is refused like no token at all", async () => {
  const stale = await mintToken(CONFIG, D, NOW - 31 * DAY);
  const { calls, app: a } = app();
  const res = await a.request(`/api/v1/files/devices/${D}/x.jpg`, {
    method: "PUT",
    body: "bytes",
    headers: { authorization: `Bearer ${stale}` },
  });
  assertEquals(res.status, 401);
  assertEquals(calls.length, 0); // and the photo bytes were never streamed upstream
});

Deno.test("gate: the event marker and union reads are served WITHOUT a token", async () => {
  // Capability `web-event-download`: these two reads are authorized by eventId-possession alone, so the
  // no-app download page (a browser with no attestation) can fetch them. A missing event is a 404, not a
  // 401 — existence-probing by a tokenless caller is the accepted, eyes-open consequence of opening them.
  const { app: a } = app(); // no marker → the event does not exist
  assertEquals((await a.request(`/api/v1/events/${E}`)).status, 404); // NOT 401
  assertEquals((await a.request(`/api/v1/events/${E}/files`)).status, 404); // NOT 401
});

Deno.test("gate: opening the reads opens no WRITE — mutating /events/<id>/… stays gated", async () => {
  const { app: a } = app();
  assertEquals((await a.request("/api/v1/events", { method: "POST", body: "{}" })).status, 401);
  assertEquals((await a.request(`/api/v1/events/${E}/notify`, { method: "POST" })).status, 401);
  assertEquals(
    (await a.request(`/api/v1/events/${E}/devices/${D}`, { method: "PUT", body: "{}" })).status,
    401,
  );
  assertEquals(
    (await a.request(`/api/v1/events/${E}/devices/${D}`, { method: "DELETE" })).status,
    401,
  );
  // A POST to the ungated READ paths themselves is a mutating method → still gated.
  assertEquals((await a.request(`/api/v1/events/${E}`, { method: "POST" })).status, 401);
  assertEquals((await a.request(`/api/v1/events/${E}/files`, { method: "POST" })).status, 401);
  // …and so is the rename, which is a real handler on exactly the ungated read's path (capability
  // `event-rename`). Reading an event un-attested must never imply renaming it.
  assertEquals(
    (await a.request(`/api/v1/events/${E}`, {
      method: "PATCH",
      body: JSON.stringify({ name: "x" }),
    }))
      .status,
    401,
  );
  // The per-device RAW listing has no web consumer and stays gated (defense in depth).
  assertEquals((await a.request(`/api/v1/files/devices/${D}`)).status, 401);
});

// ── The retired notify admin key (capabilities `event-notify-endpoint`, `backend-deployment`) ─────────
//
// A valid device token is now the ONLY credential this backend accepts. The former ADMIN_NOTIFY_KEY
// existed solely so the out-of-edge sweep could announce an expiring event before deleting it; that
// announcement is gone (capability `scheduled-cleanup`), so the credential was retired rather than left
// standing as an authorization path with no caller. These pin that it authorizes NOTHING.

Deno.test("gate: notify with no device token is refused 401, reading nothing", async () => {
  const { calls, app: a } = app();
  const res = await a.request(`/api/v1/events/${E}/notify`, { method: "POST" });
  assertEquals(res.status, 401);
  assertEquals(calls.length, 0); // no marker read, no member enumeration, no push
});

Deno.test("gate: the retired admin key authorizes nothing — not even notify", async () => {
  const { calls, app: a } = app();
  const stale = { authorization: "Bearer test-admin-key" };
  // The route it used to authorize…
  assertEquals(
    (await a.request(`/api/v1/events/${E}/notify`, { method: "POST", headers: stale })).status,
    401,
  );
  // …event creation…
  assertEquals(
    (await a.request(`/api/v1/events`, { method: "POST", body: "{}", headers: stale })).status,
    401,
  );
  // …a device-manifest PUT (join)…
  assertEquals(
    (await a.request(`/api/v1/events/${E}/devices/${D}`, {
      method: "PUT",
      body: "{}",
      headers: stale,
    })).status,
    401,
  );
  // …and a leave.
  assertEquals(
    (await a.request(`/api/v1/events/${E}/devices/${D}`, { method: "DELETE", headers: stale }))
      .status,
    401,
  );
  assertEquals(calls.length, 0); // every one refused before any upstream read
});

Deno.test("gate: OPTIONS is answered without a token (the pull zone may answer it anyway)", async () => {
  const { app: a } = app();
  const res = await a.request(`/api/v1/files/devices/${D}/IMG_0001-photo.jpg`, {
    method: "OPTIONS",
  });
  assertEquals(res.status, 204);
  assertEquals(res.headers.get("Allow"), "PUT, OPTIONS"); // still no resumable → plain PUT
});

Deno.test("gate: the marketing page at / is served without a token", async () => {
  const { calls, app: a } = app({ "site/index.html": "<!doctype html>hi" });
  const res = await a.request("/");
  assertEquals(res.status, 200); // NOT 401 — `marketing-site` is on the closed ungated list
  // It is admitted without a token and served by proxying the PUBLIC `site/` prefix (capability
  // `web-site`) — exactly one storage GET, of the public page, never the gated user data.
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url.endsWith("/site/index.html"), true);
});

Deno.test("gate: the / exception is exact-path and GET/HEAD-only — it leaks to nothing else", async () => {
  const { app: a } = app();
  // A non-root path is still gated, even for GET… (`/api/v1/events/<id>` and `…/files` are ungated by their OWN
  // exception now, so use a GET route that is still gated — the per-device raw listing.)
  assertEquals((await a.request("/api/v1/events")).status, 401);
  assertEquals((await a.request(`/api/v1/files/devices/${D}`)).status, 401);
  // …a path that merely begins with "/" but is not exactly "/" is not admitted…
  assertEquals((await a.request("/index.html")).status, 401);
  // …and a mutating method on "/" is gated, not served.
  assertEquals((await a.request("/", { method: "POST" })).status, 401);
});

Deno.test("gate: the event link's AASA is served without a token", async () => {
  const { calls, app: a } = app();
  const res = await a.request("/.well-known/apple-app-site-association");
  // NOT 401 — Apple's CDN and the device fetch this with no Authorization header and cannot be made to
  // send one, so gating it would silently defeat EVERY event link (capability `event-link`).
  assertEquals(res.status, 200);
  assertEquals(calls.length, 0); // and serving it reads no storage
});

Deno.test("gate: the /join download page is served without a token", async () => {
  const { calls, app: a } = app({ "site/join/index.html": "<!doctype html>join" });
  const res = await a.request("/join");
  await res.body?.cancel();
  assertEquals(res.status, 200); // NOT 401 — its entire audience has no app, and so no attestation
  // Admitted without a token and served by proxying the PUBLIC site/ prefix — one storage GET.
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url.endsWith("/site/join/index.html"), true);
});

Deno.test("gate: the event-link exceptions are exact-path and GET/HEAD-only — they leak to nothing else", async () => {
  const { app: a } = app();
  // A path that merely BEGINS with an admitted one is not admitted…
  assertEquals((await a.request("/joinx")).status, 401);
  assertEquals((await a.request("/join/anything")).status, 401);
  assertEquals((await a.request("/.well-known/other")).status, 401);
  assertEquals((await a.request("/.well-known/apple-app-site-association/x")).status, 401);
  // …and a mutating method on either is gated, not served.
  assertEquals((await a.request("/join", { method: "POST" })).status, 401);
  assertEquals(
    (await a.request("/.well-known/apple-app-site-association", { method: "POST" })).status,
    401,
  );
});

Deno.test("gate: a gated GET is never cacheable (the pull zone does not vary on Authorization)", async () => {
  // Load-bearing for AUTHORIZATION, not just freshness: the CDN forwards `Authorization` but does not
  // key its cache on it, so a cacheable gated response would be served to a DIFFERENT device.
  const { app: a } = app();
  const res = await a.request(`/api/v1/files/devices/${D}`, { headers: bearer });
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
});

// ── The versioned prefix (capability `backend-deployment`) ──────────────────────────────────────────
//
// Device-API routes are served under `/api/v1` — the shape every gate test above already exercises. The
// gate normalizes the `/api/vN` prefix before its closed-list checks, so the one ungated set that IS a
// device route, `/api/v1/attest/*`, holds under it. Web/link paths are NOT served under the prefix.

Deno.test("gate: web/link paths are NOT served under /api/v1 (they stay at the root)", async () => {
  const { app: a } = app();
  // Each is served at the ROOT (covered above) but must not resolve under the API prefix. The gate
  // normalizes `/api/v1/join` → `/join` and admits it (ungated), but no route is mounted there → 404 —
  // never the root's 200/302.
  assertEquals((await a.request("/api/v1/")).status, 404);
  assertEquals((await a.request("/api/v1/join")).status, 404);
  assertEquals((await a.request("/api/v1/.well-known/apple-app-site-association")).status, 404);
});

// ── The attest routes ─────────────────────────────────────────────────────────────────────────────

Deno.test("attest: the challenge route needs no token and writes nothing", async () => {
  const { calls, app: a } = app();
  const res = await a.request("/api/v1/attest/challenge");
  assertEquals(res.status, 200);
  const { challenge } = await res.json() as { challenge: string };
  assert(await challengeIsValid(CONFIG, challenge, NOW));
  assertEquals(calls.length, 0); // the one route a stranger can call cannot grow the bill
});

Deno.test("attest: a stale challenge mints no token and stores no key", async () => {
  const { calls, app: a } = app();
  const stale = await mintChallenge(CONFIG, NOW - 10 * 60 * 1000);
  const res = await a.request("/api/v1/attest/token", {
    method: "POST",
    body: JSON.stringify({
      deviceId: D,
      keyId: SAMPLE.keyIdBase64,
      attestation: SAMPLE.attestationBase64,
      challenge: stale,
    }),
  });
  assertEquals(res.status, 401);
  assertEquals(calls.length, 0);
});

Deno.test("attest: a rejected attestation mints no token and stores no key", async () => {
  const { calls, app: a } = app();
  const challenge = await mintChallenge(CONFIG, NOW);
  // A real attestation, but for a different app and a different challenge — it must not be accepted.
  const res = await a.request("/api/v1/attest/token", {
    method: "POST",
    body: JSON.stringify({
      deviceId: D,
      keyId: SAMPLE.keyIdBase64,
      attestation: SAMPLE.attestationBase64,
      challenge,
    }),
  });
  assertEquals(res.status, 401);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0); // nothing persisted
});

Deno.test("renew: a device that never attested must attest, not renew", async () => {
  const { app: a } = app(); // no devices/<id>.attest.json
  const res = await a.request("/api/v1/attest/renew", {
    method: "POST",
    body: JSON.stringify({
      deviceId: D,
      assertion: "AA==",
      challenge: await mintChallenge(CONFIG, NOW),
    }),
  });
  assertEquals(res.status, 401);
});

// ── Leave is rename-only (capability `event-leave-endpoint`) ─────────────────────────────────────────

Deno.test("leave: the departing device's config + attestation record are RETAINED (no leave-time GC)", async () => {
  // Leaving is non-destructive now: it renames the active manifest to `.left.json` and returns 200,
  // touching neither the config nor the attestation record. A fully-orphaned device is collected only by
  // the nightly sweep (capability `scheduled-cleanup`), not by leave.
  const calls: Call[] = [];
  const store = new Map<string, string>([
    [
      `events/${E}/metadata.json`,
      JSON.stringify({
        eventId: E,
        name: "x",
        createdAt: "t",
        startsAt: "2026-07-01T00:00:00Z",
        endsAt: "2026-07-31T00:00:00Z",
        capacity: 10,
      }),
    ],
    [`events/${E}/devices/${D}.json`, JSON.stringify({ deviceId: D, assets: [] })],
    [`devices/${D}.json`, "{}"],
    [`devices/${D}.attest.json`, JSON.stringify({ publicKey: "AA==", environment: "production" })],
  ]);
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    const key = url.split("/snapsync-zone/")[1] ?? "";
    const method = init.method ?? "GET";
    if (method === "GET" && key.endsWith("/")) {
      const children = [...store.keys()]
        .filter((k) => k.startsWith(key))
        .map((k) => ({
          ObjectName: k.slice(key.length),
          Length: 1,
          IsDirectory: false,
          LastChanged: "2026-07-14T00:00:00.000Z",
        }));
      return Promise.resolve(new Response(JSON.stringify(children), { status: 200 }));
    }
    if (method === "GET") {
      const body = store.get(key);
      return Promise.resolve(
        body === undefined
          ? new Response(null, { status: 404 })
          : new Response(body, { status: 200 }),
      );
    }
    if (method === "PUT") store.set(key, typeof init.body === "string" ? init.body : "{}");
    if (method === "DELETE") store.delete(key);
    return Promise.resolve(new Response(null, { status: 200 }));
  };

  const res = await createApp({ config: CONFIG, fetch: fetchImpl, now: () => NOW })
    .request(`/api/v1/events/${E}/devices/${D}`, { method: "DELETE", headers: bearer });
  assertEquals(res.status, 200);

  const deleted = calls.filter((c) => c.init.method === "DELETE").map((c) =>
    c.url.split("/snapsync-zone/")[1]
  );
  // The ONLY delete leave performs is the active manifest (renamed to .left.json). Nothing else.
  assertEquals(deleted, [`events/${E}/devices/${D}.json`]);
  assert(store.has(`devices/${D}.attest.json`), "attestation record must be retained by leave");
  assert(store.has(`devices/${D}.json`), "config must be retained by leave");
  assert(store.has(`events/${E}/devices/${D}.left.json`), "departed manifest must be written");
  assert(store.has(`events/${E}/metadata.json`), "event marker must survive leave");
});
