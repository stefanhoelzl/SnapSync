// Hono app for the backend (capabilities `event-creation` + `event-limits` + `bunny-upload-endpoint` +
// `bunny-list-endpoint` + `device-config-endpoint` + `event-notify-endpoint` + `device-attestation`,
// over the shared `backend-deployment`; pushes via `apns-push-sender`).
//
// VERSIONED PREFIX (capability `backend-deployment`): every device-API route below is served under the
// prefix `/api/v1` — the paths are written that way here, and that is the one shape they answer at. The
// web/link routes (`/`, `/join`, the AASA) stay at the ROOT only, never under `/api/v1`. The routing is
// version-parametric: a future `/api/v2` is one more mount in `createApp`.
//
// EVERY ROUTE BELOW REQUIRES A DEVICE TOKEN (capability `device-attestation`) — obtainable only by
// completing App Attest, so the API is callable by a genuine, unmodified SnapSync on a genuine Apple
// device and by nothing else. Exactly four things are ungated, and the list is CLOSED: the three
// `/attest/*` routes (self-authenticating — they issue the token) and `OPTIONS` (the pull zone may answer
// the preflight itself, so the script cannot gate it). See the middleware in `createApp`.
//
//   GET /api/v1/attest/challenge
//     → a stateless, HMAC-signed, time-bounded nonce. Writes NOTHING.
//   POST /api/v1/attest/token
//     → verifies an App Attest attestation (chain → Apple's root, nonce, app-id hash, counter, aaguid),
//       persists the attested public key at `devices/<id>.attest.json`, and mints a 30-day bearer token.
//   POST /api/v1/attest/renew
//     → verifies a local Secure-Enclave ASSERTION against that stored key and mints a fresh token — no
//       Apple round-trip, because re-attestation is the throttled path.
//
//   POST /api/v1/events
//     → mints an event: writes the marker `events/<id>/metadata.json` — stamping `capacity` and the
//       `lifetimeSeconds` DURATION, and validating the creator's `endsAt` against the configured window
//       maximum (capability `event-limits`) — and returns
//       {eventId,name,createdAt,startsAt,endsAt,capacity,deletesAt}.
//   GET /api/v1/events/:eventId
//     → returns the event (existence check) with the DERIVED `deletesAt`; 404 when absent OR `gone`
//       (legacy/corrupt marker). Never deletes on touch, even past the deadline.
//   PUT /api/v1/devices/:deviceId
//     → streams a JSON device config (the push token) into `devices/<deviceId>.json`. UNGATED by
//       event; DEVICE-ID is the capability. Faithful 201/502; last-write-wins. A flat sibling of the
//       `files/devices/<deviceId>/` byte partition, so never listed as an asset.
//   POST /api/v1/events/:eventId/notify
//     → sends a fixed SILENT (content-available) push to every ACTIVE member device (a departed
//       `<id>.left.json` member is skipped). GATED on the marker (404/502). Enumerate members
//       (LIST `events/<id>/devices/`, resolve active via last-write-wins) → read each `devices/<id>.json`
//       → best-effort fan-out via APNs. Bare 202 (no per-device results); 502 only if the member LIST
//       fails. Authorized by a device token — the ONLY credential this backend accepts.
//   PUT /api/v1/files/devices/:deviceId/:filename
//     → streams the request body into ONE bunny native Storage PUT. Requires the token, but reads NO
//       marker: bytes are device-partitioned and event-independent (`files/devices/<deviceId>/<filename>`),
//       uploaded once and linked into events by reference. The device id remains self-asserted — the token
//       proves a genuine app instance, NOT ownership of the partition (a stated non-goal; the UUID is the
//       capability). The OS performs this PUT and DOES carry the header (verified on device). (There is no
//       download GET on this path — the listing hands out a presigned S3 URL fetched directly from S3.)
//   GET /api/v1/files/devices/:deviceId
//     → lists the device's RAW stored objects (a single LIST of `files/devices/<deviceId>/`); each is
//       `{ filename, size, url }` where `url` is a presigned S3 GET URL. No manifest read, no
//       completeness, no event gate. `Cache-Control: no-store, no-cache, max-age=0` (time-limited urls;
//       see NO_CACHE — the pull zone honors `no-cache`, not `no-store`).
//   PUT /api/v1/events/:eventId/devices/:deviceId
//     → streams a JSON device manifest into `events/<eventId>/devices/<deviceId>.json`. GATED on event
//       existence (the marker read) so a manifest is never written under a non-existent event, AND on
//       CAPACITY (capability `event-limits`): a device id never enrolled (no active or `.left` sibling)
//       is refused 409 once `capacity` distinct device ids have ever enrolled (leaving frees no slot);
//       a known device's writes always pass. Capacity is the ONLY refusal — enrollment is never closed
//       by time, however long after `endsAt` it arrives.
//   DELETE /api/v1/events/:eventId/devices/:deviceId
//     → LEAVE (capability `event-leave-endpoint`): RENAME-ONLY. Renames the device's active manifest to
//       `<deviceId>.left.json` (departed — still served by the union, skipped by notify) and returns 200
//       regardless of remaining membership. NON-DESTRUCTIVE: no reap here, no leave-time GC. When this
//       was the LAST active member the event becomes EMPTY, and the nightly sweep (capability
//       `scheduled-cleanup`) reclaims it on its next run. GATED on the marker (404/502). Idempotent.
//   GET /api/v1/events/:eventId/files
//     → the event-wide UNION: every contributing device's COMPLETE assets (an asset is complete iff
//       every resource its device.json names is present in `files/devices/<deviceId>/`), flattened across
//       devices, each tagged with its owning deviceId. GATED on event existence (marker read). Fans
//       out: marker → LIST `events/<id>/devices/` → per device (read device.json + LIST its files) →
//       complete-only projection. Faithful: any non-404 read failure anywhere (incl. a manifest JSON
//       parse failure) → 502 (never a partial union). `Cache-Control: no-store, no-cache, max-age=0`
//       (live read over mutable manifests + listings; see NO_CACHE). Identity-blind: own-vs-foreign
//       skip is the client's concern.
//
// EVENT LIFECYCLE (capability `event-limits`): every event-scoped route above resolves its event through
// ONE gate (`gateEvent`), and the lifecycle is BINARY — the event exists, or the sweep has deleted it.
// `endsAt` is NOT a lifecycle input: it bounds only which captures may be UPLOADED, so nothing closes
// when the window does (in particular, JOINING IS NEVER CLOSED BY TIME — a guest who scans days late
// still holds in-window captures that belong in the event).
//
// The nightly sweep (capability `scheduled-cleanup`, run out-of-edge from GitHub Actions) is the ONLY
// deleter. It reclaims an event that is past its derived delete-by (`max(createdAt, startsAt) +
// lifetimeSeconds` — the guarantee) or EMPTY (ever joined, no active member left — opportunistic, since
// a leave whose DELETE never landed keeps a manifest active). No route reaps on touch, even past the
// deadline: that is what makes a 404 a REAL deletion, and therefore safe as one of the two witnesses the
// client's self-leave requires (capability `leave-event`). A legacy/corrupt marker (missing `startsAt`,
// `endsAt`, or `capacity`) is `gone`: the gate answers 404 and the sweep deletes it.
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
  canonicalFromMs,
  canonicalPlusSeconds,
  validateEndsAt,
  validateEventName,
  validateFilename,
  validateStartsAt,
  validateUUID,
} from "./validators.ts";
import type { Config } from "./config.ts";
import { createApnsSender, type PushToken } from "./apns.ts";
import {
  b64ToBytes,
  bytesToB64,
  challengeIsValid,
  mintChallenge,
  mintToken,
  verifyAssertion,
  verifyAttestation,
  verifyToken,
} from "./attest.ts";
// Storage primitives + on-wire shapes, shared with the nightly sweep (capability `scheduled-cleanup`).
import {
  type AttestRecord,
  byteKey,
  decodeObjectName,
  deleteObject,
  deviceAttestKey,
  deviceConfigKey,
  deviceDir,
  deviceLeftManifestKey,
  deviceManifestDir,
  deviceManifestKey,
  type EventMarker,
  type FetchLike,
  listDir,
  type ManifestResource,
  markerKey,
  putObject,
  readManifestObject,
  readMarker,
  readObjectText,
} from "./storage.ts";
// Event lifecycle + membership, shared with the nightly sweep.
import {
  classifyEvent,
  deleteByMs,
  type LiveEventMarker,
  type MemberState,
  resolveMembership,
} from "./lifecycle.ts";

