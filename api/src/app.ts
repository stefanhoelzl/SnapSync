// Hono app for the backend: the whole device API in one place (capabilities `api-endpoints` for the route
// shapes, `database` for what each one reads and writes, `device-attestation` for the gate, plus
// `event-limits`, `event-rename`, `leave-event`, `apns-push-sender`, `web-site` and `event-link`, over the
// shared `backend-deployment`).
//
// VERSIONED PREFIX (capability `backend-deployment`): every device-API route below is served under the
// prefix `/api/v1` — the paths are written that way here, and that is the one shape they answer at. The
// web/link routes (`/`, `/join`, the AASA) stay at the ROOT only, never under `/api/v1`. The routing is
// version-parametric: a future `/api/v2` is one more mount in `createApp`.
//
// ── WHERE STATE LIVES ─────────────────────────────────────────────────────────────────────────────
//
// THE DATABASE HOLDS THE FACTS; STORAGE HOLDS THE BYTES. An event exists iff its `events` row exists; a
// membership is a row with a state, not a pair of objects whose timestamps are compared; the union is one
// join. This module therefore reaches storage for exactly one thing — the photo bytes — and everything
// else is a statement in `db.ts` (capability `database`).
//
// A `devices` ROW EXISTS IFF THAT DEVICE HAS ATTESTED. That is forced by the gate rather than chosen:
// every route but `/attest/*` needs a token, and a token needs an attestation, so no device can reach any
// other device-scoped write first. It is why the push registration UPDATEs and never inserts.
//
// ── THE GATE (capability `device-attestation`) ────────────────────────────────────────────────────
//
// EVERY ROUTE REQUIRES A DEVICE TOKEN — obtainable only by completing App Attest, so the API is callable
// by a genuine, unmodified SnapSync on a genuine Apple device and by nothing else. The exceptions are a
// CLOSED LIST, and each is exempt for its own stated reason (see the middleware in `createApp`):
//
//   * the three `/attest/*` issuers — self-authenticating; they cannot require the token they mint.
//   * `OPTIONS` on any path — the pull zone may answer the preflight itself, so the script cannot gate it.
//   * `GET`/`HEAD` on exactly `/`, `/join` and the AASA — static, source-owned, read no storage.
//   * `GET`/`HEAD` on `/health` — the post-deploy probe runs before any credential exists.
//   * `GET`/`HEAD` on `/api/v1/events/<id>` and `/api/v1/events/<id>/files` — the no-app download page
//     holds no attestation, and possession of the eventId IS the read capability. Every non-GET method on
//     those paths stays gated.
//
// VERIFYING A TOKEN TOUCHES NOTHING: one HMAC comparison, no read, no Apple call. That is load-bearing
// rather than an optimisation — verification runs on the streaming byte-upload path, where a round-trip
// per resource would be paid on every photo. A route that additionally needs the device's RECORD reads it
// itself, after the gate has passed; the gate never does.
//
//   GET /api/v1/attest/challenge
//     → a stateless, HMAC-signed, time-bounded nonce. Writes NOTHING.
//   POST /api/v1/attest/token
//     → verifies an App Attest attestation (chain → Apple's root, nonce, app-id hash, counter, aaguid),
//       records the attested key AND the minted token's expiry as the device's row, then mints a 30-day
//       bearer token. That record IS the device's enrolment. PERSISTS BEFORE MINTING: a token handed out
//       against a record we failed to write is a credential nothing knows about → 502, mint nothing.
//   POST /api/v1/attest/renew
//     → verifies a local Secure-Enclave ASSERTION against that stored key — no Apple round-trip, because
//       re-attestation is the throttled path — advances the recorded expiry, THEN mints. 401 when no
//       record is on file (attest afresh); 502 when the store cannot be read or written, because absence
//       and "could not ask" have different remedies and must not collapse.
//
//   POST /api/v1/events
//     → mints an event: INSERTs the `events` row, stamping `capacity` and the `lifetimeSeconds` DURATION
//       and validating the creator's `endsAt` against the configured window maximum (capability
//       `event-limits`); returns {eventId,name,createdAt,startsAt,endsAt,capacity,deletesAt}.
//   GET /api/v1/events/:eventId
//     → the event row with the DERIVED `deletesAt`; 404 when absent. Never deletes on touch, even past
//       the deadline. UNGATED (GET/HEAD only).
//   PATCH /api/v1/events/:eventId
//     → renames (capability `event-rename`): the ONLY write to an existing event row, and it sets `name`
//       ALONE — every other column is write-once, which is why the statement is spelled out in `db.ts`
//       rather than composed. No ownership check (there is no owner); the token gate is the whole
//       authorization.
//   PUT /api/v1/devices/:deviceId
//     → the push registration (capability `push-registration`): UPDATEs the device's push columns and
//       NEVER inserts. 401 when it affects no row — the token verified, but we hold no attestation for
//       this device. The shipped client recovers unaided: the 401 drops its token, it attests (which
//       creates the row), and re-sends the registration when the new credential arrives. A 201 here would
//       be a silent absence — the device would believe it is reachable while no push could reach it.
//   POST /api/v1/events/:eventId/notify
//     → a fixed SILENT (content-available) push to every ACTIVE member (departed members are skipped).
//       GATED on the event row (404/502). Members come from one query, each member's token from its row;
//       the fan-out is best-effort — a member with no registered token is skipped and a per-token failure
//       never fails the request. Bare 202; 502 only if the member read fails.
//   PUT /api/v1/files/devices/:deviceId/:filename
//     → streams the request body into ONE bunny native Storage PUT, then BEST-EFFORT records the resource
//       row as uploaded (a failure there never changes the response — the response is the storage
//       outcome). Requires the token but reads NO event: bytes are device-partitioned and
//       event-independent, uploaded once and linked into events by reference. The device id remains
//       self-asserted — the token proves a genuine app instance, NOT ownership of the partition (a stated
//       non-goal; the UUID is the capability). The OS performs this PUT and DOES carry the header
//       (verified on device). There is no download GET here; the listing hands out a presigned S3 URL.
//   GET /api/v1/files/devices/:deviceId
//     → the device's uploaded resources, from ONE query — no storage LIST. Each entry is
//       `{ filename, url }`, where `filename` is the STORED OBJECT KEY (what the rejoin reconciler matches
//       its ledger against, capability `event-rejoin-reconciliation`) and `url` is a presigned S3 GET.
//       `Cache-Control: no-store, no-cache, max-age=0` (time-limited urls; see NO_CACHE — the pull zone
//       honors `no-cache`, not `no-store`).
//   PUT /api/v1/events/:eventId/devices/:deviceId
//     → publishes the device manifest. GATED on existence AND on CAPACITY by ONE conditional statement
//       (capability `event-limits`): a device never enrolled is refused 409 once `capacity` distinct ids
//       have ever enrolled — leaving frees no slot, a rejoin reuses its own — and a zero-row outcome is
//       disambiguated into 409-vs-404 by a follow-up read rather than collapsed. Capacity is the ONLY
//       refusal; enrollment is never closed by time, however long after `endsAt` it arrives. The write is
//       ONE ATOMIC BATCH: membership → active, the membership's assets REPLACED with exactly what the body
//       lists (an omitted asset is removed), each named resource upserted with `uploaded` MONOTONE.
//   DELETE /api/v1/events/:eventId/devices/:deviceId
//     → LEAVE (capability `leave-event`): marks the membership `departed`. GATED on the event row
//       (404/502), idempotent, and NON-DESTRUCTIVE — the assets are RETAINED, so the union keeps serving
//       what the device shared. No reap here and no leave-time GC. When this was the last active member
//       the event becomes EMPTY and the nightly sweep reclaims it on its next run.
//   GET /api/v1/events/:eventId/files
//     → the event-wide UNION, as ONE query joining the event's assets to their resources across ACTIVE
//       AND DEPARTED memberships (a member who left keeps contributing what it already shared). An asset
//       naming a resource with no recorded upload is dropped — defense in depth, since the manifest lists
//       only completed resources. Faithful: any read failure → 502, never a partial union. UNGATED
//       (GET/HEAD only). Identity-blind: own-vs-foreign skip is the client's concern.
//       `Cache-Control: no-store, no-cache, max-age=0`.
//   GET /health
//     → the post-deploy boot probe (capability `deployment-configuration`): this bundle's stamped SHA and
//       the store's foreign-key posture, reported SEPARATELY so a misprovisioned store is distinguishable
//       from one that is merely still starting.
//
// ── EVENT LIFECYCLE (capability `event-limits`) ───────────────────────────────────────────────────
//
// Every event-scoped route resolves its event through ONE gate (`gateEvent`), and the lifecycle is
// BINARY — the event exists, or the sweep has deleted it. `endsAt` is NOT a lifecycle input: it bounds
// only which captures may be UPLOADED, so nothing closes when the window does (in particular, JOINING IS
// NEVER CLOSED BY TIME — a guest who scans days late still holds in-window captures that belong in the
// event).
//
// The nightly sweep (capability `scheduled-cleanup`, run out-of-edge from GitHub Actions) is the ONLY
// deleter. It reclaims an event past its derived delete-by (`max(createdAt, startsAt) + lifetimeSeconds`
// — the guarantee) or EMPTY (ever joined, no active member left — opportunistic, since a leave whose
// DELETE never landed keeps a membership active). No route reaps on touch, even past the deadline: that
// is what makes a 404 a REAL deletion, and therefore safe as one of the two witnesses the client's
// self-leave requires (capability `leave-event`).
//
// The token check ALWAYS runs before the existence gate, so an unauthenticated caller cannot tell an
// existing event from a missing one — except on the two routes the closed list deliberately opens, where
// eventId-possession is itself the read capability.
//
// ── THE BYTE ROUTE ────────────────────────────────────────────────────────────────────────────────
//
// The per-device byte WRITE route is defined on a child Hono (`byteFile`) and mounted under
// `/files/devices/:deviceId/:filename` via app.route(), so PUT (upload) and OPTIONS share it.
// `deviceId`/`filename` are Hono's decoded path params (typed `string | undefined` through a mount, hence
// the guard); the filename is re-encoded per-segment when building the bunny URL, so the stored object is
// the real filename and keys stay flat. Config is injected (validated at startup). Upload invariants:
// pass-through only (never buffer/hash), faithful outcome (2xx only on confirmed store), last-write-wins.
// There is NO download route: the listing's `url` is a presigned S3 GET the device fetches directly from
// bunny's S3 endpoint (the short-read integrity check moves to the client).

