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
import { createApp as createRealApp, type Deps, type FetchLike } from "../../src/app.ts";
import { mintToken } from "../../src/attest.ts";
import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import { type Db, insertEvent } from "../../src/db.ts";
import { migrate } from "../../src/migrations.ts";

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
  attestTokenKey: "test-attest-token-key",
  appAttestRootCa: "",
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
  await migrate(db);
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

/** The real app with NOTHING attached — for the routes that must be reachable without a token. */
export { createRealApp };

/** Give a device the attestation row every device-scoped write now requires (capability `database`). */
export { enrolDevice } from "./db.ts";

export async function rows(db: Db, sql: string, args: unknown[] = []) {
  return (await db.execute(sql, args)).rows;
}

/**
 * Seed one resource row directly, expressed as the FACT a test means rather than as the columns that
 * happen to hold it: this device has (or has not) had these bytes recorded as arrived.
 *
 * Every direct `INSERT INTO resources` in the suite goes through here. That is the whole point — the
 * column list is schema knowledge, and keeping it in one place means a schema change edits one function
 * instead of every test that needed a starting state.
 */
export async function seedResource(db: Db, r: {
  deviceId: string;
  key: string;
  assetId?: string;
  role?: string;
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
  await db.execute(
    `INSERT INTO resources (device_id, asset_id, role, key, content_type, filename)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [
      r.deviceId,
      r.assetId ?? "A",
      r.role ?? "primary",
      r.key,
      r.contentType ?? "image/heic",
      r.filename ?? `Capture ${r.key}`,
    ],
  );
}

/**
 * Assert `url` is a presigned S3 GET for the bare object key `key`: path-style origin+path against the S3
 * endpoint, 7-day expiry, and an AWS4-HMAC-SHA256 signature. The signature is time-dependent, so this
 * asserts the shape, not an exact string.
 */
export function assertPresigned(url: string, key: string) {
  const u = new URL(url);
  assertEquals(`${u.origin}${u.pathname}`, `${S3_ZONE}/${key}`);
  assertEquals(u.searchParams.get("X-Amz-Algorithm"), "AWS4-HMAC-SHA256");
  assertEquals(u.searchParams.get("X-Amz-Expires"), "604800");
  assert((u.searchParams.get("X-Amz-Signature") ?? "").length > 0);
}
