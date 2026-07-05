// Hono app for the backend (capabilities `event-creation` + `bunny-upload-endpoint` +
// `bunny-list-endpoint` + `device-config-endpoint` + `event-notify-endpoint`, over the shared
// `backend-config`; pushes via `apns-push-sender`).
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
//     → sends a fixed SILENT (content-available) push to EVERY member device. GATED on the marker
//       (404/502). Enumerate members (LIST `events/<id>/devices/`) → read each `devices/<id>.json`
//       → best-effort fan-out via APNs. Bare 202 (no per-device results); 502 only if the member LIST
//       fails. No production caller wired (the trigger is a deferred use case).
//   PUT /files/devices/:deviceId/:filename
//     → streams the request body into ONE bunny native Storage PUT. UNGATED (no marker read): bytes
//       are device-partitioned and event-independent (`files/devices/<deviceId>/<filename>`), uploaded
//       once and linked into events by reference. The device id is self-asserted (accepted abuse
//       trade-off — see `bunny-upload-endpoint` §8; App Attest is the hardening path). (There is no
//       download GET on this path — the listing hands out a presigned S3 URL fetched directly from S3.)
//   GET /files/devices/:deviceId
//     → lists the device's RAW stored objects (a single LIST of `files/devices/<deviceId>/`); each is
//       `{ filename, size, url }` where `url` is a presigned S3 GET URL. No manifest read, no
//       completeness, no event gate. `Cache-Control: no-store` (the urls are time-limited).
//   PUT /events/:eventId/devices/:deviceId
//     → streams a JSON device manifest into `events/<eventId>/devices/<deviceId>.json`. GATED on event
//       existence (the marker read) so a manifest is never written under a non-existent event.
//   GET /events/:eventId/files
//     → the event-wide UNION: every contributing device's COMPLETE assets (an asset is complete iff
//       every resource its device.json names is present in `files/devices/<deviceId>/`), flattened across
//       devices, each tagged with its owning deviceId. GATED on event existence (marker read). Fans
//       out: marker → LIST `events/<id>/devices/` → per device (read device.json + LIST its files) →
//       complete-only projection. Faithful: any non-404 read failure anywhere (incl. a manifest JSON
//       parse failure) → 502 (never a partial union). `Cache-Control: no-store` (live read over
//       mutable manifests + listings). Identity-blind: own-vs-foreign skip is the client's concern.
//
// EVENT REGISTRY: an event exists iff the object `events/<id>/metadata.json` is present. Because an
// eventId is a UUID, the marker key `events/<id>/metadata.json`, the device-manifest keys
// `events/<id>/devices/<deviceId>.json`, and the device-global byte store `files/devices/<deviceId>/…`
// are mutually disjoint and never collide. Existence is a small `GET` of the marker (bunny's Edge Storage
// API has no HEAD); a non-404 read failure surfaces as 502 (a transient failure is never mistaken for
// absence). Only the device-manifest write, the metadata route, and the event-wide union read the
// marker — the byte upload/download and per-device list routes are event-independent and ungated.
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
import { validateEventName, validateFilename, validateUUID } from "./validators.ts";
import type { Config } from "./config.ts";
import { createApnsSender, type PushToken } from "./apns.ts";

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

/** The event marker's contents — the registry record written on create. */
type EventMarker = {
  eventId: string;
  name: string;
  createdAt: string;
};

export type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

// RequestInit + the streaming-body flag required when `body` is a ReadableStream.
type StreamInit = RequestInit & { duplex?: "half" };

export type Deps = {
  /** Upstream fetch (global fetch in production; a fake in tests). */
  fetch: FetchLike;
  /** Validated storage config (built at startup via readConfig). */
  config: Config;
};

// A single entry from bunny's native Storage "List Files" response. We read only these fields;
// everything else (Guid, ServerId, the last-modified timestamps, …) is ignored.
type BunnyEntry = {
  ObjectName: string;
  Length: number;
  IsDirectory: boolean;
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
  return await res.json() as EventMarker;
}

/**
 * Read one device's per-event manifest object (`events/<eventId>/devices/<deviceId>.json`). THROWS on
 * any non-OK status, network error, abort, OR a JSON parse failure — so a faulty manifest fails the
 * whole union faithfully (`502`) rather than silently dropping a contributor. The caller only invokes
 * this for devices already discovered by the directory LIST, so the object is expected to exist (a
 * `404` here is a race and is treated as a failure, not absence).
 */
async function readDeviceManifest(
  fetchImpl: FetchLike,
  config: Config,
  eventId: string,
  deviceId: string,
): Promise<DeviceManifest> {
  const url = `https://${config.host}/${config.zone}/${deviceManifestKey(eventId, deviceId)}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (!res.ok) {
    throw new Error(`bunny device-manifest GET returned ${res.status} for ${eventId}/${deviceId}`);
  }
  return await res.json() as DeviceManifest;
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

export function createApp({ fetch: fetchImpl, config }: Deps): Hono {
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

  // Create an event (capability `event-creation`). Open (no token, matching the possession-is-
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
    // The server is the source of truth for existence, so it mints the id; any client-supplied id is
    // ignored (we only read `name` above).
    const marker: EventMarker = {
      eventId: crypto.randomUUID(),
      name,
      createdAt: new Date().toISOString(),
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
      // means no contributors → an empty union.
      const manifestEntries = await listDir(fetchImpl, config, deviceManifestDir(eventId));
      const deviceIds = (manifestEntries ?? [])
        .filter((e) => !e.IsDirectory && e.ObjectName.endsWith(".json"))
        .map((e) => decodeObjectName(e.ObjectName).slice(0, -".json".length));

      // Per-device fan-out (parallel): read the manifest AND list the device's byte partition, then
      // keep only complete assets. Any rejection here is caught below → 502 (never a partial union).
      const perDevice = await Promise.all(deviceIds.map(async (deviceId): Promise<UnionAsset[]> => {
        const [manifest, fileEntries] = await Promise.all([
          readDeviceManifest(fetchImpl, config, eventId, deviceId),
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
      }));

      c.header("Cache-Control", "no-store"); // live read over mutable manifests + listings
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
      c.header("Cache-Control", "no-store"); // each `url` is a time-limited presigned S3 URL
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

    let memberIds: string[];
    try {
      const entries = await listDir(fetchImpl, config, deviceManifestDir(eventId));
      memberIds = (entries ?? [])
        .filter((e) => !e.IsDirectory && e.ObjectName.endsWith(".json"))
        .map((e) => decodeObjectName(e.ObjectName).slice(0, -".json".length));
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