import { Hono } from "hono";
import { AwsClient } from "aws4fetch";
import {
  canonicalFromMs,
  canonicalPlusSeconds,
  validateEndsAt,
  validateEventName,
  validateFilename,
  validateStartsAt,
  validateUUID,
} from "./validators.ts";
import { BUILD_SHA, type Config } from "./config.ts";
import { createApnsSender, type PushToken } from "./apns.ts";
import {
  b64ToBytes,
  bytesToB64,
  challengeIsValid,
  mintChallenge,
  mintToken,
  tokenExpiryIso,
  verifyAssertion,
  verifyAttestation,
  verifyToken,
} from "./attest.ts";
// Storage primitives — now ONLY the byte store. The attestation record was the last non-byte object this
// script touched, and it is a row now (capability `database`): storage holds bytes, the database holds
// facts.
import { byteKey, type FetchLike, storageReachable } from "./storage.ts";
// The relational store (capability `database`) — the authority for events, memberships, assets,
// resources and device records, shared with the nightly sweep.
import {
  type Db,
  departMembership,
  deviceFiles,
  enroll,
  type EventRow,
  insertEvent,
  type ManifestAssetEntry,
  markUploaded,
  membersOf,
  publishStatements,
  putAttestation,
  putDeviceRecord,
  readAttestation,
  readDeviceRecord,
  readEvent,
  renameEvent,
  touchTokenExpiry,
  unionRows,
} from "./db.ts";
// Event lifecycle, shared with the nightly sweep.
import { deleteByMs } from "./lifecycle.ts";

// Re-exported so existing importers (tests, callers) keep their `from "./app.ts"` imports working.
export type { FetchLike } from "./storage.ts";

// The browser-facing pages (capabilities `marketing-site` at `/` and `web-event-download` at `/join`) are
// no longer embedded here — they are built by the `site/` Astro module and served by proxying the storage
// `site/` prefix (capability `web-site`, see `serveSiteObject` + the `/`, `/join`, and `/_astro/*` routes
// below). The `shots` pipeline that inlined the landing screenshots is gone with them.

// RequestInit + the streaming-body flag required when `body` is a ReadableStream.
type StreamInit = RequestInit & { duplex?: "half" };

export type Deps = {
  /** Upstream fetch (global fetch in production; a fake in tests). */
  fetch: FetchLike;
  /** Validated storage config (built at startup via readConfig). */
  config: Config;
  /**
   * The relational store (capability `database`). Injected like {@link fetch}: production passes the
   * libSQL driver built in `main.ts`, tests pass an in-process `node:sqlite` one. The port is narrow
   * enough that both are the same few methods, and neither can be mistaken for the other at a call site.
   */
  db: Db;
  /**
   * Wall clock, in epoch ms. Injected so tests can pin it — the device token and the challenge are both
   * time-bounded, and a test for "an expired token is refused" cannot wait 30 days. Defaults to `Date.now`.
   */
  now?: () => number;
  /**
   * The commit this bundle was built from, served by `GET /health` so the post-deploy probe can tell THIS
   * bundle from the previous one still being served (capability `backend-deployment`).
   *
   * A DEPENDENCY, not configuration — which is why it sits here beside {@link now} rather than on
   * `Config`: it varies per build, not per deployment, and a test must be able to pin it. Reading it as a
   * module-level import instead would make the health test assert against whatever the generated file
   * happened to hold. Defaults to the value resolved into this bundle.
   */
  buildSha?: string;
};

// One file in the per-device listing response — exactly `filename` and `url` (a closed shape).
//
// `size` USED TO BE HERE and is gone. It had no reader: the iOS `UnionResource` model omits it,
// `HttpDeviceFilesSource` documents it as an ignored unknown key, and the web zip page reads only
// `role`/`url`/`filename`/`key`. Dropping it is what makes the byte route's database write safe to LOSE
// (capability `api-endpoints`): `size` was the one field sourced from storage rather than the manifest,
// so carrying it would have forced a column only that best-effort write could fill — and one lost write
// would then leave a NULL, make this closed shape unemittable, and silently drop the asset.
type FileEntry = {
  filename: string;
  url: string;
};

// One asset in the event-wide union: the owning `deviceId` (own-vs-foreign skip is the client's
// concern), the device-local `assetId`, the capture `creationDate`, and the complete set of resources.
type UnionResource = {
  role: string;
  contentType: string;
  key: string;
  filename: string;
  url: string;
};
type UnionAsset = {
  deviceId: string;
  assetId: string;
  creationDate: string;
  resources: UnionResource[];
};

/**
 * Validate a device manifest body into the entries the publish records (wire format: capability
 * `device-manifest`). Returns `null` when the body is not a manifest — a `400`, never a partial publish.
 *
 * Unknown fields are IGNORED rather than rejected: the manifest is written by a shipped app, and a
 * backend that refused a field a future client adds would break every device the moment that client
 * shipped. What is checked is what this backend records.
 *
 * `uploaded` is read here so it can be carried through `publishStatements`, where ABSENT means `true`.
 * A client that omits it — every client today does — publishes only COMPLETED resources, so `true` is
 * right by construction; a future client that publishes a pending set can say `false` and be believed.
 */
function parseManifestAssets(body: { assets?: unknown }): ManifestAssetEntry[] | null {
  if (!Array.isArray(body.assets)) return null;
  const out: ManifestAssetEntry[] = [];
  for (const raw of body.assets) {
    if (typeof raw !== "object" || raw === null) return null;
    const a = raw as Record<string, unknown>;
    if (typeof a.assetId !== "string" || a.assetId === "") return null;
    if (typeof a.creationDate !== "string") return null;
    if (!Array.isArray(a.resources) || a.resources.length === 0) return null;
    const resources = [];
    for (const rawResource of a.resources) {
      if (typeof rawResource !== "object" || rawResource === null) return null;
      const r = rawResource as Record<string, unknown>;
      if (
        typeof r.role !== "string" || typeof r.contentType !== "string" ||
        typeof r.key !== "string" || r.key === "" || typeof r.filename !== "string"
      ) return null;
      resources.push({
        role: r.role,
        contentType: r.contentType,
        key: r.key,
        filename: r.filename,
        uploaded: typeof r.uploaded === "boolean" ? r.uploaded : undefined,
      });
    }
    out.push({ assetId: a.assetId, creationDate: a.creationDate, resources });
  }
  return out;
}

