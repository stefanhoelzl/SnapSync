// Hono app for the backend (capabilities `event-creation` + `bunny-upload-endpoint` +
// `bunny-list-endpoint` + `device-config-endpoint` + `event-notify-endpoint` + `device-attestation`,
// over the shared `backend-deployment`; pushes via `apns-push-sender`).
//
// EVERY ROUTE BELOW REQUIRES A DEVICE TOKEN (capability `device-attestation`) — obtainable only by
// completing App Attest, so the API is callable by a genuine, unmodified SnapSync on a genuine Apple
// device and by nothing else. Exactly four things are ungated, and the list is CLOSED: the three
// `/attest/*` routes (self-authenticating — they issue the token) and `OPTIONS` (the pull zone may answer
// the preflight itself, so the script cannot gate it). See the middleware in `createApp`.
//
//   GET /attest/challenge
//     → a stateless, HMAC-signed, time-bounded nonce. Writes NOTHING.
//   POST /attest/token
//     → verifies an App Attest attestation (chain → Apple's root, nonce, app-id hash, counter, aaguid),
//       persists the attested public key at `devices/<id>.attest.json`, and mints a 30-day bearer token.
//   POST /attest/renew
//     → verifies a local Secure-Enclave ASSERTION against that stored key and mints a fresh token — no
//       Apple round-trip, because re-attestation is the throttled path.
//
//   POST /events
//     → mints an event: writes the marker `events/<id>/metadata.json`, returns {eventId,name,createdAt}.
//   GET /events/:eventId
//     → returns the event marker (existence check); 404 when absent.
//   PUT /devices/:deviceId
//     → streams a JSON device config (the push token) into `devices/<deviceId>.json`. UNGATED by
//       event; DEVICE-ID is the capability. Faithful 201/502; last-write-wins. A flat sibling of the
//       `files/devices/<deviceId>/` byte partition, so never listed as an asset.
//   POST /events/:eventId/notify
//     → sends a fixed SILENT (content-available) push to every ACTIVE member device (a departed
//       `<id>.left.json` member is skipped). GATED on the marker (404/502). Enumerate members
//       (LIST `events/<id>/devices/`, resolve active via last-write-wins) → read each `devices/<id>.json`
//       → best-effort fan-out via APNs. Bare 202 (no per-device results); 502 only if the member LIST
//       fails. No production caller wired (the trigger is a deferred use case).
//   PUT /files/devices/:deviceId/:filename
//     → streams the request body into ONE bunny native Storage PUT. Requires the token, but reads NO
//       marker: bytes are device-partitioned and event-independent (`files/devices/<deviceId>/<filename>`),
//       uploaded once and linked into events by reference. The device id remains self-asserted — the token
//       proves a genuine app instance, NOT ownership of the partition (a stated non-goal; the UUID is the
//       capability). The OS performs this PUT and DOES carry the header (verified on device). (There is no
//       download GET on this path — the listing hands out a presigned S3 URL fetched directly from S3.)
//   GET /files/devices/:deviceId
//     → lists the device's RAW stored objects (a single LIST of `files/devices/<deviceId>/`); each is
//       `{ filename, size, url }` where `url` is a presigned S3 GET URL. No manifest read, no
//       completeness, no event gate. `Cache-Control: no-store, no-cache, max-age=0` (time-limited urls;
//       see NO_CACHE — the pull zone honors `no-cache`, not `no-store`).
//   PUT /events/:eventId/devices/:deviceId
//     → streams a JSON device manifest into `events/<eventId>/devices/<deviceId>.json`. GATED on event
//       existence (the marker read) so a manifest is never written under a non-existent event.
//   DELETE /events/:eventId/devices/:deviceId
//     → LEAVE (capability `event-leave-endpoint`): renames the device's active manifest to
//       `<deviceId>.left.json` (departed — still served by the union, skipped by notify); if no ACTIVE
//       member remains (last-write-wins over the devices/ listing), reaps `events/<eventId>/` and GCs
//       each freed device's `files/devices/<id>/` bytes + `devices/<id>.json` config — but only for a
//       device that appears in no surviving event. GATED on the marker (404/502). Idempotent + leak-safe.
//   GET /events/:eventId/files
//     → the event-wide UNION: every contributing device's COMPLETE assets (an asset is complete iff
//       every resource its device.json names is present in `files/devices/<deviceId>/`), flattened across
//       devices, each tagged with its owning deviceId. GATED on event existence (marker read). Fans
//       out: marker → LIST `events/<id>/devices/` → per device (read device.json + LIST its files) →
//       complete-only projection. Faithful: any non-404 read failure anywhere (incl. a manifest JSON
//       parse failure) → 502 (never a partial union). `Cache-Control: no-store, no-cache, max-age=0`
//       (live read over mutable manifests + listings; see NO_CACHE). Identity-blind: own-vs-foreign
//       skip is the client's concern.
//
// EVENT REGISTRY: an event exists iff the object `events/<id>/metadata.json` is present. Because an
// eventId is a UUID, the marker key `events/<id>/metadata.json`, the device-manifest keys
// `events/<id>/devices/<deviceId>.json`, and the device-global byte store `files/devices/<deviceId>/…`
// are mutually disjoint and never collide. Existence is a small `GET` of the marker (bunny's Edge Storage
// API has no HEAD); a non-404 read failure surfaces as 502 (a transient failure is never mistaken for
// absence). Only the device-manifest write, the metadata route, and the event-wide union read the
// marker — the byte upload and per-device list routes are event-independent (they read no marker, though
// they still require the token). The token check ALWAYS runs first, so an unauthenticated caller cannot
// tell an existing event from a missing one.
//
// The per-device byte WRITE route is defined on a child Hono (`byteFile`) and mounted under
// `/files/devices/:deviceId/:filename` via app.route(), so PUT (upload) and OPTIONS share it.
// `deviceId`/`filename` are Hono's decoded path params (typed `string | undefined` through a mount,
// hence the guard); the filename is re-encoded per-segment when building the bunny URL, so the stored
// object is the real filename and keys stay flat. Config is injected (validated at startup). Upload
// invariants: pass-through only (never buffer/hash), faithful outcome (2xx only on confirmed store),
// last-write-wins. There is NO download route: the listing's `url` is a presigned S3 GET the device
// fetches directly from bunny's S3 endpoint (the short-read integrity check moves to the client).
//
// The list route returns the device's raw objects from a single bunny native Storage LIST of
// `files/devices/<deviceId>/` — no manifest content reads. Completeness is computed by the app (the shared
// gallery enumeration seam × this raw list), not server-side. Faithful: any LIST transport failure →
// 502 (never a partial list); a 404 on the device dir is "no objects" → 200 []. Each `url` is a
// presigned S3 GET URL (see `presignDownloadUrl`).

