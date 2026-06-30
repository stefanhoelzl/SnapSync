// Hono app for the backend (capabilities `event-creation` + `bunny-upload-endpoint` +
// `bunny-list-endpoint` + `bunny-download-endpoint`, over the shared `backend-config`).
//
//   POST /event
//     → mints an event: writes the marker `events/<id>/metadata.json`, returns {eventId,name,createdAt}.
//   GET /event/:eventId
//     → returns the event marker (existence check); 404 when absent.
//   PUT /files/device/:deviceId/:filename
//     → streams the request body into ONE bunny native Storage PUT. UNGATED (no marker read): bytes
//       are device-partitioned and event-independent (`files/<deviceId>/<filename>`), uploaded once and
//       linked into events by reference. The device id is self-asserted (accepted abuse trade-off — see
//       `bunny-upload-endpoint` §8; App Attest is the hardening path).
//   GET /files/device/:deviceId/:filename
//     → streams ONE bunny native Storage GET of the object straight back. UNGATED: a missing object is
//       plain 404 (no event concept on this per-device route).
//   GET /files/device/:deviceId
//     → lists the device's RAW stored objects (a single LIST of `files/<deviceId>/`); each entry is
//       `{ filename, size, url }`. No manifest read, no completeness, no event gate.
//   PUT /event/:eventId/device/:deviceId
//     → streams a JSON device manifest into `events/<eventId>/device/<deviceId>.json`. GATED on event
//       existence (the marker read) so a manifest is never written under a non-existent event.
//   GET /event/:eventId/files
//     → the event-wide UNION: every contributing device's COMPLETE assets (an asset is complete iff
//       every resource its device.json names is present in `files/<deviceId>/`), flattened across
//       devices, each tagged with its owning deviceId. GATED on event existence (marker read). Fans
//       out: marker → LIST `events/<id>/device/` → per device (read device.json + LIST its files) →
//       complete-only projection. Faithful: any non-404 read failure anywhere (incl. a manifest JSON
//       parse failure) → 502 (never a partial union). `Cache-Control: no-store` (live read over
//       mutable manifests + listings). Identity-blind: own-vs-foreign skip is the client's concern.
//
// EVENT REGISTRY: an event exists iff the object `events/<id>/metadata.json` is present. Because an
// eventId is a UUID, the marker key `events/<id>/metadata.json`, the device-manifest keys
// `events/<id>/device/<deviceId>.json`, and the device-global byte store `files/<deviceId>/…` are
// mutually disjoint and never collide. Existence is a small `GET` of the marker (bunny's Edge Storage
// API has no HEAD); a non-404 read failure surfaces as 502 (a transient failure is never mistaken for
// absence). Only the device-manifest write, the metadata route, and the event-wide union read the
// marker — the byte upload/download and per-device list routes are event-independent and ungated.
//
// The per-object byte routes are defined once on a child Hono (`byteFile`) and mounted under
// `/files/device/:deviceId/:filename` via app.route(), so PUT (upload), OPTIONS, and GET (download)
// share it. `deviceId`/`filename` are Hono's decoded path params (typed `string | undefined` through a
// mount, hence the guard); the filename is re-encoded per-segment when building the bunny URL, so the
// stored object is the real filename and keys stay flat. Config is injected (validated at startup).
// Upload invariants: pass-through only (never buffer/hash), faithful outcome (2xx only on confirmed
// store), last-write-wins. Download is ungated: bunny 200 → 200, bunny 404 → 404, any other status /
// connect error / pre-body timeout → 502; status+headers commit before the body, so a mid-body abort
// is a truncated 200 the relayed Content-Length makes a client-detectable short-read.
//
// The list route returns the device's raw objects from a single bunny native Storage LIST of
// `files/<deviceId>/` — no manifest content reads. Completeness is computed by the app (the shared
// gallery enumeration seam × this raw list), not server-side. Faithful: any LIST transport failure →
// 502 (never a partial list); a 404 on the device dir is "no objects" → 200 [].

import { Hono } from "hono";
import { validateEventName, validateFilename, validateUUID } from "./validators.ts";
import type { Config } from "./config.ts";

