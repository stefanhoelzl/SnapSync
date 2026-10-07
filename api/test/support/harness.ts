// The shared machinery every API test stands on: a pinned clock, a valid device token, an in-memory
// migrated store, a storage-call recorder, and the presign assertion.
//
// WHAT BELONGS HERE AND WHAT DOES NOT. This module holds machinery — things that encode the SCHEMA or the
// test rig, which every version genuinely shares. It does NOT hold fixtures: paths, request bodies and
// expected response shapes encode a version's CONTRACT, and those stay literal in each version's own file
// even where the two versions would spell them identically today.
//
// That split is deliberate and inverts the usual instinct to hoist duplication. `v1.test.ts` is a frozen
// contract: v1's wire behaviour must not move while it is served. If it imported a fixture builder that
// `v2.test.ts` also used, a change made for v2 could silently move what v1 asserts — which is exactly the
// failure the file split exists to prevent. Duplication between the two files is the point.

import { assert, assertEquals } from "@std/assert";
import { createApp as createAppUnquiet, type Deps, type FetchLike } from "../../src/app.ts";
import { mintToken } from "../../src/attest.ts";
import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import { type Db, enroll, insertEvent } from "../../src/db.ts";
import { replay } from "../../src/dev/replay.ts";

export const NOW = Date.parse("2026-07-14T12:00:00Z");

export const E = "7a3f9c21-0000-4000-8000-000000000001"; // an eventId
export const D = "11111111-0000-4000-8000-000000000002"; // a deviceId
export const D2 = "22222222-0000-4000-8000-000000000003"; // a second deviceId

export const CONFIG = {
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
  fcmProjectId: "",
  fcmServiceAccountKey: "",
  attestTokenKey: "test-attest-token-key",
  appAttestRootCa: "",
  androidPackageName: "app.snapsync",
  androidSigningCertDigests: [],
  androidAttestationRoots: [],
  androidAttestationTrust: "hardware" as const,
  attestTokenTtlSeconds: 30 * 24 * 60 * 60,
  attestAppId: "E9Z8BADH58.app.snapsync",
  linkDomain: "snapsync.stho.net",
  appStoreUrl: "https://apps.apple.com/app/id6781692480",
  eventCapacity: 10,
  eventWindowMaxSeconds: 30 * 24 * 60 * 60,
  eventLifetimeSeconds: 30 * 24 * 60 * 60,
  minAppVersion: "0.1",
  maintenance: false,
  databaseUrl: "",
  databaseToken: "",
};

export const TOKEN = await mintToken(CONFIG, D, NOW);

/** The header every device request names its app version in — what the v2 version gate reads. */
export const VERSION_HEADER = "x-snapsync-app-version";
/**
 * Headers naming a version every `/api/v2` route serves, for a test whose subject is not the version gate
 * (capability `app-update-required`).
 */
export const V2 = { [VERSION_HEADER]: "99.0" };

/**
 * The `authorization` header of a request made AS `deviceId` — for a test acting for a device other than
 * {@link D}, since a token acts only for the device it was minted for (`actsFor` in `app.ts`). Passed in a
 * request's `headers`, it replaces the {@link TOKEN} {@link createApp} attaches.
 */
export async function as(deviceId: string): Promise<{ authorization: string }> {
  return { authorization: `Bearer ${await mintToken(CONFIG, deviceId, NOW)}` };
}

export const ZONE = `https://storage.bunnycdn.com/snapsync-zone`;
export const S3_ZONE = `${CONFIG.s3Scheme}://${CONFIG.s3Host}/${CONFIG.zone}`;

export const STARTS_AT = "2026-06-27T18:00:00Z";
export const ENDS_AT = "2026-07-27T18:00:00Z"; // startsAt + the configured 30 days
export const EVENT = {
  eventId: E,
  name: "Party",
  createdAt: "2026-06-27T00:00:00Z",
  startsAt: STARTS_AT,
  endsAt: ENDS_AT,
  capacity: 10,
  lifetimeSeconds: 30 * 24 * 60 * 60,
};

export type Call = { url: string; init: RequestInit };

/**
 * Records upstream STORAGE calls. Only the byte objects and the attestation record live there now, so the
 * fake is correspondingly small: a PUT answers `status` (201 by default), or throws when `throws`.
 */
export function recorder(opts: { status?: number; throws?: boolean } = {}) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    if (opts.throws) return Promise.reject(new Error("network boom"));
    return Promise.resolve(new Response(null, { status: opts.status ?? 201 }));
  };
  return { calls, fetchImpl };
}

/** A migrated in-memory store. Every test gets its own, so none can observe another's rows. */
export async function store(): Promise<Db & { close(): void }> {
  const db = sqliteDb(":memory:");
  await replay(db);
  return db;
}

/** A store already holding {@link EVENT} — the starting point for every event-scoped route's tests. */
export async function storeWithEvent(overrides: Partial<typeof EVENT> = {}) {
  const db = await store();
  await insertEvent(db, { ...EVENT, ...overrides });
  return db;
}