import { Hono } from "hono";
import { AwsClient } from "aws4fetch";
import {
  validateEventName,
  validateFilename,
  validateStartsAt,
  validateUUID,
} from "./validators.ts";
import type { Config } from "./config.ts";
import { createApnsSender, type PushToken } from "./apns.ts";
import {
  type AttestEnvironment,
  b64ToBytes,
  bytesToB64,
  challengeIsValid,
  mintChallenge,
  mintToken,
  verifyAssertion,
  verifyAttestation,
  verifyToken,
} from "./attest.ts";

// The marketing/landing page (capability `marketing-site`), embedded at build time. `deno bundle` inlines
// this text import, so the page ships inside the single bundle — served from memory, no runtime file read.
import LANDING_HTML from "./landing.html" with { type: "text" };

// The event registry's marker prefix. Because an eventId is a UUID, the marker
// `events/<id>/metadata.json` is disjoint from any device manifest `events/<id>/devices/<deviceId>.json`
// and from the byte store `files/devices/<deviceId>/…`.
const MARKER_PREFIX = "events";

/** Storage key of an event's marker object: `events/<eventId>/metadata.json`. */
function markerKey(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/metadata.json`;
}

/** Storage key of a device's per-event manifest: `events/<eventId>/devices/<deviceId>.json`. */
function deviceManifestKey(eventId: string, deviceId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/devices/${
    encodeURIComponent(deviceId)
  }.json`;
}

/** The per-event device-manifest directory to LIST: `events/<eventId>/devices/`. */
function deviceManifestDir(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/devices/`;
}

/**
 * Storage key of a device's **departed** manifest: `events/<eventId>/devices/<deviceId>.left.json`.
 * Leaving renames the active `<deviceId>.json` to this sibling (see the leave route); the union still
 * serves a departed device's photos, but notify skips it and the reap ignores it as an active member.
 */
function deviceLeftManifestKey(eventId: string, deviceId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/devices/${
    encodeURIComponent(deviceId)
  }.left.json`;
}

type MemberState = "active" | "departed";

/**
 * Parse one `events/<eventId>/devices/` child object name into its device id and whether it is the
 * departed (`.left.json`) or active (`.json`) manifest. `.left.json` is checked first because it also
 * ends with `.json`. Returns `null` for anything else (a stray object or a directory entry).
 */
function parseManifestObjectName(objectName: string): { deviceId: string; isLeft: boolean } | null {
  const decoded = decodeObjectName(objectName);
  if (decoded.endsWith(".left.json")) {
    return { deviceId: decoded.slice(0, -".left.json".length), isLeft: true };
  }
  if (decoded.endsWith(".json")) {
    return { deviceId: decoded.slice(0, -".json".length), isLeft: false };
  }
  return null;
}

/**
 * Resolve each device's membership from a single `events/<eventId>/devices/` listing, applying
 * **last-write-wins** when both a `<id>.json` (active) and a `<id>.left.json` (departed) sibling are
 * present: the newer object's state wins; an exact tie resolves to `active` (the leak-safe side; see
 * `device-manifest`). A device is counted once. This is the shared membership source for the union
 * (all devices), notify (active only), and the reap (any active remaining?).
 */
function resolveMembership(
  entries: BunnyEntry[] | null,
): { deviceId: string; state: MemberState }[] {
  const byDevice = new Map<string, { active?: number; left?: number }>();
  for (const e of entries ?? []) {
    if (e.IsDirectory) continue;
    const parsed = parseManifestObjectName(e.ObjectName);
    if (!parsed) continue;
    const parsedTime = Date.parse(e.LastChanged);
    const time = Number.isNaN(parsedTime) ? 0 : parsedTime;
    const slot = byDevice.get(parsed.deviceId) ?? {};
    if (parsed.isLeft) slot.left = Math.max(slot.left ?? -Infinity, time);
    else slot.active = Math.max(slot.active ?? -Infinity, time);
    byDevice.set(parsed.deviceId, slot);
  }
  const out: { deviceId: string; state: MemberState }[] = [];
  for (const [deviceId, slot] of byDevice) {
    const hasActive = slot.active !== undefined;
    const hasLeft = slot.left !== undefined;
    // Both present → LWW, active wins the tie; else whichever exists.
    const state: MemberState = hasActive && (!hasLeft || slot.active! >= slot.left!)
      ? "active"
      : "departed";
    out.push({ deviceId, state });
  }
  return out;
}

/** Storage key of a stored resource byte object: `files/devices/<deviceId>/<filename>`. */
function byteKey(deviceId: string, filename: string): string {
  return `files/devices/${encodeURIComponent(deviceId)}/${encodeURIComponent(filename)}`;
}

/** The device byte-store directory to LIST: `files/devices/<deviceId>/`. */
function deviceDir(deviceId: string): string {
  return `files/devices/${encodeURIComponent(deviceId)}/`;
}

/** Storage key of a device's config document (holds the push token): `devices/<deviceId>.json`. */
function deviceConfigKey(deviceId: string): string {
  return `devices/${encodeURIComponent(deviceId)}.json`;
}

/**
/**
 * Storage key of a device's attestation record: `devices/<deviceId>.attest.json` (capability
 * `device-attestation`). Holds the attested public key, written ONCE at attestation and read ONLY when
 * renewing — never on a gated request, so no route pays a storage read to authenticate. A flat sibling of
 * `devices/<deviceId>.json`, and disjoint from every other namespace.
 */
function deviceAttestKey(deviceId: string): string {
  return `devices/${encodeURIComponent(deviceId)}.attest.json`;
}

/** A device's attestation record: the attested public key, base64, plus which environment attested it. */
type AttestRecord = {
  publicKey: string;
  environment: AttestEnvironment;
  attestedAt: string;
};

/**
 * The event marker's contents — the registry record written on create.
 *
 * `createdAt` and `startsAt` are DISTINCT facts and must not be conflated: `createdAt` is server-minted
 * wall-clock at the moment the marker is written (and carries milliseconds, per `toISOString()`), while
 * `startsAt` is the host's statement of when the event BEGAN — client-supplied, canonical cutoff shape,
 * honored verbatim. `startsAt` is both the default and the FLOOR for every member's capture-date cutoff
 * (capability `photo-selection-policy`).
 *
 * Write-once: no route rewrites a stored marker. The backend has no owner field — attestation proves a
 * genuine app instance, NOT ownership of an event (a stated non-goal of `device-attestation`) — so a
 * mutation route would let anyone holding the event id retroactively widen every future joiner's scope.
 */