// 7 days — the S3 presign maximum. The device re-presigns (re-reads the union) on every foreground well
// within this window, so a queued background download that outlives one URL self-heals with a fresh one.
const PRESIGN_EXPIRY_SECONDS = 604800;

// The listing routes' cache header. All three directives are deliberate: the Edge Script is fronted by a
// bunny CDN pull zone, and bunny documents `no-cache` — NOT `no-store` — as the origin directive that
// suppresses its cache. `no-store` alone would rest the listings' cacheability on undocumented behavior,
// and a cached listing serves stale, expiring presigned URLs.
const NO_CACHE = "no-store, no-cache, max-age=0";

// What a maintenance `503` suggests waiting (capability `backend-deployment`). HTTP pairs `Retry-After`
// with `503`, which is the status it defines for scheduled maintenance (RFC 9110 §15.6.4).
//
// IT IS A POLL INTERVAL, NOT AN ESTIMATE OF THE WINDOW, and that distinction is what makes the number
// defensible rather than invented. Every `503` re-issues this header, so a caller that honours it asks
// again, gets a fresh hint, and converges — which means the value only has to answer "how long until it
// is worth asking again", never "how long will this last".
//
// That framing is what keeps it robust to a window whose length nobody can predict. The MIGRATION is not
// the term that sets the duration — v1–v3 are small DDL batches, milliseconds against the remote store.
// What dominates is TWO publishes and their propagation, which `probe.ts` polls at 5 s intervals with a
// 120 s deadline apiece. So the window plausibly runs anywhere from ~10 s (propagation instant, each
// probe satisfied on its first request) to ~250 s (both probes near their deadlines). A single constant
// cannot name that range; a poll interval does not have to.
//
// UNDER-ESTIMATING IS THE SAFER ERROR, which is why this sits below even the fast case. Too low costs a
// few wasted requests during the window. Too high keeps a caller that honours it away AFTER the service
// is back — unavailability we would be inflicting ourselves, past the outage we actually chose.
//
// Deliberately NOT tied to `probe.ts`'s interval: they answer different questions (how fast may CI ask
// again vs how fast may a stranger), and coupling them would be false precision. No shipped SnapSync
// client reads this at all — the upload engine retries forever with no attempt budget, and create/join
// map any unrecognised status to their existing transient states — so its audience is a human, a proxy,
// or a curl.
const MAINTENANCE_RETRY_AFTER_SECONDS = 30;

// PUBLIC and static — the deliberate inverse of the listings' NO_CACHE. A `public` directive lets the
// bunny pull zone serve it from the edge, keeping the Edge Script off the request hot path. Still used by
// the AASA and (until Phase 2) the `/join` page.
const PUBLIC_CACHE = "public, max-age=300";

// Cache policy for the proxied `site/` objects (capability `web-site`). HTML entry points are the
// always-fresh shell — `no-cache` so a deploy is picked up immediately; the pull zone still revalidates
// cheaply. Fingerprinted assets are addressed by content hash, so they are immutable for a year — the hash
// is the version, and a changed asset gets a new URL.
const SITE_HTML_CACHE = "no-cache";
const SITE_ASSET_CACHE = "public, max-age=31536000, immutable";

// Content-Type by extension for proxied `site/` objects — deterministic, so we do not rest the served
// type on the storage API's guess. Falls back to octet-stream.
function siteContentType(sitePath: string): string {
  if (sitePath.endsWith(".html")) return "text/html; charset=utf-8";
  if (sitePath.endsWith(".webp")) return "image/webp";
  if (sitePath.endsWith(".css")) return "text/css; charset=utf-8";
  if (sitePath.endsWith(".js") || sitePath.endsWith(".mjs")) {
    return "text/javascript; charset=utf-8";
  }
  if (sitePath.endsWith(".svg")) return "image/svg+xml";
  if (sitePath.endsWith(".png")) return "image/png";
  if (sitePath.endsWith(".json")) return "application/json; charset=utf-8";
  if (sitePath.endsWith(".ico")) return "image/x-icon";
  if (sitePath.endsWith(".woff2")) return "font/woff2";
  return "application/octet-stream";
}

/**
 * Serve a built page/asset by streaming it from the storage `site/` prefix (capability `web-site`). The api
 * owns this routing in source (no pull-zone edge rules, no account key); the pull zone caches the response
 * by the `Cache-Control` we set here, so only cold misses reach the script. `sitePath` is the storage key
 * under `site/` (e.g. `index.html`, `_astro/app.<hash>.js`). `HEAD` returns the headers with no body. A
 * missing object is `404`; any other upstream failure is `502` — the same faithful-outcome contract as the
 * rest of the api (never a false success, never a partial body mislabelled `200`).
 */
async function serveSiteObject(
  fetchImpl: FetchLike,
  config: Config,
  sitePath: string,
  method: string,
  cacheControl: string,
): Promise<Response> {
  const url = `https://${config.host}/${config.zone}/site/${sitePath}`;
  let upstream: Response;
  try {
    upstream = await fetchImpl(url, { method: "GET", headers: { AccessKey: config.accessKey } });
  } catch (e) {
    console.error(`site: upstream GET errored for site/${sitePath}: ${e}`);
    return new Response("upstream error", { status: 502 });
  }
  if (upstream.status === 404) {
    await upstream.body?.cancel();
    return new Response("not found", { status: 404 });
  }
  if (!upstream.ok) {
    await upstream.body?.cancel();
    console.error(`site: bunny returned ${upstream.status} for site/${sitePath}`);
    return new Response("upstream error", { status: 502 });
  }
  const headers = new Headers({
    "Content-Type": siteContentType(sitePath),
    "Cache-Control": cacheControl,
  });
  if (method === "HEAD") {
    await upstream.body?.cancel();
    return new Response(null, { status: 200, headers });
  }
  return new Response(upstream.body, { status: 200, headers });
}

/**
 * Mint an AWS SigV4 **presigned S3 GET URL** for a stored object (the download-URL authority for
 * `api-endpoints`): `<s3Scheme>://<s3Host>/<zone>/<key>?X-Amz-…&X-Amz-Signature=…` — `https` in
 * every deployed configuration; only the local dev rig moves it, so it can serve loopback HTTP that a
 * device can actually fetch. Path-style, each
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
  const url =
    `${config.s3Scheme}://${config.s3Host}/${config.zone}/${byteKey(deviceId, filename)}` +
    `?X-Amz-Expires=${PRESIGN_EXPIRY_SECONDS}`;
  const signed = await aws.sign(url, { method: "GET", aws: { signQuery: true } });
  return signed.url;
}

/**
 * The device's registered push token, or `null` when it has none. Used by the notify fan-out, which is
 * **best-effort** — a member without a registered token is simply skipped, so this NEVER throws.
 *
 * There is no parsing left to do: the columns ARE the shape (capability `database`), so a malformed
 * registration cannot reach here — it is refused at the write, by the route that made it.
 */
async function readPushToken(db: Db, deviceId: string): Promise<PushToken | null> {
  try {
    return await readDeviceRecord(db, deviceId);
  } catch {
    return null; // store unreachable → skip this member (best-effort)
  }
}

// (`isNotifyPath` + `constantTimeEqual` lived here to authorize the notify-only ADMIN_NOTIFY_KEY. That
// credential is retired — a device token is now the only one this backend accepts — so both are gone
// rather than left as dead code inviting a second bearer-secret path.)