// The event registry's marker prefix. Because an eventId is a UUID, the marker
// `events/<id>/metadata.json` is disjoint from any device manifest `events/<id>/device/<deviceId>.json`
// and from the byte store `files/<deviceId>/…`.
const MARKER_PREFIX = "events";

/** Storage key of an event's marker object: `events/<eventId>/metadata.json`. */
function markerKey(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/metadata.json`;
}

/** Storage key of a device's per-event manifest: `events/<eventId>/device/<deviceId>.json`. */
function deviceManifestKey(eventId: string, deviceId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/device/${
    encodeURIComponent(deviceId)
  }.json`;
}

/** The per-event device-manifest directory to LIST: `events/<eventId>/device/`. */
function deviceManifestDir(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/device/`;
}

/** Storage key of a stored resource byte object: `files/<deviceId>/<filename>`. */
function byteKey(deviceId: string, filename: string): string {
  return `files/${encodeURIComponent(deviceId)}/${encodeURIComponent(filename)}`;
}

/** The device byte-store directory to LIST: `files/<deviceId>/`. */
function deviceDir(deviceId: string): string {
  return `files/${encodeURIComponent(deviceId)}/`;
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
// `url` is the absolute download URL (per `bunny-download-endpoint`).
type FileEntry = {
  filename: string;
  size: number;
  url: string;
};

// The on-storage device manifest (`device-manifest`), after the `key`/`filename` rename. We read only
// these fields; the union projects them straight through. A resource's `key` is its storage object
// name (`files/<deviceId>/<key>`, the fetch handle); `filename` is the human capture name.
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
// — each a manifest resource plus its `size` (from the device's file listing) and absolute `url`.
type UnionResource = ManifestResource & { size: number; url: string };
type UnionAsset = {
  deviceId: string;
  assetId: string;
  creationDate: string;
  resources: UnionResource[];
};

/**
 * The public download URL for a stored object: `<baseUrl>/files/device/<deviceId>/<filename>`, each
 * path segment percent-encoded so the filename stays a single flat segment (deviceId is a UUID, so its
 * encoding is identity). This is the sole builder of that URL — the per-device list uses it and the
 * download route serves the matching path, so they agree by construction.
 */
function downloadUrl(config: Config, deviceId: string, filename: string): string {
  return `${config.baseUrl}/files/device/${encodeURIComponent(deviceId)}/${
    encodeURIComponent(filename)
  }`;
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
 * tiny, and the same read serves the `GET /event/:eventId` metadata response and the device-manifest
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
 * Read one device's per-event manifest object (`events/<eventId>/device/<deviceId>.json`). THROWS on
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

export function createApp({ fetch: fetchImpl, config }: Deps): Hono {
  // Per-device byte object routes (`bunny-upload-endpoint` / `bunny-download-endpoint`). Mounted under
  // `/files/device/:deviceId/:filename`, so the handlers read `deviceId`/`filename` from the mount.
  const byteFile = new Hono();

  // Upload — UNGATED. No marker read: bytes are device-partitioned and event-independent. Stream the
  // body straight into one bunny native PUT at `files/<deviceId>/<filename>`. Faithful: 201 only on a
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

  // Download (capability `bunny-download-endpoint`). A single bunny object GET, streamed straight back.
  // UNGATED — no marker read: the object GET already yields faithful absence (bunny 404 → 404). Faithful
  // read is narrower than the upload's: status+headers commit before the body, so a mid-body upstream
  // abort is a truncated 200 (not a 5xx); the relayed Content-Length makes it a client-detectable
  // short-read.
  byteFile.get("/", async (c) => {
    const deviceId = c.req.param("deviceId");
    const filename = c.req.param("filename");
    if (
      !deviceId || !filename ||
      !validateUUID(deviceId) || !validateFilename(filename)
    ) {
      return c.text("invalid key", 400);
    }

    const target = `https://${config.host}/${config.zone}/${byteKey(deviceId, filename)}`;

    let upstream: Response;
    try {
      upstream = await fetchImpl(target, {
        method: "GET",
        headers: { AccessKey: config.accessKey },
      });
    } catch (e) {
      console.error(`download: upstream GET errored for ${byteKey(deviceId, filename)}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (upstream.status === 404) return c.text("not found", 404); // missing object
    if (!upstream.ok) {
      console.error(
        `download: bunny returned ${upstream.status} for ${byteKey(deviceId, filename)}`,
      );
      return c.text("upstream error", 502);
    }

    // 200: stream the body through and relay the content + cache-validator headers. Content-Length is
    // relayed so a truncated stream is a client-detectable short-read.
    const headers = new Headers();
    headers.set("Content-Type", upstream.headers.get("content-type") ?? "application/octet-stream");
    for (const name of ["content-length", "etag", "last-modified", "cache-control"]) {
      const value = upstream.headers.get(name);
      if (value !== null) headers.set(name, value);
    }
    return new Response(upstream.body, { status: 200, headers });
  });

  // OPTIONS: do NOT advertise resumable uploads → the iOS uploader falls back to a plain PUT.
  byteFile.options("/", (c) => {
    c.header("Allow", "GET, PUT, OPTIONS");
    return c.body(null, 204);
  });

  const app = new Hono();

  // Create an event (capability `event-creation`). Open (no token, matching the possession-is-
  // capability model). Validates the name, mints a server-side UUID, and writes the marker. Faithful
  // outcome: 201 only after bunny confirms the marker store; any upstream failure → 502.
  app.post("/event", async (c) => {
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
  app.get("/event/:eventId", async (c) => {
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
  // streamed straight into one bunny native PUT at `events/<eventId>/device/<deviceId>.json`.
  app.put("/event/:eventId/device/:deviceId", async (c) => {
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
  // absent → 404, non-404 read failure → 502. Then fan out: one LIST of `events/<eventId>/device/` to
  // discover the contributing devices, and per device (in parallel) read its `device.json` and LIST
  // its `files/<deviceId>/` partition. An asset is emitted only when EVERY resource its manifest names
  // is present in that device's byte store (complete-only); each kept asset is flattened into one
  // array, tagged with its owning deviceId (the endpoint is identity-blind — own-vs-foreign skip is
  // the client's concern). The stored manifest is already the event's date-filtered projection, so its
  // asset list is trusted as-is (no re-filtering). Faithful: any non-404 read failure anywhere in the
  // fan-out (incl. a manifest JSON parse failure) → 502, never a partial union; a per-device file dir
  // 404 is "no bytes" (every asset incomplete), not a failure. The 200 response is non-cacheable.
  app.get("/event/:eventId/files", async (c) => {
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

        return (manifest.assets ?? [])
          .filter((a) => a.resources.length > 0 && a.resources.every((r) => present.has(r.key)))
          .map((a) => ({
            deviceId,
            assetId: a.assetId,
            creationDate: a.creationDate,
            resources: a.resources.map((r) => ({
              role: r.role,
              contentType: r.contentType,
              key: r.key,
              filename: r.filename,
              size: present.get(r.key)!,
              url: downloadUrl(config, deviceId, r.key),
            })),
          }));
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
  app.get("/files/device/:deviceId", async (c) => {
    const deviceId = c.req.param("deviceId");
    if (!validateUUID(deviceId)) {
      return c.text("invalid device", 400);
    }
    try {
      // Single LIST of the device dir → its objects. 404/absent → no objects → []. Any other LIST
      // failure throws → 502, so a partial list is never returned.
      const entries = await listDir(fetchImpl, config, deviceDir(deviceId));
      if (entries === null) return c.json([] as FileEntry[]);

      const files: FileEntry[] = entries
        .filter((e) => !e.IsDirectory)
        .map((e) => {
          const filename = decodeObjectName(e.ObjectName);
          return { filename, size: e.Length, url: downloadUrl(config, deviceId, filename) };
        });
      return c.json(files);
    } catch (e) {
      console.error(`list: bunny LIST failed for device ${deviceId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Mount the per-device byte object routes; any unmatched path or wrong method → Hono's 404.
  app.route("/files/device/:deviceId/:filename", byteFile);
  return app;
}