type EventMarker = {
  eventId: string;
  name: string;
  createdAt: string;
  startsAt: string;
};

/** A marker as it may sit in storage — one written before `startsAt` existed lacks the field. */
type StoredEventMarker = Omit<EventMarker, "startsAt"> & { startsAt?: string };

export type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

// RequestInit + the streaming-body flag required when `body` is a ReadableStream.
type StreamInit = RequestInit & { duplex?: "half" };

export type Deps = {
  /** Upstream fetch (global fetch in production; a fake in tests). */
  fetch: FetchLike;
  /** Validated storage config (built at startup via readConfig). */
  config: Config;
  /**
   * Wall clock, in epoch ms. Injected so tests can pin it — the device token and the challenge are both
   * time-bounded, and a test for "an expired token is refused" cannot wait 30 days. Defaults to `Date.now`.
   */
  now?: () => number;
};

// A single entry from bunny's native Storage "List Files" response. We read only these fields;
// everything else (Guid, ServerId, …) is ignored. `LastChanged` is the object's server-set
// last-modified time — the last-write-wins tiebreak between a device's active `<id>.json` and
// departed `<id>.left.json` manifests (see `resolveMembership`); it is a wall-clock string
// (e.g. `2026-07-06T10:30:00.000`) minted by the same storage zone, so it is comparable across
// sibling objects without any client clock.
type BunnyEntry = {
  ObjectName: string;
  Length: number;
  IsDirectory: boolean;
  LastChanged: string;
};

// One file in the per-device listing response — exactly `filename`, `size`, and `url` (a closed shape).
// `filename` is the uploaded name decoded from the stored key; `size` is the object's byte length;
// `url` is a presigned S3 GET URL (per `bunny-list-endpoint`, built by `presignDownloadUrl`).
type FileEntry = {
  filename: string;
  size: number;
  url: string;
};

// The on-storage device manifest (`device-manifest`), after the `key`/`filename` rename. We read only
// these fields; the union projects them straight through. A resource's `key` is its storage object
// name (`files/devices/<deviceId>/<key>`, the fetch handle); `filename` is the human capture name.
type ManifestResource = {
  role: string;
  contentType: string;
  key: string;
  filename: string;
};
type ManifestAsset = {
  assetId: string;
  creationDate: string;
  resources: ManifestResource[];
};
type DeviceManifest = {
  deviceId: string;
  assets: ManifestAsset[];
};

// One asset in the event-wide union: the owning `deviceId` (own-vs-foreign skip is the client's
// concern), the device-local `assetId`, the capture `creationDate`, and the complete set of resources
// — each a manifest resource plus its `size` (from the device's file listing) and presigned S3 `url`.
type UnionResource = ManifestResource & { size: number; url: string };
type UnionAsset = {
  deviceId: string;
  assetId: string;
  creationDate: string;
  resources: UnionResource[];
};

// 7 days — the S3 presign maximum. The device re-presigns (re-reads the union) on every foreground well
// within this window, so a queued background download that outlives one URL self-heals with a fresh one.
const PRESIGN_EXPIRY_SECONDS = 604800;

// The listing routes' cache header. All three directives are deliberate: the Edge Script is fronted by a
// bunny CDN pull zone, and bunny documents `no-cache` — NOT `no-store` — as the origin directive that
// suppresses its cache. `no-store` alone would rest the listings' cacheability on undocumented behavior,
// and a cached listing serves stale, expiring presigned URLs.
const NO_CACHE = "no-store, no-cache, max-age=0";

// The marketing page is PUBLIC and static — the deliberate inverse of the listings' NO_CACHE. A `public`
// directive lets the bunny pull zone serve it from the edge, keeping the Edge Script off the request hot
// path (capability `marketing-site`).
const PUBLIC_CACHE = "public, max-age=300";

/**
 * Mint an AWS SigV4 **presigned S3 GET URL** for a stored object (the download-URL authority for
 * `bunny-list-endpoint`): `https://<s3Host>/<zone>/<key>?X-Amz-…&X-Amz-Signature=…`, path-style, each
 * key segment percent-encoded (deviceId is a UUID → identity), `X-Amz-Expires` 7 days. The zone name is
 * the S3 Access Key ID and `accessKey` the secret. The device fetches this URL DIRECTLY from bunny's S3
 * endpoint with no credential — the query signature is the sole authorization. A fresh URL is minted on
 * every listing response, so each read yields one valid for a further 7 days. Both list routes use this
 * single builder, so per-device list and union agree by construction.
 */
async function presignDownloadUrl(
  aws: AwsClient,
  config: Config,
  deviceId: string,
  filename: string,
): Promise<string> {
  const url = `https://${config.s3Host}/${config.zone}/${byteKey(deviceId, filename)}` +
    `?X-Amz-Expires=${PRESIGN_EXPIRY_SECONDS}`;
  const signed = await aws.sign(url, { method: "GET", aws: { signQuery: true } });
  return signed.url;
}

/**
 * List one bunny native Storage directory (trailing slash required). Returns the parsed entries, or
 * `null` when the directory has nothing / does not exist (bunny `404`) — the caller maps `null` to
 * "no objects". Any other non-OK status, network error, or abort THROWS, so the route surfaces a
 * faithful `502` and never a partial list.
 */
async function listDir(
  fetchImpl: FetchLike,
  config: Config,
  dirPath: string,
): Promise<BunnyEntry[] | null> {
  const url = `https://${config.host}/${config.zone}/${dirPath}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (res.status === 404) return null; // empty / unknown directory
  if (!res.ok) throw new Error(`bunny LIST returned ${res.status} for ${dirPath}`);
  return await res.json() as BunnyEntry[];
}

// The stored object name is `encodeURIComponent(filename)` (see the upload handler), so decode it
// back to the filename the client uploaded — the reinstall-stable key a re-joining device reconciles
// against. Malformed escapes (never produced by our own encoder) fall back to the raw name rather
// than throw.
function decodeObjectName(objectName: string): string {
  try {
    return decodeURIComponent(objectName);
  } catch {
    return objectName;
  }
}

/**
 * Read an event's marker (the existence check). Returns the parsed marker when present (bunny `200`),
 * `null` when absent (bunny `404`), and THROWS on any other status, network error, or abort — so the
 * caller surfaces a faithful `502` and never mistakes a transient read failure for "event absent".
 * Bunny's Edge Storage API has no `HEAD`, so existence is a small `GET` of the marker; the marker is
 * tiny, and the same read serves the `GET /events/:eventId` metadata response and the device-manifest
 * write gate.
 */
async function readMarker(
  fetchImpl: FetchLike,
  config: Config,
  eventId: string,
): Promise<EventMarker | null> {
  const url = `https://${config.host}/${config.zone}/${markerKey(eventId)}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (res.status === 404) return null; // event was never created
  if (!res.ok) throw new Error(`bunny marker GET returned ${res.status} for ${eventId}`);
  const stored = await res.json() as StoredEventMarker;
  // A marker written before `startsAt` existed is patched AT READ, never rewritten (the marker is
  // write-once). Synthesizing here — the single place every marker read funnels through — is what keeps
  // `startsAt` non-null for every consumer, so no client carries a nullable start date and every
  // downstream type stays total. Such an event behaves exactly as it did before this change: the cutoff
  // is seeded from creation. NB the synthesized value inherits `createdAt`'s MILLISECONDS, so it is not
  // canonical; the app normalizes a createdAt-derived cutoff (capability `photo-selection-policy`).
  return { ...stored, startsAt: stored.startsAt ?? stored.createdAt };
}