export function createApp(
  { fetch: fetchImpl, config, db, now = Date.now, buildSha = BUILD_SHA }: Deps,
): Hono {
  // The S3 signer used ONLY to presign download URLs (capability `api-endpoints`). Access Key ID =
  // the zone name, secret = the storage-zone `AccessKey`; pure Web-Crypto, no network. Uploads/reads/
  // listings stay on the native API and are not signed with this.
  const aws = new AwsClient({
    accessKeyId: config.zone,
    secretAccessKey: config.accessKey,
    region: config.s3Region,
    service: "s3",
  });

  // The APNs provider sender (capability `apns-push-sender`), memoizing its ES256 provider JWT across
  // sends. Used by the notify fan-out and the expiry reap's member notification. No production caller
  // is wired to notify yet (the trigger is a deferred use case).
  const apns = createApnsSender(config, fetchImpl);

  // ── THE EVENT-LIMITS GATE (capability `event-limits`) ───────────────────────────────────────────
  //
  // Every event-scoped route resolves its event through `gateEvent` below: one row read. The lifecycle
  // is BINARY — an event exists, or the sweep has deleted it. `endsAt` is NOT
  // consulted: it bounds only which captures may be UPLOADED, and closes nothing. In particular JOINING
  // IS NEVER CLOSED BY TIME, because a guest who scans days late still holds in-window captures that
  // belong in the event. There is no on-touch reap: deleting is the nightly sweep's alone
  // (capability `scheduled-cleanup`), including for an event already past its derived delete-by.

  /**
   * Resolve an event for a route: ONE row read (capability `database`). An event exists exactly when its
   * row does, so `absent` now means precisely "never created, or the sweep deleted it" — the INCOMPLETE
   * case the marker era had to carry is unstateable, because `startsAt`, `endsAt`, `capacity` and
   * `lifetimeSeconds` are `NOT NULL` columns.
   *
   * THROWS on a store failure, so the route surfaces 502 and never mistakes a transient fault for
   * absence. That distinction is load-bearing beyond this file: a `404` here is a SEALED deletion, and
   * `leave-event`'s two-witness teardown acts on it.
   */
  async function gateEvent(
    eventId: string,
  ): Promise<{ kind: "ok"; event: EventRow } | { kind: "absent" }> {
    const event = await readEvent(db, eventId);
    return event === null ? { kind: "absent" } : { kind: "ok", event };
  }

  /**
   * The WIRE shape of an event (capabilities `api-endpoints`, `event-limits`): the row's public
   * fields with the stamped `lifetimeSeconds` replaced by the DERIVED `deletesAt`, in the canonical
   * cutoff shape.
   *
   * Serving the derived instant — rather than the duration and the anchor for a client to combine —
   * keeps the anchor policy in ONE place and means no client ever holds a copy of the lifetime constant.
   * A duplicated constant would let a join gate confidently promise a date the backend will not honour,
   * and the drift would be silent.
   */
  function publicEvent(event: EventRow) {
    const { lifetimeSeconds: _stamped, ...wire } = event;
    return { ...wire, deletesAt: canonicalFromMs(deleteByMs(event)) };
  }

  // Per-device byte WRITE route (capability `api-endpoints`). Mounted under
  // `/files/devices/:deviceId/:filename`, so the handlers read `deviceId`/`filename` from the mount.
  // (Downloads are no longer proxied here — the listing hands out a presigned S3 GET URL the device
  // fetches directly from bunny's S3 endpoint.)
  const byteFile = new Hono();

  // Upload — GATED by the device token like every other route, but it reads NO EVENT: bytes are
  // device-partitioned and event-independent, so there is nothing to resolve. Stream the body straight
  // into one bunny native PUT at `files/devices/<deviceId>/<filename>`, then best-effort record the
  // resource row. Faithful: 201 only on a confirmed store; last-write-wins (no existence check on the key).
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
    // Bunny confirmed the stored object. Record the upload (capability `database`) — BEST-EFFORT: this
    // route's success is "the bytes landed", and failing it because a bookkeeping row did not land would
    // turn a successful upload into a retried one.
    //
    // The collapse is safe because the record is REPAIRED, not lost. The device manifest publish is a
    // full-state document listing only uploaded resources, it upserts each resource's `uploaded` as true
    // when the entry does not say otherwise, and it fires in the SAME cycle that produced these bytes.
    // That repair in turn rests on `device-manifest`'s rule that an unchanged manifest may be skipped
    // only when the LAST WRITE SUCCEEDED — without that word a doubly-failed write would strand
    // `uploaded` at 0 while the device believed it had published, and the photo would be invisible to
    // every other member with no error anywhere. Do not edit one of those two rules alone.
    try {
      // Keyed by the URL's final segment — the bare object name the manifest publish upserts on. A full
      // path here would create a SECOND row the manifest never touches, so the repair path would silently
      // stop repairing.
      await markUploaded(db, deviceId, filename);
    } catch (e) {
      console.error(`upload: could not record ${byteKey(deviceId, filename)}: ${e}`);
    }
    return c.body(null, 201);
  });

  // OPTIONS: do NOT advertise resumable uploads → the iOS uploader falls back to a plain PUT.
  byteFile.options("/", (c) => {
    c.header("Allow", "PUT, OPTIONS");
    return c.body(null, 204);
  });

  const app = new Hono();

  // ── THE MAINTENANCE GATE (capability `backend-deployment`) ──────────────────────────────────────
  //
  // While this bundle carries the maintenance flag, every route under `/api/` answers `503` and touches
  // neither storage nor the database. It exists to close the one interval the deploy pipeline could not:
  // between a migration landing and the bundle written against it serving, the PREVIOUS bundle answers
  // requests against the MIGRATED store, with statements written for a shape that no longer exists.
  //
  // REGISTERED FIRST — ahead of the token gate below — so maintenance wins over `401`. That is cheaper (no
  // HMAC verification) and truthful: the service is unavailable, and the caller's credentials are not what
  // is wrong. It discloses nothing `/health` does not already disclose publicly.
  //
  // MATCHED AS A PREFIX, NEVER AS A LIST OF ROUTES, and that is the whole design rather than a shortcut.
  // A closed list can be omitted from — a route added later lands ungated by nobody's decision — whereas
  // a prefix cannot be, and a future `/api/v2` mount inherits this by construction. It is also why the
  // gate is here rather than wrapped around the `Db` and storage ports: port decoration would gate by
  // USAGE, which is the better property on paper, but this file's best-effort `catch` blocks deliberately
  // swallow (`markUploaded` logs and still answers `201`), so a thrown maintenance error would let a byte
  // upload stream to storage, silently lose its `resources` row, and report success — after which the
  // device never retries. Decision record: `changes/add-deploy-maintenance-mode/design.md` D1.
  //
  // ROOT ROUTES ARE NOT GATED: `/`, `/join`, `/_astro/*` and the AASA read only the public storage `site/`
  // prefix or nothing at all, so a schema migration has no bearing on them — and `/health` is how the
  // deploy learns the window's state, so gating it would blind the step that lifts the window.
  //
  // `NO_CACHE` IS LOAD-BEARING HERE, not decoration: the pull zone caches on the origin's directives, and
  // a cached `503` would outlive the window — turning a bounded, deliberate outage into an unbounded
  // accidental one. CI cannot configure the pull zone (that needs the account key), so this header is the
  // only lever, and its behaviour is verified THROUGH the pull zone rather than at the origin.
  //
  // Downloads are unaffected by construction: presigned S3 URLs are fetched straight from bunny's S3
  // endpoint and never reach this script.
  if (config.maintenance) {
    // `next` is deliberately never called: this middleware SHORT-CIRCUITS, so no handler runs and no
    // upstream request is made. `async` because Hono's middleware signature returns a promise.
    app.use("/api/*", async (c) => {
      c.header("Cache-Control", NO_CACHE);
      c.header("Retry-After", String(MAINTENANCE_RETRY_AFTER_SECONDS));
      return await Promise.resolve(c.text("maintenance", 503));
    });
  }

  // ── THE GATE (capability `device-attestation`) ──────────────────────────────────────────────────
  //
  // Every route requires a device token, obtainable ONLY by completing App Attest — so the API is
  // callable by a genuine, unmodified SnapSync on a genuine Apple device, and by nothing else. What this
  // closes is bill/storage abuse: the byte route resolves no event, the device id is self-asserted, and the
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
    const method = c.req.method;
    // Device-API routes are served under a versioned prefix (`/api/v1`, capability `backend-deployment`),
    // and Hono does NOT strip the mount prefix from the path accessors — so normalize a leading `/api/vN`
    // away HERE, once, before the closed-list checks below, which are written in un-prefixed terms. This is
    // deliberately version-agnostic (`v\d+`): a future `/api/v2` mount is gated identically with no change
    // here. `/api/v1` → `/`, `/api/v1/attest/x` → `/attest/x`.
    const rawPath = new URL(c.req.url).pathname;
    const stripped = rawPath.replace(/^\/api\/v\d+(?=\/|$)/, "");
    const path = stripped === "" ? "/" : stripped;
    // Ungated (closed list): OPTIONS, the `/attest/*` token issuers, the public marketing page at
    // EXACTLY `/` (capability `marketing-site`), and the event link's two public routes (capability
    // `event-link`) — the AASA, which Apple's CDN and the device fetch with no Authorization header and
    // cannot be made to send one, and `/join`, whose entire audience is people who have no app and so no
    // attestation. These three (`/`, `/join`, the AASA) are exact-path and GET/HEAD-only — never a prefix,
    // never a mutating method — and read no storage, so serving them unauthenticated grows neither the bill
    // nor the storage this gate protects. They are served at the ROOT only, never under `/api/v1`; the
    // normalization above is what lets `/attest/*` (a device route, so it arrives prefixed) AND the two
    // event READS added below — also device routes — be matched here on the normalized `path`.
    // `/` and the site's fingerprinted assets under `/_astro/*` are the browser-facing site (capability
    // `web-site`), proxied by the api from the PUBLIC storage `site/` prefix. Unlike the other public GETs
    // they DO read storage — but only the public `site/` prefix, never the bill-/photo-protected user data
    // this gate guards, so serving them unauthenticated is safe. GET/HEAD only.
    // `/health` is the third reason a path is ungated, and a different one from the two above: it is
    // OPERATIONAL. It exists so the deploy workflow can tell a booted script serving THIS bundle from a
    // corpse or a previous deployment (capability `backend-deployment`), and it is the cheapest route in
    // the backend — no storage read, no crypto, one constant string. Serving it unauthenticated
    // discloses only the commit of a PUBLIC repository, and costs strictly less than `/join` or the two
    // public event reads below, which are already ungated and uncacheable and do touch storage.
    const publicGet = path === "/" || path === "/join" || path === "/health" ||
      path === "/.well-known/apple-app-site-association" ||
      path.startsWith("/_astro/");
    // The two event READS the no-app download page fetches (capability `web-event-download`): the event
    // metadata `/events/<id>` and the photo union `/events/<id>/files`. These are authorized by
    // eventId-possession alone — the eventId IS the read capability — so a browser that holds no attestation
    // can fetch them. This narrows the gate's READ posture (attestation never proved who may read whose
    // photos, and the presigned bytes it fronts were always ungated); it does NOT open any WRITE. The match
    // is GET/HEAD-only and shape-anchored to exactly these two paths, so every mutating `/events/<id>/…`
    // method (device manifest, leave, notify), `POST /events`, and — landing on the SAME path shape as
    // the read below, which makes it the closest call here — `PATCH /events/<id>` (the rename, capability
    // `event-rename`) all stay gated. The method check is the ONLY thing separating the rename from the
    // ungated read; `attest.test.ts` pins both directions. Decision record:
    // `changes/web-event-download`. This is an accepted, eyes-open widening: a leaked eventId becomes a
    // perpetual read grant (no per-event opt-in, no rate limit).
    const publicRead = (method === "GET" || method === "HEAD") &&
      (/^\/events\/[^/]+$/.test(path) || /^\/events\/[^/]+\/files$/.test(path));
    if (
      method === "OPTIONS" ||
      path.startsWith("/attest/") ||
      ((method === "GET" || method === "HEAD") && publicGet) ||
      publicRead
    ) {
      return await next();
    }

    const auth = c.req.header("authorization") ?? "";
    const token = auth.startsWith("Bearer ") ? auth.slice("Bearer ".length).trim() : "";

    // A valid device token is the ONLY credential this backend accepts. There is no admin key, master
    // key, or route-scoped bypass: the former notify-only ADMIN_NOTIFY_KEY existed solely so the
    // out-of-edge sweep could announce an expiring event before deleting it, and that announcement is
    // gone (capability `scheduled-cleanup`) — so the credential is retired rather than left standing as
    // an authorization path with no caller.
    if (!token || !await verifyToken(config, token, now())) {
      return c.text("unattested", 401);
    }
    return await next();
  });

  // The public marketing/landing page (capability `marketing-site`, built by `web-site`): served by
  // proxying `site/index.html` from storage. The HTML entry point is `no-cache` — the always-fresh shell —
  // so a deploy is picked up immediately; it references immutable, content-hashed `/_astro/*` assets. The
  // gate above admits `/` (GET/HEAD).
  app.on(
    ["GET", "HEAD"],
    "/",
    (c) => serveSiteObject(fetchImpl, config, "index.html", c.req.method, SITE_HTML_CACHE),
  );

  // The landing page's fingerprinted assets (capability `web-site`): served by proxying `site/_astro/*`
  // from storage with a year-long immutable cache — the content hash in the name is the version, so the
  // pull zone serves repeat hits from the edge and only cold misses reach the script. The wildcard segment
  // after `/_astro/` is the storage key tail. The gate above admits `/_astro/*` (GET/HEAD).
  app.on(["GET", "HEAD"], "/_astro/*", (c) => {
    const tail = new URL(c.req.url).pathname.slice("/".length); // "_astro/app.<hash>.js"
    return serveSiteObject(fetchImpl, config, tail, c.req.method, SITE_ASSET_CACHE);
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

  // The no-app download page (capabilities `event-link`, `web-event-download`, built by `web-site`): the
  // path a browser requests when an event link is opened on a device with no app to claim it. Served by
  // proxying the CONSTANT `site/join/index.html` object from storage — byte-identical for every link, and
  // `no-cache` (the always-fresh shell). GET returns the page; HEAD returns the headers with no body.
  //
  // The handler does not — cannot — read the payload: that rides in the URL fragment, which a browser never
  // transmits, so this handler sees `/join` and nothing more, and it reads the same constant object for
  // every request (no per-event state). Everything per-event — the event name, the photo union, the zip —
  // is done by the page's own client island, off the fragment. The eventId never reaches the server here.
  app.on(
    ["GET", "HEAD"],
    "/join",
    (c) => serveSiteObject(fetchImpl, config, "join/index.html", c.req.method, SITE_HTML_CACHE),
  );

  // The BOOT PROBE's target (capability `backend-deployment`). Answers with the commit this bundle was
  // built from, so `api-deploy.yml` can tell the deploy it just made from the one that was already live —
  // `POST /code` + `POST /publish` succeed whether or not the bundle can boot, and a bare `200` cannot
  // distinguish a new deployment from an old corpse still being served.
  //
  // TOTAL BY CONSTRUCTION: it returns 200 or it did not run. `readConfig` is called at module top level in
  // `main.ts`, OUTSIDE this app, so a configuration failure means the script never serves at all rather
  // than that this route answers with an error — a `503` branch here would be dead code. Distinguishing
  // causes is the probe's job, from the combination of status and body.
  //
  // `NO_CACHE` because the pull zone must never answer a probe from the PREVIOUS deploy's copy — which is
  // exactly the false green this exists to prevent. Root-mounted, never under `/api/v1`: no device calls
  // it, so a future `/api/vN` should neither duplicate nor strand it. It is also NOT gated by the
  // maintenance middleware above — it is how the deploy learns the window's state, so gating it would
  // blind the thing that lifts the window.
  //
  // IT REPORTS `maintenance` ONLY WHEN THE WINDOW IS OPEN, and its ABSENCE MEANS CLOSED. The two bundles a
  // migrating deploy publishes are built from the SAME COMMIT, so `sha` alone cannot tell them apart —
  // this field is what does, and the probe asserts it in both directions.
  //
  // The absence is a DELIBERATE COLLAPSE, and it is safe because both causes it absorbs are the same
  // answer: a bundle that predates the flag, and a bundle whose flag is false. The first is not merely
  // *probably* not in maintenance — maintenance mode did not exist when it was built, so it is NECESSARILY
  // serving the device API. Nothing else can produce the absence: a response from something that is not
  // this backend fails the `sha` check first, and this commit's own bundle always carries the field when
  // the window is open.
  //
  // IT IS NO LONGER INERT, and that is a deliberate trade. It now reaches BOTH dependencies this
  // deployment has — the relational store and the storage zone — because a bundle identifier alone cannot
  // say whether either is addressable. The storage half is new coverage and closes the documented half of
  // the 2026-07 outage that the probe could not previously see: a `BUNNY_STORAGE_ZONE` that is present but
  // names a zone that does not exist boots and probes green. Serving this unauthenticated is still the
  // right call, and the reason has changed: it is no longer "the cheapest route in the backend" but "the
  // probe carries no credential this backend accepts, so any route it can reach is equally ungated" —
  // moving the checks behind another path would relocate the exposure, not remove it.
  //
  // AN UNREACHABLE DEPENDENCY IS A BARE NON-SUCCESS, not a `200` describing itself. The route used to
  // report the store's state in the body so the probe could split a TERMINAL cause (foreign keys off) from
  // a RETRYABLE one (unreachable); with the foreign-key assertion gone (capability `database` — the
  // measurement is now trusted, and what would falsify it is recorded there) the only condition left is
  // unreachability, which is retryable, and a non-success status already carries that. The cost, accepted:
  // a red probe says `server-error` rather than naming which dependency was unreachable — so the causes
  // are logged here, where a human reading the script's output can still tell them apart.
  //
  // BOTH CHECKS RUN, always, even when the first fails: a deploy that has broken both should not need two
  // rounds to find out.
  app.on(["GET", "HEAD"], "/health", async (c) => {
    c.header("Cache-Control", NO_CACHE);
    c.header("Content-Type", "application/json");
    const [store, zone] = await Promise.all([
      db.execute("SELECT 1").then(() => true).catch((e) => {
        console.error(`health: relational store unreachable: ${e}`);
        return false;
      }),
      storageReachable(fetchImpl, config).then((ok) => {
        if (!ok) console.error(`health: storage zone '${config.zone}' unreachable`);
        return ok;
      }),
    ]);
    if (!store || !zone) return c.body(null, 503);
    const body = JSON.stringify(
      config.maintenance ? { sha: buildSha, maintenance: true } : { sha: buildSha },
    );
    return c.req.method === "HEAD" ? c.body(null) : c.body(body);
  });

  // ── THE DEVICE API (capability `backend-deployment`) ────────────────────────────────────────────
  //
  // Every device-API route below is registered on this ONE sub-app, mounted under `/api/v1` at the end of
  // `createApp`. Keeping the sub-app is what makes the routing version-parametric by construction: a future
  // `/api/v2` is one more `app.route(...)` of a v2 router, without touching v1. The web/link routes above
  // stay at the ROOT, never under `/api/v1`.
  //
  // The gate (`app.use("*")`) runs for the mount (verified: Hono runs parent middleware for mounted
  // sub-apps) and normalizes the `/api/vN` prefix, so the ungated `/attest/*` set holds under it.
  const deviceApi = new Hono();

  // Issue a challenge. Stateless and self-authenticating (an HMAC over its own expiry), so this writes
  // NOTHING — the one route a stranger can call cannot grow the bill this gate exists to protect.
  deviceApi.get("/attest/challenge", async (c) => {
    c.header("Cache-Control", NO_CACHE);
    return c.json({ challenge: await mintChallenge(config, now()) });
  });

  // Attest: verify the attestation object, persist the attested public key, mint a token.
  deviceApi.post("/attest/token", async (c) => {
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
    // expensive to attempt at every wake. This INSERT is also the device's enrolment: a `devices` row
    // exists if and only if the device has attested, and this is the only route that creates one.
    //
    // PERSIST BEFORE MINTING. A token handed out against a record we failed to write is a credential
    // nothing knows about; the client retries at its next wake, so refusing costs nothing.
    try {
      await putAttestation(
        db,
        deviceId,
        { publicKey: bytesToB64(verified.publicKey), environment: verified.environment },
        new Date(now()).toISOString(),
        tokenExpiryIso(config, now()),
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
  deviceApi.post("/attest/renew", async (c) => {
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

    let record: Awaited<ReturnType<typeof readAttestation>>;
    try {
      record = await readAttestation(db, deviceId);
    } catch (e) {
      // Absence and "could not ask" are DIFFERENT answers here and must not collapse: absence sends the
      // device down a full Apple attestation, which is the throttled path, so a database blink must read
      // as retry-me and not as attest-again.
      console.error(`renew: could not read the attestation record for ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (record === null) {
      // Two causes, one answer, and the log keeps them apart: a device the backend has never seen, or one
      // whose row the nightly sweep collected. Both mean "attest afresh", which is what the client does.
      console.info(`renew: no attestation on file for ${deviceId} — never attested, or collected`);
      return c.text("not attested", 401);
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

    // RECORD THE NEW EXPIRY BEFORE MINTING, exactly as `/attest/token` persists before minting. The sweep
    // decides whether this device may still hold a working credential from this value; minting first and
    // writing after would leave the store understating the token's life, and the sweep would then collect
    // a device that is still using it — costing it a full re-attestation.
    try {
      const { rowsAffected } = await touchTokenExpiry(db, deviceId, tokenExpiryIso(config, now()));
      if (rowsAffected === 0) {
        // The row went away between the read and this write. Nothing to renew against.
        console.info(`renew: the attestation record for ${deviceId} vanished mid-renewal`);
        return c.text("not attested", 401);
      }
    } catch (e) {
      console.error(`renew: could not record the token expiry for ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }

    return c.json({ token: await mintToken(config, deviceId, now()) }, 201);
  });

  // Create an event (capabilities `api-endpoints`, `event-limits`). GATED by the device token above (an
  // ungated create let a stranger mint unbounded events). Beyond that gate it stays a
  // possession-is-capability model. Validates the name, mints a server-side UUID, and INSERTs the row.
  // Faithful outcome: 201 only after the store confirms the write; any failure → 502.
  deviceApi.post("/events", async (c) => {
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
    // `endsAt` is CREATOR-SUPPLIED at mint (capability `event-limits`) and bounds ONLY which captures may
    // be uploaded — it is not a lifetime. When the body carries one it is validated (canonical instant,
    // strictly after `startsAt`, and no longer than the configured WINDOW MAXIMUM) and stamped; when
    // ABSENT it falls back to `startsAt + windowMax`, so old clients that send only `startsAt` keep
    // working. A present-but-invalid `endsAt` is a 400. `capacity` and `lifetimeSeconds` stay
    // server-resolved — a client-supplied `capacity` (and `eventId`) is still ignored. The config values
    // are consulted HERE ONLY; enforcement reads the row's own stamped fields.
    const rawEndsAt = (body as { endsAt?: unknown } | null)?.endsAt;
    let endsAt: string;
    if (rawEndsAt === undefined || rawEndsAt === null) {
      endsAt = canonicalPlusSeconds(startsAt, config.eventWindowMaxSeconds); // absent-endsAt fallback
    } else {
      const validated = validateEndsAt(rawEndsAt, startsAt, config.eventWindowMaxSeconds);
      if (validated === null) {
        // non-canonical / not a real instant / not after startsAt / longer than the window maximum
        return c.text("invalid endsAt", 400);
      }
      endsAt = validated;
    }
    const event: EventRow = {
      eventId: crypto.randomUUID(),
      name,
      createdAt: new Date(now()).toISOString(),
      startsAt,
      endsAt,
      capacity: config.eventCapacity,
      lifetimeSeconds: config.eventLifetimeSeconds,
    };

    try {
      await insertEvent(db, event);
    } catch (e) {
      console.error(`create: event insert failed for ${event.eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    // The row exists — only now is the event created.
    return c.json(publicEvent(event), 201);
  });

  // Event metadata / existence (capability `api-endpoints`). Returns the event — always carrying
  // `startsAt`, `endsAt`, `capacity`, and the derived `deletesAt`, because those are `NOT NULL` columns
  // — or 404 when the event was never created or the sweep deleted it; a read failure → 502. This is the
  // canonical existence check the device-manifest write gate relies on. There is no third answer: the
  // INCOMPLETE case the object era had to carry is unstateable now (see `gateEvent`).
  //
  // An event past its WINDOW (`endsAt`) serves normally — the window closes nothing. An event past its
  // derived `deletesAt` ALSO serves normally until the nightly sweep removes it: no route deletes on
  // touch. The 404 a client acts on is therefore always a real deletion, which is what makes it safe as
  // one of the two witnesses the client's self-leave requires (capability `leave-event`).
  deviceApi.get("/events/:eventId", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }
    try {
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
      return c.json(publicEvent(gate.event));
    } catch (e) {
      console.error(`metadata: event read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Rename an event (capability `event-rename`). The ONLY route that writes an existing event row, and
  // it writes exactly ONE column. `name` is the single exception to the row's write-once rule
  // (capability `event-limits`) because it touches neither threat that rule names: a name cannot
  // retroactively widen a joiner's capture scope and cannot extend an event's limits. It is cosmetic to
  // the upload gate, cosmetic to the extension, and load-bearing for display alone.
  //
  // GATED by the device token like `POST /events`, and BEYOND that gate there is no ownership check —
  // there is no owner field, and possession of the event id already authorizes uploading into the event
  // and listing every photo in it, so a rename is strictly weaker than what a holder already has.
  //
  // ⚠️ Every other field is written back VERBATIM — never restamped, never recomputed. That is what
  // makes a race with the nightly sweep (capability `scheduled-cleanup`) self-defusing: a rename that
  // re-creates a row the sweep has just deleted re-creates it carrying its ORIGINAL `createdAt`,
  // `startsAt`, and `lifetimeSeconds`, so its derived delete-by is still in the past and the next sweep
  // reaps it again. Restamping any of those would resurrect the event for a fresh lifetime.
  //
  // Concurrent renames are last-write-wins: bunny has no compare-and-set (the same constraint the
  // device-manifest capacity gate reads and writes under). No ordering guarantee is available or claimed.
  deviceApi.patch("/events/:eventId", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }
    let body: unknown;
    try {
      body = await c.req.json();
    } catch {
      return c.text("invalid body", 400); // not JSON
    }
    // The SAME validator the create route uses — one rule for what an event may be called.
    const name = validateEventName((body as { name?: unknown } | null)?.name);
    if (name === null) {
      return c.text("invalid name", 400); // missing/empty/whitespace/too long
    }

    // The same existence gate the metadata route serves from: absent → 404 (never a partial
    // rewrite of a row the sweep is about to delete); a transport failure → 502, so a
    // transient fault is never mistaken for absence.
    let current: EventRow;
    try {
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
      current = gate.event;
    } catch (e) {
      console.error(`rename: event read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

    // ONE column. `renameEvent` is a `SET name = ?` and nothing else — see `db.ts`, where the statement
    // is spelled out in one place precisely because widening it is now a one-word edit.
    try {
      const written = await renameEvent(db, eventId, name);
      // Zero rows means the event was deleted between the gate and the write. Report the absence rather
      // than a success that renamed nothing.
      if (written.rowsAffected === 0) return c.text("event not found", 404);
    } catch (e) {
      console.error(`rename: update failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    return c.json(publicEvent({ ...current, name }));
  });

  // Write a device's per-event manifest (capabilities `api-endpoints`, `device-manifest`).
  // GATED on event existence AND capacity (capability `event-limits`) by ONE conditional statement: it
  // admits the device when the event exists AND (it already holds a membership — a rejoin reuses its own
  // slot — OR the event has fewer than `capacity` memberships of ANY state, because leaving frees none).
  // The count and the insert are evaluated together, so concurrent first enrollments cannot overshoot.
  // A zero-row outcome CONFLATES at-capacity with no-such-event, so it is disambiguated by a follow-up
  // existence read rather than guessed: absent → 404, otherwise → 409. The body then publishes as ONE
  // atomic batch (see `publishStatements`). Any transport failure → 502 (never mistaken for absent or
  // full).
  //
  // CAPACITY IS THE ONLY REFUSAL. There is no time-based rejection: a device may enroll for as long as
  // the event exists, however long after `endsAt` that is, because a guest who scans days late still
  // holds in-window captures that belong in the event. (The former 410 "event over" is deleted with the
  // grace period.)
  //
  // The count is read-then-write without coordination (bunny has no compare-and-set): concurrent first
  // enrollments may transiently overshoot, accepted — what is guaranteed is that a request OBSERVING the
  // event at capacity admits no new device.
  deviceApi.put("/events/:eventId/devices/:deviceId", async (c) => {
    const eventId = c.req.param("eventId");
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(eventId) || !validateUUID(deviceId)) {
      return c.text("invalid key", 400);
    }

    // The manifest is a WIRE FORMAT now, not an object: parse it here rather than streaming it to
    // storage. It is bounded by the device's own library, and the whole point of reading it is that the
    // backend records what it says.
    let body: { assets?: unknown };
    try {
      body = await c.req.json();
    } catch {
      return c.text("invalid body", 400);
    }
    const assets = parseManifestAssets(body);
    if (assets === null) return c.text("invalid manifest", 400);

    // Enrollment IS the capacity gate, evaluated and applied in ONE conditional statement so concurrent
    // first enrollments cannot overshoot (capability `event-limits`). Its zero-row outcome is resolved to
    // `full` or `no-such-event` inside `enroll`, never collapsed into one status.
    let outcome;
    try {
      outcome = await enroll(db, eventId, deviceId, new Date(now()).toISOString());
    } catch (e) {
      console.error(`device-manifest: enrollment failed for ${eventId}/${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (outcome === "no-such-event") return c.text("event not found", 404);
    if (outcome === "full") return c.text("event full", 409);

    // ONE atomic unit: the membership becomes active, the event's asset set for this device is REPLACED
    // wholesale, and every listed resource is upserted. A partial replace must never be observable by
    // the union — which is exactly what a half-applied full-state write would produce.
    try {
      await db.batch(publishStatements(eventId, deviceId, assets));
    } catch (e) {
      console.error(`device-manifest: publish failed for ${eventId}/${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
    return c.body(null, 201);
  });

  // Leave an event (capability `leave-event`). A STATE CHANGE, and non-destructive: mark the membership
  // `departed`. GATED on the event row (absent → 404; read failure → 502). The membership's assets are
  // RETAINED, so the union still serves what the device shared, and the route returns 200 REGARDLESS of
  // remaining membership — the event survives until it expires and is deleted by the nightly sweep
  // (capability `scheduled-cleanup`), which also collects the bytes. No last-member reap, no leave-time
  // garbage collection. Idempotent: a repeated leave, or one naming a membership that never existed,
  // changes nothing and is not an error, so a retried DELETE re-runs
  // harmlessly. Any transport failure → 502.
  deviceApi.delete("/events/:eventId/devices/:deviceId", async (c) => {
    const eventId = c.req.param("eventId");
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(eventId) || !validateUUID(deviceId)) {
      return c.text("invalid key", 400);
    }

    try {
      // The lifecycle gate (capability `event-limits`): an absent event 404s, which the client already
      // treats as "nothing to leave". A leave DURING grace proceeds: members may still
      // depart an over-but-not-yet-swept event.
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
    } catch (e) {
      console.error(`leave: event read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

    try {
      // ONE column. The departed-sibling object and its last-write-wins tie-break are gone: membership
      // is a `state`, so leaving cannot leave a half-renamed pair behind and cannot be double-counted.
      // The membership's assets are RETAINED, so the union still serves what this device shared.
      await departMembership(db, eventId, deviceId);
      // Always succeed: the event persists (rejoinable) regardless of how many active members remain,
      // and a leave naming a membership that never existed changes nothing rather than failing.
      return c.body(null, 200);
    } catch (e) {
      console.error(`leave: depart failed for ${eventId}/${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Event-wide UNION read (capability `api-endpoints`). UNGATED by the token — the no-app download page
  // fetches it from a browser that holds no attestation, so eventId-possession IS the read capability
  // (`device-attestation`'s closed list, entry 8) — but still gated on event EXISTENCE: absent → 404,
  // read failure → 502.
  //
  // ONE QUERY, no fan-out: the event's assets joined to their resources across ACTIVE and DEPARTED
  // memberships, so a member who has left keeps contributing what it already shared. An asset naming a
  // resource with no recorded upload is dropped — defense in depth, since a manifest lists only completed
  // resources (capability `api-endpoints`). Each kept asset is flattened into one array, tagged with its
  // owning deviceId (the endpoint is identity-blind — own-vs-foreign skip is the client's concern). The
  // published manifest is already the event's date-filtered projection, so its asset list is trusted
  // as-is (no re-filtering). Faithful: any read failure → 502, never a partial union. Non-cacheable.
  deviceApi.get("/events/:eventId/files", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }

    // Gate on the event row (capability `database`): absent → 404; a store failure → 502. An event past
    // its window still serves its union — the window closes nothing.
    try {
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
    } catch (e) {
      console.error(`union: event read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

    try {
      // ONE query, spanning the event's memberships in BOTH states: a member who has left keeps
      // contributing the photos it already shared, until the event itself is deleted. What this replaces
      // is a fan-out — one directory listing to discover members, then a manifest read AND a byte listing
      // per member — whose cost grew with the event and which no index could help.
      const rows = await unionRows(db, eventId);

      // Group by (device, asset), keeping each asset's resources together and dropping any asset that
      // names a resource the backend has not recorded as uploaded. That check is DEFENSE-IN-DEPTH, not
      // the completeness mechanism: the manifest lists only uploaded resources, so a listed resource is
      // uploaded by construction, and the sweep protects a referenced byte from collection.
      const byAsset = new Map<string, { row: typeof rows[number]; resources: typeof rows }>();
      for (const r of rows) {
        const id = `${r.deviceId}/${r.assetId}`;
        const slot = byAsset.get(id) ?? { row: r, resources: [] };
        slot.resources.push(r);
        byAsset.set(id, slot);
      }

      const assets: UnionAsset[] = [];
      for (const { row, resources } of byAsset.values()) {
        if (resources.length === 0 || resources.some((r) => !r.uploaded)) continue;
        assets.push({
          deviceId: row.deviceId,
          assetId: row.assetId,
          creationDate: row.creationDate,
          resources: await Promise.all(resources.map(async (r) => ({
            role: r.role,
            contentType: r.contentType,
            key: r.key,
            filename: r.filename,
            // From `key`, never `filename`: the object is stored under its key, and a capture name that
            // differs would presign a URL that 404s at download while everything else looked right.
            url: await presignDownloadUrl(aws, config, r.deviceId, r.key),
          }))),
        });
      }

      c.header("Cache-Control", NO_CACHE); // every `url` is a time-limited presigned S3 URL
      return c.json(assets);
    } catch (e) {
      console.error(`union: assembly failed for event ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // List a device's stored resources (capability `api-endpoints`). Served from the backend's own record
  // of what it accepted — one query — rather than by enumerating storage. Each entry is
  // `{ filename, url }` where `url` is a presigned S3 GET the device fetches directly.
  //
  // Reading the RECORD rather than the byte store is the correct direction for this route's main
  // consumer: the rejoin reconcile seeds `COMPLETED` rows from it (capability
  // `event-rejoin-reconciliation`), and seeding from bytes the backend cannot vouch for would suppress
  // an upload that never happened.
  deviceApi.get("/files/devices/:deviceId", async (c) => {
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(deviceId)) {
      return c.text("invalid device", 400);
    }
    try {
      const stored = await deviceFiles(db, deviceId);
      c.header("Cache-Control", NO_CACHE); // each `url` is a time-limited presigned S3 URL
      const files: FileEntry[] = await Promise.all(stored.map(async (e) => ({
        // `filename` on this route is the STORED OBJECT NAME, not the capture name — that is what the
        // rejoin reconciler matches its ledger keys against.
        filename: e.key,
        url: await presignDownloadUrl(aws, config, deviceId, e.key),
      })));
      return c.json(files);
    } catch (e) {
      console.error(`list: device listing failed for ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Write a device's config document (capability `api-endpoints`). Gated by DEVICE-ID possession alone
  // (no event) — the same capability model as the byte upload. The document is recorded against the
  // device (capability `database`); last-write-wins, and it is not a resource, so it never appears in the
  // per-device listing or the union.
  deviceApi.put("/devices/:deviceId", async (c) => {
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(deviceId)) {
      return c.text("invalid device", 400);
    }
    let push: { kind: string; token: string; env: string } | null;
    try {
      const body = await c.req.json() as { pushToken?: Record<string, unknown> };
      const pt = body.pushToken;
      if (pt === undefined || pt === null) {
        // An explicit absence: the device is telling us it has no registration. Distinct from a
        // malformed body, and a legitimate thing to record.
        push = null;
      } else if (
        typeof pt.kind === "string" && typeof pt.token === "string" && typeof pt.env === "string"
      ) {
        push = { kind: pt.kind, token: pt.token, env: pt.env };
      } else {
        return c.text("invalid pushToken", 400);
      }
    } catch {
      return c.text("invalid body", 400);
    }
    // An UPDATE, never an insert: a `devices` row exists only where the device has attested, and this
    // route cannot attest on its behalf (capability `device-attestation`).
    try {
      const { rowsAffected } = await putDeviceRecord(
        db,
        deviceId,
        push,
        new Date(now()).toISOString(),
      );
      if (rowsAffected === 0) {
        // The token verified — it is ours and unexpired — but we hold no attestation for this device.
        // `401` is the answer because the remedy is the same one a rejected token has, and the shipped
        // client already takes it: drop the token, attest afresh (which CREATES the row), and re-send
        // this registration when a new token arrives. A `201` here would be a silent absence — the device
        // would believe it is reachable while no push could ever reach it, and it writes its registration
        // once per OS-delivered token, so nothing would retry.
        console.info(`config: no attestation on file for ${deviceId} — refusing the registration`);
        return c.text("unattested", 401);
      }
    } catch (e) {
      console.error(`config: device record write failed for ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
    return c.body(null, 201);
  });

  // Notify an event's members (capabilities `api-endpoints`, `apns-push-sender`). GATED on the event row
  // (absent → 404, read failure → 502). Enumerate the ACTIVE members with one query — departed members
  // are skipped, which is what makes leaving stop the pushes without stopping the union; a read failure
  // → 502 (nothing enumerable). Then BEST-EFFORT: read each member's registered push token (no row, or
  // no registration → skipped) and send a silent (content-available) push carrying the route's `eventId`
  // in its payload to the rest. Per-member read/send failures never fail the request
  // — always a bare 202 once the gate passed and members were enumerated. Server-chosen payload
  // (the path event id), all members, no exclusion; the uploader fires this via `upload-completion-notify`.
  deviceApi.post("/events/:eventId/notify", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }

    try {
      // The lifecycle gate (capability `event-limits`): an expired event reaps here and 404s; an
      // event in grace still notifies — members keep full sync until expiry.
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
    } catch (e) {
      console.error(`notify: event read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

    // Enumerate ACTIVE members only — one `state` read. A departed device has left and is not notified.
    let memberIds: string[];
    try {
      memberIds = await membersOf(db, eventId, ["active"]);
    } catch (e) {
      console.error(`notify: member read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

    // Best-effort per-member token read (skips members without a registered token), then fan out.
    const tokens = (await Promise.all(memberIds.map((d) => readPushToken(db, d))))
      .filter((t): t is PushToken => t !== null);
    const outcomes = await apns.sendSilent(tokens, eventId);
    const sent = outcomes.filter((o) => o.status === "sent").length;
    console.info(
      `notify: event ${eventId} — ${memberIds.length} members, ${tokens.length} with a token, ${sent} pushed`,
    );

    return c.body(null, 202);
  });

  // Mount the per-device byte object routes; any unmatched path or wrong method → Hono's 404.
  deviceApi.route("/files/devices/:deviceId/:filename", byteFile);

  // Mount the device API under the versioned prefix — the one shape it is served at. Gated by the one
  // `app.use("*")` above, which normalizes the `/api/vN` prefix before its checks, so a future `/api/v2`
  // is ONE MORE MOUNT LINE here and needs no change to the gate.
  app.route("/api/v1", deviceApi);
  return app;
}