/** The real app, with the clock pinned and a valid device token attached to every request. */
export function createApp(deps: Omit<Deps, "now">) {
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

/**
 * The real app with NOTHING attached — for the routes that must be reachable without a token. Its request
 * log is silent unless the test hands it a sink: a test that asserts a line collects them itself.
 */
export function createRealApp(deps: Deps) {
  return createAppUnquiet({ logSink: () => {}, ...deps });
}

/** Give a device the attestation row every device-scoped write now requires (`docs/architecture.md`). */
export { enrolDevice } from "./db.ts";

export async function rows(db: Db, sql: string, args: unknown[] = []) {
  return (await db.execute(sql, args)).rows;
}

/**
 * Make `deviceId` a member of `eventId` (`sharing`), straight through the store and with no route — so no
 * app version or other side effect is recorded. A byte route files its upload under a PRESENT membership
 * (change `per-event-storage-layout`), so every upload a test makes starts here.
 */
export async function joinEvent(db: Db, eventId: string, deviceId: string) {
  const outcome = await enroll(db, eventId, deviceId, new Date(NOW).toISOString());
  assertEquals(outcome, "enrolled");
}

/**
 * Seed one resource row directly, expressed as the FACT a test means rather than as the columns that
 * happen to hold it: this device has (or has not) had these bytes recorded as arrived, in this event.
 *
 * Every direct `INSERT INTO resources` in the suite goes through here. That is the whole point — the
 * column list is schema knowledge, and keeping it in one place means a schema change edits one function
 * instead of every test that needed a starting state. The membership must exist (the row is its child).
 */
export async function seedResource(db: Db, r: {
  eventId: string;
  deviceId: string;
  assetId?: string;
  role?: string;
  /** Where the bytes are. Defaults to the pre-0010 layout's `files/devices/<deviceId>/<assetId>-<role>.heic`. */
  path?: string;
  contentType?: string;
  filename?: string;
  /** Whether the backend has recorded this resource's bytes as arrived. Defaults to true. */
  uploaded?: boolean;
}) {
  // NOT UPLOADED IS AN ABSENT ROW. The schema carries no upload flag: the row's existence IS the record
  // that the bytes arrived, written by the one route that watched them arrive. So a caller asking for a
  // not-yet-uploaded resource is asking for no row at all — the same fact the retired `uploaded = 0`
  // expressed, spelled the way the store now holds it.
  if ((r.uploaded ?? true) === false) return;
  const assetId = r.assetId ?? "A";
  const role = r.role ?? "primary";
  await db.execute(
    `INSERT INTO resources (event_id, device_id, asset_id, role, path, content_type, filename)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [
      r.eventId,
      r.deviceId,
      assetId,
      role,
      r.path ?? `files/devices/${r.deviceId}/${assetId}-${role}.heic`,
      r.contentType ?? "image/heic",
      r.filename ?? `IMG_${assetId}.HEIC`,
    ],
  );
}

/**
 * Assert `url` is a presigned S3 GET for the bare object key `key`: path-style origin+path against the S3
 * endpoint, the expiry (the devices' 7 days unless [expires] names another), and an AWS4-HMAC-SHA256 signature. The signature is time-dependent, so this
 * asserts the shape, not an exact string.
 */
export function assertPresigned(url: string, key: string, expires = "604800") {
  const u = new URL(url);
  assertEquals(`${u.origin}${u.pathname}`, `${S3_ZONE}/${key}`);
  assertEquals(u.searchParams.get("X-Amz-Algorithm"), "AWS4-HMAC-SHA256");
  assertEquals(u.searchParams.get("X-Amz-Expires"), expires);
  assert((u.searchParams.get("X-Amz-Signature") ?? "").length > 0);
}

/**
 * A config carrying a REAL ES256 key, generated per run.
 *
 * The APNs sender signs its provider JWT lazily and catches a signing failure PER TOKEN — so with the
 * placeholder PEM in {@link CONFIG}, every push is reported failed and none is ever sent. That is
 * faithful to the route's best-effort contract, but it would make a fan-out test pass while asserting
 * nothing, which is precisely the failure a fan-out test exists to catch.
 *
 * Machinery, not a fixture: it encodes how the rig makes a push observable, not what any version's wire
 * contract is.
 */
export async function apnsConfig() {
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

/**
 * [config] able to send through FCM for the Firebase project `test-project`: a real RSA service-account key, so the
 * sender really signs its token-exchange assertion.
 */
export async function withFcm<C extends object>(
  config: C,
): Promise<C & { fcmProjectId: string; fcmServiceAccountKey: string }> {
  const kp = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", kp.privateKey));
  let bin = "";
  for (const b of pkcs8) bin += String.fromCharCode(b);
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(bin)}\n-----END PRIVATE KEY-----\n`;
  const key = JSON.stringify({
    client_email: "sender@test-project.iam.gserviceaccount.com",
    private_key: pem,
  });
  return { ...config, fcmProjectId: "test-project", fcmServiceAccountKey: key };
}

/**
 * [inner] with Google's two FCM endpoints faked in front of it: the token exchange answers an access token, and each
 * FCM send is recorded (its device token) and answered 200.
 */
export function fcmRecorder(inner: FetchLike) {
  const sent: string[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    if (url === "https://oauth2.googleapis.com/token") {
      return Promise.resolve(Response.json({ access_token: "ACCESS", expires_in: 3599 }));
    }
    if (url.startsWith("https://fcm.googleapis.com/")) {
      sent.push(JSON.parse(init?.body as string).message.token);
      return Promise.resolve(new Response("{}", { status: 200 }));
    }
    return inner(url, init);
  };
  return { sent, fetchImpl };
}

/** An APNs-shaped fetch fake: records the pushes (and their headers) and answers each with `status`. */
export function apnsRecorder(status = 200) {
  const pushed: string[] = [];
  const headers: Record<string, string>[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    if (url.includes("api.push.apple.com") || url.includes("api.sandbox.push.apple.com")) {
      pushed.push(url.split("/").pop()!);
      headers.push({ ...(init?.headers as Record<string, string> ?? {}) });
      return Promise.resolve(new Response(null, { status }));
    }
    return Promise.resolve(new Response(null, { status: 201 }));
  };
  return { pushed, headers, fetchImpl };
}