/**
 * Read one device-manifest object by its full key (the LWW-winning `<id>.json` or `<id>.left.json`).
 * THROWS on any non-OK status, network error, abort, OR a JSON parse failure — so a faulty manifest
 * fails the whole union faithfully (`502`) rather than silently dropping a contributor. The caller only
 * invokes this for devices already discovered by the directory LIST, so the object is expected to exist
 * (a `404` here is a race and is treated as a failure, not absence).
 */
async function readManifestObject(
  fetchImpl: FetchLike,
  config: Config,
  key: string,
): Promise<DeviceManifest> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (!res.ok) {
    throw new Error(`bunny device-manifest GET returned ${res.status} for ${key}`);
  }
  return await res.json() as DeviceManifest;
}

/**
 * Read a storage object's raw body text, or `null` when absent (`404`). THROWS on any other non-OK
 * status, so the leave cascade surfaces a faithful `502` rather than losing a contribution. Used to
 * copy an active manifest into its departed sibling.
 */
async function readObjectText(
  fetchImpl: FetchLike,
  config: Config,
  key: string,
): Promise<string | null> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey },
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`bunny GET returned ${res.status} for ${key}`);
  return await res.text();
}

/** PUT a storage object's body (minting a fresh last-modified time). THROWS on any non-OK status. */
async function putObject(
  fetchImpl: FetchLike,
  config: Config,
  key: string,
  body: string,
  contentType: string,
): Promise<void> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "PUT",
    headers: { AccessKey: config.accessKey, "Content-Type": contentType },
    body,
  });
  if (!res.ok) throw new Error(`bunny PUT returned ${res.status} for ${key}`);
  await res.body?.cancel();
}

/**
 * DELETE a storage object, idempotently: a `404` (already gone) is success. THROWS on any other non-OK
 * status so a real failure surfaces as `502`. Deleting an absent object is a no-op, which keeps the
 * whole leave cascade safe to re-run under at-least-once delivery.
 */
async function deleteObject(fetchImpl: FetchLike, config: Config, key: string): Promise<void> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "DELETE",
    headers: { AccessKey: config.accessKey },
  });
  if (!res.ok && res.status !== 404) {
    throw new Error(`bunny DELETE returned ${res.status} for ${key}`);
  }
  await res.body?.cancel();
}

/**
 * Reference-count check for the leave cascade's GC: does `deviceId` still appear — as an active
 * `<id>.json` OR a departed `<id>.left.json` — under any event other than `excludeEventId` (the event
 * being reaped)? Enumerates all events with one LIST of `events/` and one LIST per surviving event's
 * `devices/` directory. Reads the storage **main** region (the deployment invariant), so a concurrent
 * rejoin's fresh manifest is visible. THROWS on any transport failure so a partial check never deletes
 * shared bytes.
 */
async function deviceAppearsInAnotherEvent(
  fetchImpl: FetchLike,
  config: Config,
  deviceId: string,
  excludeEventId: string,
): Promise<boolean> {
  const eventEntries = await listDir(fetchImpl, config, `${MARKER_PREFIX}/`);
  const eventIds = (eventEntries ?? [])
    .filter((e) => e.IsDirectory)
    .map((e) => decodeObjectName(e.ObjectName));
  for (const eventId of eventIds) {
    if (eventId === excludeEventId) continue;
    const entries = await listDir(fetchImpl, config, deviceManifestDir(eventId));
    const found = resolveMembership(entries).some((m) => m.deviceId === deviceId);
    if (found) return true;
  }
  return false;
}

/**
 * Read a device's config object (`devices/<deviceId>.json`) and return its `pushToken`, or
 * `null` when the config is absent (`404`), unreadable, unparseable, or carries no usable token. Used
 * by the notify fan-out, which is **best-effort** — a member without a registered token is simply
 * skipped, so this NEVER throws (unlike the manifest read that fails the union). The body is always
 * drained so the connection is released.
 */
async function readPushToken(
  fetchImpl: FetchLike,
  config: Config,
  deviceId: string,
): Promise<PushToken | null> {
  const url = `https://${config.host}/${config.zone}/${deviceConfigKey(deviceId)}`;
  let res: Response;
  try {
    res = await fetchImpl(url, {
      method: "GET",
      headers: { AccessKey: config.accessKey, Accept: "application/json" },
    });
  } catch {
    return null; // transport error → skip this member (best-effort)
  }
  if (!res.ok) {
    await res.body?.cancel();
    return null; // 404 (never registered) or any read error → skip
  }
  try {
    const doc = await res.json() as { pushToken?: Partial<PushToken> };
    const pt = doc.pushToken;
    if (
      pt && typeof pt.kind === "string" && typeof pt.token === "string" &&
      typeof pt.env === "string"
    ) {
      return { kind: pt.kind, token: pt.token, env: pt.env };
    }
    return null; // no / malformed pushToken → skip
  } catch {
    return null; // unparseable config → skip
  }
}