// Re-exported so existing importers (tests, callers) keep their `from "./app.ts"` imports working.
export { classifyEvent } from "./lifecycle.ts";
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
   * Wall clock, in epoch ms. Injected so tests can pin it — the device token and the challenge are both
   * time-bounded, and a test for "an expired token is refused" cannot wait 30 days. Defaults to `Date.now`.
   */
  now?: () => number;
};

// One file in the per-device listing response — exactly `filename`, `size`, and `url` (a closed shape).
// `filename` is the uploaded name decoded from the stored key; `size` is the object's byte length;
// `url` is a presigned S3 GET URL (per `bunny-list-endpoint`, built by `presignDownloadUrl`).
type FileEntry = {
  filename: string;
  size: number;
  url: string;
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

// (`isNotifyPath` + `constantTimeEqual` lived here to authorize the notify-only ADMIN_NOTIFY_KEY. That
// credential is retired — a device token is now the only one this backend accepts — so both are gone
// rather than left as dead code inviting a second bearer-secret path.)

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
  // sends. Used by the notify fan-out and the expiry reap's member notification. No production caller
  // is wired to notify yet (the trigger is a deferred use case).
  const apns = createApnsSender(config, fetchImpl);

  // ── THE EVENT-LIMITS GATE (capability `event-limits`) ───────────────────────────────────────────
  //
  // Every event-scoped route resolves its event through `gateEvent` below: read the marker and check it
  // is complete. The lifecycle is BINARY — an event exists, or the sweep has deleted it. `endsAt` is NOT
  // consulted: it bounds only which captures may be UPLOADED, and closes nothing. In particular JOINING
  // IS NEVER CLOSED BY TIME, because a guest who scans days late still holds in-window captures that
  // belong in the event. There is no on-touch reap: deleting is the nightly sweep's alone
  // (capability `scheduled-cleanup`), including for an event already past its derived delete-by.

  /**
   * Resolve an event for a route: marker read + completeness check (capability `event-limits`).
   * `absent` covers "never created" AND a `gone` marker (legacy/corrupt — missing `startsAt`, `endsAt`,
   * or `capacity`), which the nightly sweep deletes. THROWS on marker-read transport failure, so the
   * route surfaces 502 and never mistakes a transient failure for absence.
   */
  async function gateEvent(
    eventId: string,
  ): Promise<{ kind: "ok"; marker: LiveEventMarker } | { kind: "absent" }> {
    const stored = await readMarker(fetchImpl, config, eventId);
    if (stored === null) return { kind: "absent" };
    const cls = classifyEvent(stored);
    if (cls.phase === "gone") return { kind: "absent" }; // legacy/corrupt marker — the sweep deletes it
    return { kind: "ok", marker: cls.marker };
  }

  /**
   * The WIRE shape of an event (capabilities `event-creation`, `event-limits`): the marker's public
   * fields with the stamped `lifetimeSeconds` replaced by the DERIVED `deletesAt`, in the canonical
   * cutoff shape.
   *
   * Serving the derived instant — rather than the duration and the anchor for a client to combine —
   * keeps the anchor policy in ONE place and means no client ever holds a copy of the lifetime constant.
   * A duplicated constant would let a join gate confidently promise a date the backend will not honour,
   * and the drift would be silent.
   */
  function publicEvent(marker: LiveEventMarker) {
    const { lifetimeSeconds: _stamped, ...wire } = marker;
    return { ...wire, deletesAt: canonicalFromMs(deleteByMs(marker, config.eventLifetimeSeconds)) };
  }

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
    const publicGet = path === "/" || path === "/join" ||
      path === "/.well-known/apple-app-site-association" ||
      path.startsWith("/_astro/");
    // The two event READS the no-app download page fetches (capability `web-event-download`): the event
    // marker `/events/<id>` and the photo union `/events/<id>/files`. These are authorized by
    // eventId-possession alone — the eventId IS the read capability — so a browser that holds no attestation
    // can fetch them. This narrows the gate's READ posture (attestation never proved who may read whose
    // photos, and the presigned bytes it fronts were always ungated); it does NOT open any WRITE. The match
    // is GET/HEAD-only and shape-anchored to exactly these two paths, so every mutating `/events/<id>/…`
    // method (device manifest, leave, notify) and `POST /events` stay gated. Decision record:
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
    // are consulted HERE ONLY; enforcement reads the marker's own stamped fields.
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
    const marker: EventMarker = {
      eventId: crypto.randomUUID(),
      name,
      createdAt: new Date().toISOString(),
      startsAt,
      endsAt,
      capacity: config.eventCapacity,
      lifetimeSeconds: config.eventLifetimeSeconds,
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
    return c.json(publicEvent(marker), 201);
  });

  // Event metadata / existence (capability `event-creation`). Returns the event — always carrying
  // `startsAt`, `endsAt`, `capacity`, and the derived `deletesAt`, because the gate never serves a marker
  // without the first three — or 404 when the event was never created OR its marker is incomplete
  // (capability `event-limits`); a non-404 marker read failure → 502. This is the canonical existence
  // check the device-manifest write gate relies on.
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
      return c.json(publicEvent(gate.marker));
    } catch (e) {
      console.error(`metadata: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Write a device's per-event manifest (capability `bunny-upload-endpoint`, device-manifest route).
  // GATED on event existence AND capacity (capability `event-limits`): read the marker, then LIST
  // `events/<eventId>/devices/` to classify the writer as KNOWN (an active `<id>.json` or departed
  // `<id>.left.json` exists — a member's manifest update, or a rejoin reusing its own slot) vs NEW (an
  // enrollment). Resolution order: absent → 404; new ∧ ever-enrolled ≥ capacity → 409 (active and
  // departed both count: leaving frees no slot); otherwise stream the body into one bunny native PUT at
  // `events/<eventId>/devices/<deviceId>.json`. Any non-404 marker/LIST failure → 502 (never mistaken
  // for absent or full).
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

    let capacity: number;
    let members: { deviceId: string; state: MemberState }[];
    try {
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
      capacity = gate.marker.capacity;
      members = resolveMembership(await listDir(fetchImpl, config, deviceManifestDir(eventId)));
    } catch (e) {
      console.error(`device-manifest: gate failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    const known = members.some((m) => m.deviceId === deviceId);
    if (!known && members.length >= capacity) return c.text("event full", 409);

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

  // Leave an event (capability `event-leave-endpoint`). RENAME-ONLY: leaving is non-destructive. GATED
  // on the marker (absent → 404; non-404 read failure → 502). Rename the device's active manifest to its
  // `.left.json` sibling (copy content → FRESH timestamp, then delete the active) so the union still
  // serves its photos, then return 200 REGARDLESS of remaining membership — the event survives until it
  // expires and is deleted by the nightly sweep (capability `scheduled-cleanup`), which also collects the
  // bytes. No last-member reap, no leave-time garbage collection. Idempotent + leak-safe: the `.left.json`
  // is written BEFORE the active is deleted, so a failure between them leaves the device recoverable; a
  // missing active manifest (already departed / never a member) is a no-op, so a retried DELETE re-runs
  // harmlessly. Any transport failure → 502.
  deviceApi.delete("/events/:eventId/devices/:deviceId", async (c) => {
    const eventId = c.req.param("eventId");
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(eventId) || !validateUUID(deviceId)) {
      return c.text("invalid key", 400);
    }

    try {
      // The lifecycle gate (capability `event-limits`): a `gone` (legacy/corrupt) marker 404s, which the
      // client already treats as "nothing to leave". A leave DURING grace proceeds: members may still
      // depart an over-but-not-yet-swept event.
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
    } catch (e) {
      console.error(`leave: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

    try {
      // Departed rename. Write the `.left.json` sibling FIRST (fresh timestamp = the commit), then
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
      // Always succeed: the event persists (rejoinable) regardless of how many active members remain.
      return c.body(null, 200);
    } catch (e) {
      console.error(`leave: rename failed for ${eventId}/${deviceId}: ${e}`);
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
  deviceApi.get("/events/:eventId/files", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }

    // Gate on the marker (existence + lifecycle, capability `event-limits`): absent or expired-and-
    // reaped → 404; a non-404 read failure → 502. An event in grace still serves its union — members
    // keep full sync until expiry.
    try {
      const gate = await gateEvent(eventId);
      if (gate.kind === "absent") return c.text("event not found", 404);
    } catch (e) {
      console.error(`union: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

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
            listDir(fetchImpl, config, deviceDir(deviceId)), // empty/absent dir → [] → no bytes
          ]);

          // The device's present object names → byte length (the completeness oracle + size source).
          const present = new Map<string, number>();
          for (const e of fileEntries.filter((e) => !e.IsDirectory)) {
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
  deviceApi.get("/files/devices/:deviceId", async (c) => {
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(deviceId)) {
      return c.text("invalid device", 400);
    }
    try {
      // Single LIST of the device dir → its objects. An empty or absent dir lists as `[]`, which maps
      // to an empty array below. Any other LIST failure throws → 502, so a partial list is never
      // returned.
      const entries = await listDir(fetchImpl, config, deviceDir(deviceId));
      c.header("Cache-Control", NO_CACHE); // each `url` is a time-limited presigned S3 URL

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
  deviceApi.put("/devices/:deviceId", async (c) => {
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
      console.error(`notify: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }

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
  deviceApi.route("/files/devices/:deviceId/:filename", byteFile);

  // Mount the device API under the versioned prefix — the one shape it is served at. Gated by the one
  // `app.use("*")` above, which normalizes the `/api/vN` prefix before its checks, so a future `/api/v2`
  // is ONE MORE MOUNT LINE here and needs no change to the gate.
  app.route("/api/v1", deviceApi);
  return app;
}