export function createApp({ fetch: fetchImpl, config, now = Date.now }: Deps): Hono {
  // The S3 signer used ONLY to presign download URLs (capability `bunny-list-endpoint`). Access Key ID =
  // the zone name, secret = the storage-zone `AccessKey`; pure Web-Crypto, no network. Uploads/reads/
  // listings stay on the native API and are not signed with this.
  const aws = new AwsClient({
    accessKeyId: config.zone,
    secretAccessKey: config.accessKey,
    region: config.s3Region,
    service: "s3",
  });

  // The APNs provider sender (capability `apns-push-sender`), memoizing its ES256 provider JWT across
  // sends. Used only by the notify fan-out. No production caller is wired to notify yet (the trigger is
  // a deferred use case); the route exists so the pipe is exercisable end-to-end.
  const apns = createApnsSender(config, fetchImpl);

  // Per-device byte WRITE route (`bunny-upload-endpoint`). Mounted under
  // `/files/devices/:deviceId/:filename`, so the handlers read `deviceId`/`filename` from the mount.
  // (Downloads are no longer proxied here — the listing hands out a presigned S3 GET URL the device
  // fetches directly from bunny's S3 endpoint.)
  const byteFile = new Hono();

  // Upload — UNGATED. No marker read: bytes are device-partitioned and event-independent. Stream the
  // body straight into one bunny native PUT at `files/devices/<deviceId>/<filename>`. Faithful: 201 only on a
  // confirmed store; last-write-wins (no existence check on the object key).
  byteFile.put("/", async (c) => {
    const deviceId = c.req.param("deviceId");
    const filename = c.req.param("filename");
    if (
      !deviceId || !filename ||
      !validateUUID(deviceId) || !validateFilename(filename)
    ) {
      return c.text("invalid key", 400);
    }

    const target = `https://${config.host}/${config.zone}/${byteKey(deviceId, filename)}`;
    const init: StreamInit = {
      method: "PUT",
      headers: {
        AccessKey: config.accessKey,
        "Content-Type": c.req.header("content-type") ?? "application/octet-stream",
      },
      body: c.req.raw.body, // ReadableStream — streamed straight through, never buffered
      duplex: "half",
    };

    let upstream: Response;
    try {
      upstream = await fetchImpl(target, init);
    } catch (e) {
      console.error(`upload: upstream PUT errored for ${byteKey(deviceId, filename)}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (!upstream.ok) {
      console.error(`upload: bunny returned ${upstream.status} for ${byteKey(deviceId, filename)}`);
      return c.text("upstream rejected", 502);
    }
    // Bunny confirmed the stored object — and only now do we report success.
    return c.body(null, 201);
  });

  // OPTIONS: do NOT advertise resumable uploads → the iOS uploader falls back to a plain PUT.
  byteFile.options("/", (c) => {
    c.header("Allow", "PUT, OPTIONS");
    return c.body(null, 204);
  });

  const app = new Hono();

  // ── THE GATE (capability `device-attestation`) ──────────────────────────────────────────────────
  //
  // Every route requires a device token, obtainable ONLY by completing App Attest — so the API is
  // callable by a genuine, unmodified SnapSync on a genuine Apple device, and by nothing else. What this
  // closes is bill/storage abuse: the byte route reads no marker, the device id is self-asserted, and the
  // host ships in plaintext in every IPA, so before this an unbounded write to the zone was available to
  // anyone who read the binary.
  //
  // Registered FIRST, as one middleware, which gives three properties for free:
  //   * it runs BEFORE every event-existence gate, so an unauthenticated caller cannot even probe which
  //     events exist (a 404-vs-401 difference would leak that);
  //   * the ungated set is a CLOSED LIST in one readable place, so a future route cannot land ungated by
  //     omission — it has to be added here deliberately;
  //   * verification costs one HMAC comparison — no storage read, no Apple call — so the streaming
  //     photo-upload hot path pays nothing for it.
  //
  // The exceptions, exhaustively:
  //   * `/attest/*` — the three routes that ISSUE the token cannot require the token they issue. Each is
  //     self-authenticating: the challenge is HMAC-signed and stateless, and token/renew carry an
  //     attestation or an assertion that is verified before anything is minted.
  //   * `OPTIONS` — the pull zone is free to answer the preflight ITSELF (it has been observed doing so),
  //     so the script cannot gate it even if it wanted to; and a 401 here would break the plain-PUT
  //     fallback the iOS uploader depends on.
  app.use("*", async (c, next) => {
    const path = new URL(c.req.url).pathname;
    const method = c.req.method;
    // Ungated (closed list): OPTIONS, the `/attest/*` token issuers, the public marketing page at
    // EXACTLY `/` (capability `marketing-site`), and the event link's two public routes (capability
    // `event-link`) — the AASA, which Apple's CDN and the device fetch with no Authorization header and
    // cannot be made to send one, and `/join`, whose entire audience is people who have no app and so no
    // attestation. Every exception is exact-path and GET/HEAD-only — never a prefix, never a mutating
    // method — so no gated route can be reached through one. All three read no storage and carry no side
    // effect, so serving them unauthenticated grows neither the bill nor the storage this gate protects.
    const publicGet = path === "/" || path === "/join" ||
      path === "/.well-known/apple-app-site-association";
    if (
      method === "OPTIONS" ||
      path.startsWith("/attest/") ||
      ((method === "GET" || method === "HEAD") && publicGet)
    ) {
      return await next();
    }

    const auth = c.req.header("authorization") ?? "";
    const token = auth.startsWith("Bearer ") ? auth.slice("Bearer ".length).trim() : "";
    if (!token || !await verifyToken(config, token, now())) {
      return c.text("unattested", 401);
    }
    return await next();
  });

  // The public marketing/landing page (capability `marketing-site`): a single source-owned static page,
  // served from memory with no storage or Apple call. Cacheable (PUBLIC_CACHE) so the pull zone answers
  // from the edge. GET returns the page; HEAD returns the same headers with no body. The gate above admits
  // both at exactly `/`.
  app.on(["GET", "HEAD"], "/", (c) => {
    c.header("Cache-Control", PUBLIC_CACHE);
    c.header("Content-Type", "text/html; charset=utf-8");
    return c.req.method === "HEAD" ? c.body(null) : c.body(LANDING_HTML);
  });

  // The Apple App Site Association document (capability `event-link`): what makes the event link a
  // Universal Link instead of a web page. Apple's CDN and the device fetch it unauthenticated, so the
  // gate above admits it; it MUST be served as application/json with NO redirect.
  //
  // `appIDs` is `config.attestAppId` — the same `<teamId>.<bundleId>` the attestation gate gnaws on —
  // derived, never restated, so the AASA cannot drift from the app it names. The extension is absent
  // deliberately: it never handles URLs.
  //
  // The path is matched with `components` on `/join` ALONE — no query, no fragment constraint. That is
  // deliberate on two counts. A malformed link then still opens the app and surfaces the invalid-link
  // error rather than dead-ending invisibly in a browser (a visible failure beats a silent one). And it
  // sidesteps the documented iOS bug where a `?` nested inside a `#` cannot be matched — we ask for
  // neither. Narrow matching also keeps `/`, `/events/:id`, and `/attest/*` opening in a browser; a
  // broad `/*` would hijack our own marketing page into the app.
  const aasa = JSON.stringify({
    applinks: { details: [{ appIDs: [config.attestAppId], components: [{ "/": "/join" }] }] },
  });
  app.on(["GET", "HEAD"], "/.well-known/apple-app-site-association", (c) => {
    c.header("Cache-Control", PUBLIC_CACHE);
    c.header("Content-Type", "application/json");
    return c.req.method === "HEAD" ? c.body(null) : c.body(aasa);
  });

  // The App Store fallback (capability `event-link`): the path a browser requests when an event link is
  // opened on a device with no app to claim it. Identical for every link and reads nothing.
  //
  // It does not — cannot — read the payload: that rides in the URL fragment, which a browser never
  // transmits, so this handler sees `/join` and nothing more. That is the point, not a limitation: the
  // eventId IS the upload capability, and keeping it out of the fragment-blind server path keeps it out
  // of the edge's logs and cache keys too. It is also why there is no per-event landing page to render.
  //
  // iOS does NO deferred deep linking: someone who installs from here reaches their event by opening the
  // original link again.
  app.on(["GET", "HEAD"], "/join", (c) => c.redirect(config.appStoreUrl, 302));

  // Issue a challenge. Stateless and self-authenticating (an HMAC over its own expiry), so this writes
  // NOTHING — the one route a stranger can call cannot grow the bill this gate exists to protect.
  app.get("/attest/challenge", async (c) => {
    c.header("Cache-Control", NO_CACHE);
    return c.json({ challenge: await mintChallenge(config, now()) });
  });

  // Attest: verify the attestation object, persist the attested public key, mint a token.
  app.post("/attest/token", async (c) => {
    let body: { deviceId?: string; keyId?: string; attestation?: string; challenge?: string };
    try {
      body = await c.req.json();
    } catch {
      return c.text("invalid body", 400);
    }
    const { deviceId, keyId, attestation, challenge } = body;
    if (!deviceId || !validateUUID(deviceId) || !keyId || !attestation || !challenge) {
      return c.text("invalid body", 400);
    }
    if (!await challengeIsValid(config, challenge, now())) return c.text("stale challenge", 401);

    let verified;
    try {
      verified = await verifyAttestation(config, {
        attestation: b64ToBytes(attestation),
        challenge,
        keyId: b64ToBytes(keyId),
        at: new Date(now()),
      });
    } catch (e) {
      console.error(`attest: attestation rejected for ${deviceId}: ${e}`);
      return c.text("attestation rejected", 401);
    }

    // Persist the attested key so RENEWAL can verify a cheap local assertion against it instead of
    // forcing a fresh attestation — which is the throttled path, and which would make renewal too
    // expensive to attempt at every wake.
    const record: AttestRecord = {
      publicKey: bytesToB64(verified.publicKey),
      environment: verified.environment,
      attestedAt: new Date(now()).toISOString(),
    };
    try {
      await putObject(
        fetchImpl,
        config,
        deviceAttestKey(deviceId),
        JSON.stringify(record),
        "application/json",
      );
    } catch (e) {
      console.error(`attest: could not persist the attestation record for ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }

    console.info(`attest: ${deviceId} attested (${verified.environment})`);
    return c.json({ token: await mintToken(config, deviceId, now()) }, 201);
  });

  // Renew: verify an assertion against the stored key, mint a fresh token. No Apple round-trip, so this
  // is cheap enough for the app to attempt at EVERY wake rather than in a narrow window near expiry.
  app.post("/attest/renew", async (c) => {
    let body: { deviceId?: string; assertion?: string; challenge?: string };
    try {
      body = await c.req.json();
    } catch {
      return c.text("invalid body", 400);
    }
    const { deviceId, assertion, challenge } = body;
    if (!deviceId || !validateUUID(deviceId) || !assertion || !challenge) {
      return c.text("invalid body", 400);
    }
    if (!await challengeIsValid(config, challenge, now())) return c.text("stale challenge", 401);

    let record: AttestRecord;
    try {
      const raw = await readObjectText(fetchImpl, config, deviceAttestKey(deviceId));
      if (raw === null) return c.text("not attested", 401); // never attested, or GC'd → attest afresh
      record = JSON.parse(raw) as AttestRecord;
    } catch (e) {
      console.error(`renew: could not read the attestation record for ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }

    try {
      await verifyAssertion({
        assertion: b64ToBytes(assertion),
        challenge,
        publicKey: b64ToBytes(record.publicKey),
        appId: config.attestAppId,
      });
    } catch (e) {
      console.error(`renew: assertion rejected for ${deviceId}: ${e}`);
      return c.text("assertion rejected", 401);
    }

    return c.json({ token: await mintToken(config, deviceId, now()) }, 201);
  });

  // Create an event (capability `event-creation`). GATED by the device token above (an ungated create
  // let a stranger mint unbounded event markers). Beyond that gate it stays possession-is-
  // capability model). Validates the name, mints a server-side UUID, and writes the marker. Faithful
  // outcome: 201 only after bunny confirms the marker store; any upstream failure → 502.
  app.post("/events", async (c) => {
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.text("invalid body", 400); // not JSON
    }
    const name = validateEventName((body as { name?: unknown } | null)?.name);
    if (name === null) {
      return c.text("invalid name", 400); // missing/empty/whitespace/too long
    }
    const startsAt = validateStartsAt((body as { startsAt?: unknown } | null)?.startsAt);
    if (startsAt === null) {
      return c.text("invalid startsAt", 400); // missing/empty/non-canonical/not a real instant
    }
    // The server is the source of truth for existence, so it mints the id; any client-supplied id is
    // ignored (we only read `name` and `startsAt` above).
    const marker: EventMarker = {
      eventId: crypto.randomUUID(),
      name,
      createdAt: new Date().toISOString(),
      startsAt,
    };

    const target = `https://${config.host}/${config.zone}/${markerKey(marker.eventId)}`;
    let upstream: Response;
    try {
      upstream = await fetchImpl(target, {
        method: "PUT",
        headers: { AccessKey: config.accessKey, "Content-Type": "application/json" },
        body: JSON.stringify(marker),
      });
    } catch (e) {
      console.error(`create: marker PUT errored for ${marker.eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (!upstream.ok) {
      console.error(`create: bunny returned ${upstream.status} for marker ${marker.eventId}`);
      return c.text("upstream rejected", 502);
    }
    // Bunny confirmed the marker — only now is the event created.
    return c.json(marker, 201);
  });

  // Event metadata / existence (capability `event-creation`). Returns the marker, or 404 when the
  // event was never created; a non-404 marker read failure → 502. This is the canonical existence
  // check the device-manifest write gate relies on.
  app.get("/events/:eventId", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }
    let marker: EventMarker | null;
    try {
      marker = await readMarker(fetchImpl, config, eventId);
    } catch (e) {
      console.error(`metadata: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (marker === null) return c.text("event not found", 404);
    return c.json(marker);
  });

  // Write a device's per-event manifest (capability `bunny-upload-endpoint`, device-manifest route).
  // GATED on event existence: read the marker first; absent → 404 (no upstream object PUT); a non-404
  // read failure → 502 (never mistaken for absence). The body (a full-state JSON device manifest) is
  // streamed straight into one bunny native PUT at `events/<eventId>/devices/<deviceId>.json`.
  app.put("/events/:eventId/devices/:deviceId", async (c) => {
    const eventId = c.req.param("eventId");
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(eventId) || !validateUUID(deviceId)) {
      return c.text("invalid key", 400);
    }

    let marker: EventMarker | null;
    try {
      marker = await readMarker(fetchImpl, config, eventId);
    } catch (e) {
      console.error(`device-manifest: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (marker === null) return c.text("event not found", 404);

    const target = `https://${config.host}/${config.zone}/${deviceManifestKey(eventId, deviceId)}`;
    const init: StreamInit = {
      method: "PUT",
      headers: {
        AccessKey: config.accessKey,
        "Content-Type": c.req.header("content-type") ?? "application/json",
      },
      body: c.req.raw.body, // ReadableStream — the full-state manifest, streamed never buffered
      duplex: "half",
    };

    let upstream: Response;
    try {
      upstream = await fetchImpl(target, init);
    } catch (e) {
      console.error(`device-manifest: upstream PUT errored for ${eventId}/${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (!upstream.ok) {
      console.error(
        `device-manifest: bunny returned ${upstream.status} for ${eventId}/${deviceId}`,
      );
      return c.text("upstream rejected", 502);
    }
    return c.body(null, 201);
  });

  // Leave an event (capability `event-leave-endpoint`). The device notifies the backend it is leaving.
  // GATED on the marker (absent → 404; non-404 read failure → 502). Cascade: (1) rename the device's
  // active manifest to its `.left.json` sibling (copy content → FRESH timestamp, then delete the
  // active) so the union still serves its photos; (2) if no ACTIVE member remains (last-write-wins over
  // the devices/ listing) reap the whole `events/<eventId>/` tree; (3) for each freed device that
  // appears in no surviving event, GC its `files/devices/<id>/` byte partition and `devices/<id>.json`
  // config. Idempotent + leak-safe: the `.left.json` is written BEFORE the active is deleted, and every
  // delete of an absent object is a no-op, so a duplicate/retried DELETE re-runs harmlessly. Any
  // transport failure anywhere in the cascade → 502.
  app.delete("/events/:eventId/devices/:deviceId", async (c) => {
    const eventId = c.req.param("eventId");
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(eventId) || !validateUUID(deviceId)) {
      return c.text("invalid key", 400);
    }

    let marker: EventMarker | null;
    try {
      marker = await readMarker(fetchImpl, config, eventId);
    } catch (e) {
      console.error(`leave: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (marker === null) return c.text("event not found", 404);

    try {
      // (1) Departed rename. Write the `.left.json` sibling FIRST (fresh timestamp = the commit), then
      // delete the active. A missing active manifest (already departed / never a member) → no-op.
      const activeKey = deviceManifestKey(eventId, deviceId);
      const activeBody = await readObjectText(fetchImpl, config, activeKey);
      if (activeBody !== null) {
        await putObject(
          fetchImpl,
          config,
          deviceLeftManifestKey(eventId, deviceId),
          activeBody,
          "application/json",
        );
        await deleteObject(fetchImpl, config, activeKey);
      }

      // (2) Reap check: re-LIST devices/ (now reflecting the rename); any active member remaining?
      const entries = await listDir(fetchImpl, config, deviceManifestDir(eventId));
      const members = resolveMembership(entries);
      if (members.some((m) => m.state === "active")) {
        return c.body(null, 200); // an active member remains — keep the event
      }

      // No active member → reap the event tree: every manifest under devices/, then the marker.
      for (const e of (entries ?? []).filter((e) => !e.IsDirectory)) {
        await deleteObject(fetchImpl, config, `${deviceManifestDir(eventId)}${e.ObjectName}`);
      }
      await deleteObject(fetchImpl, config, markerKey(eventId));

      // (3) Reference-checked GC: delete a freed device's bytes + config only if it appears in no
      // surviving event (shared bytes another event still references are retained).
      for (const { deviceId: freedId } of members) {
        if (await deviceAppearsInAnotherEvent(fetchImpl, config, freedId, eventId)) continue;
        const files = await listDir(fetchImpl, config, deviceDir(freedId));
        for (const f of (files ?? []).filter((e) => !e.IsDirectory)) {
          await deleteObject(fetchImpl, config, `${deviceDir(freedId)}${f.ObjectName}`);
        }
        await deleteObject(fetchImpl, config, deviceConfigKey(freedId));
        // …and its attestation record (capability `device-attestation`). Per-device state keyed by a
        // device that now participates in nothing: leaving it behind leaks one object per departed
        // device, forever. Safe to drop — a device that returns simply attests again, which writes a
        // fresh record.
        await deleteObject(fetchImpl, config, deviceAttestKey(freedId));
      }
      return c.body(null, 200);
    } catch (e) {
      console.error(`leave: cascade failed for ${eventId}/${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Event-wide UNION read (capability `bunny-list-endpoint`). GATED on event existence (marker read):
  // absent → 404, non-404 read failure → 502. Then fan out: one LIST of `events/<eventId>/devices/` to
  // discover the contributing devices, and per device (in parallel) read its `device.json` and LIST
  // its `files/devices/<deviceId>/` partition. An asset is emitted only when EVERY resource its manifest names
  // is present in that device's byte store (complete-only); each kept asset is flattened into one
  // array, tagged with its owning deviceId (the endpoint is identity-blind — own-vs-foreign skip is
  // the client's concern). The stored manifest is already the event's date-filtered projection, so its
  // asset list is trusted as-is (no re-filtering). Faithful: any non-404 read failure anywhere in the
  // fan-out (incl. a manifest JSON parse failure) → 502, never a partial union; a per-device file dir
  // 404 is "no bytes" (every asset incomplete), not a failure. The 200 response is non-cacheable.
  app.get("/events/:eventId/files", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }

    // Gate on the marker (existence): absent → 404; a non-404 read failure → 502.
    let marker: EventMarker | null;
    try {
      marker = await readMarker(fetchImpl, config, eventId);
    } catch (e) {
      console.error(`union: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (marker === null) return c.text("event not found", 404);

    try {
      // Enumerate contributing devices with a single LIST of the device-manifest dir. A 404/empty dir
      // means no contributors → an empty union. BOTH active (`<id>.json`) and departed (`<id>.left.json`)
      // devices contribute — a departed device's already-shared photos stay downloadable until the event
      // is reaped — but a device is counted once via last-write-wins (see `resolveMembership`).
      const manifestEntries = await listDir(fetchImpl, config, deviceManifestDir(eventId));
      const members = resolveMembership(manifestEntries);

      // Per-device fan-out (parallel): read the LWW-winning manifest AND list the device's byte
      // partition, then keep only complete assets. Any rejection here is caught below → 502 (never a
      // partial union).
      const perDevice = await Promise.all(
        members.map(async ({ deviceId, state }): Promise<UnionAsset[]> => {
          const manifestKey = state === "departed"
            ? deviceLeftManifestKey(eventId, deviceId)
            : deviceManifestKey(eventId, deviceId);
          const [manifest, fileEntries] = await Promise.all([
            readManifestObject(fetchImpl, config, manifestKey),
            listDir(fetchImpl, config, deviceDir(deviceId)), // 404 → null → no bytes present
          ]);

          // The device's present object names → byte length (the completeness oracle + size source).
          const present = new Map<string, number>();
          for (const e of (fileEntries ?? []).filter((e) => !e.IsDirectory)) {
            present.set(decodeObjectName(e.ObjectName), e.Length);
          }

          const complete = (manifest.assets ?? [])
            .filter((a) => a.resources.length > 0 && a.resources.every((r) => present.has(r.key)));
          return await Promise.all(complete.map(async (a): Promise<UnionAsset> => ({
            deviceId,
            assetId: a.assetId,
            creationDate: a.creationDate,
            resources: await Promise.all(a.resources.map(async (r) => ({
              role: r.role,
              contentType: r.contentType,
              key: r.key,
              filename: r.filename,
              size: present.get(r.key)!,
              url: await presignDownloadUrl(aws, config, deviceId, r.key),
            }))),
          })));
        }),
      );

      c.header("Cache-Control", NO_CACHE); // live read over mutable manifests + listings
      return c.json(perDevice.flat());
    } catch (e) {
      console.error(`union: assembly failed for event ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // List a device's RAW stored objects (capability `bunny-list-endpoint`). A single LIST of the device
  // dir; each direct-child object becomes one `{ filename, size, url }`. No manifest read, no
  // completeness, no event gate — the app computes completeness from the gallery enumeration seam ×
  // this list. A non-UUID id → 400; any other method / unmatched path → Hono's 404.
  app.get("/files/devices/:deviceId", async (c) => {
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(deviceId)) {
      return c.text("invalid device", 400);
    }
    try {
      // Single LIST of the device dir → its objects. 404/absent → no objects → []. Any other LIST
      // failure throws → 502, so a partial list is never returned.
      const entries = await listDir(fetchImpl, config, deviceDir(deviceId));
      c.header("Cache-Control", NO_CACHE); // each `url` is a time-limited presigned S3 URL
      if (entries === null) return c.json([] as FileEntry[]);

      const files: FileEntry[] = await Promise.all(
        entries
          .filter((e) => !e.IsDirectory)
          .map(async (e) => {
            const filename = decodeObjectName(e.ObjectName);
            return {
              filename,
              size: e.Length,
              url: await presignDownloadUrl(aws, config, deviceId, filename),
            };
          }),
      );
      return c.json(files);
    } catch (e) {
      console.error(`list: bunny LIST failed for device ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Write a device's config object (capability `device-config-endpoint`). Gated by DEVICE-ID
  // possession alone (no marker, no event) — the same capability model as the byte upload. Streams the
  // JSON body into one bunny native PUT at `devices/<deviceId>.json`. Faithful: 201 only on a
  // confirmed store; last-write-wins (a rotated token overwrites). The config is a flat sibling OUTSIDE
  // the `files/devices/<deviceId>/` byte partition, so it never appears in the per-device list or the union.
  app.put("/devices/:deviceId", async (c) => {
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(deviceId)) {
      return c.text("invalid device", 400);
    }
    const target = `https://${config.host}/${config.zone}/${deviceConfigKey(deviceId)}`;
    const init: StreamInit = {
      method: "PUT",
      headers: {
        AccessKey: config.accessKey,
        "Content-Type": c.req.header("content-type") ?? "application/json",
      },
      body: c.req.raw.body, // ReadableStream — the config doc, streamed never buffered
      duplex: "half",
    };
    let upstream: Response;
    try {
      upstream = await fetchImpl(target, init);
    } catch (e) {
      console.error(`config: upstream PUT errored for ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (!upstream.ok) {
      console.error(`config: bunny returned ${upstream.status} for ${deviceId}`);
      return c.text("upstream rejected", 502);
    }
    return c.body(null, 201);
  });

  // Notify an event's members (capability `event-notify-endpoint`). GATED on the marker (absent → 404,
  // non-404 read failure → 502). Enumerate members with one LIST of `events/<eventId>/devices/`; a LIST
  // transport failure → 502 (nothing enumerable). Then BEST-EFFORT: read each member's config token
  // (absent/unparseable/no-token → skipped) and send a silent (content-available) push carrying the
  // route's `eventId` in its payload to the rest. Per-member read/send failures never fail the request
  // — always a bare 202 once the marker gate passed and members were enumerated. Server-chosen payload
  // (the path event id), all members, no exclusion; the uploader fires this via `upload-completion-notify`.
  app.post("/events/:eventId/notify", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }

    let marker: EventMarker | null;
    try {
      marker = await readMarker(fetchImpl, config, eventId);
    } catch (e) {
      console.error(`notify: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (marker === null) return c.text("event not found", 404);

    // Enumerate ACTIVE members only: a departed device (`<id>.left.json` winning by last-write-wins)
    // has left the event and is not notified.
    let memberIds: string[];
    try {
      const entries = await listDir(fetchImpl, config, deviceManifestDir(eventId));
      memberIds = resolveMembership(entries)
        .filter((m) => m.state === "active")
        .map((m) => m.deviceId);
    } catch (e) {
      console.error(`notify: member LIST failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

    // Best-effort per-member token read (skips members without a registered token), then fan out.
    const tokens = (await Promise.all(memberIds.map((d) => readPushToken(fetchImpl, config, d))))
      .filter((t): t is PushToken => t !== null);
    const outcomes = await apns.sendSilent(tokens, eventId);
    const sent = outcomes.filter((o) => o.status === "sent").length;
    console.info(
      `notify: event ${eventId} — ${memberIds.length} members, ${tokens.length} with a token, ${sent} pushed`,
    );

    return c.body(null, 202);
  });

  // Mount the per-device byte object routes; any unmatched path or wrong method → Hono's 404.
  app.route("/files/devices/:deviceId/:filename", byteFile);
  return app;
}
