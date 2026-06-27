// Hono app for the backend (capabilities `event-creation` + `bunny-upload-endpoint` +
// `bunny-list-endpoint` + `bunny-download-endpoint`, over the shared `backend-config`).
//
//   POST /event
//     → mints an event: writes the marker `events/<id>.json`, returns {eventId,name,createdAt}.
//   GET /event/:eventId
//     → returns the event marker (existence check); 404 when absent.
//   PUT /event/:eventId/file/:filename
//     → streams the request body into ONE bunny native Storage PUT (gated on event existence).
//   GET /event/:eventId/file/:filename
//     → streams ONE bunny native Storage GET of the object straight back. NOT gated on the marker:
//       a missing object and an unknown event are indistinguishably 404 by design (see below).
//   GET /event/:eventId/files
//     → lists every stored object for the event (gated on event existence); each entry carries a
//       `url`, the absolute download URL for that object.
//
// EVENT REGISTRY: an event exists iff the object `events/<id>.json` is present. The `events/` prefix
// is disjoint from any event's photo dir `<id>/` (an eventId is a UUID, never the literal "events"),
// so the marker never appears in a per-event listing and never collides with a photo. List and upload
// both read the marker first (a `GET`, since bunny's Edge Storage API has no HEAD) and 404 when it is
// absent; a non-404 read failure surfaces as 502 (a transient failure is never mistaken for absence).
//
// The per-object routes are defined once on a child Hono (`file`) and mounted under
// `/event/:eventId/file/:filename` via app.route(), so PUT (upload), OPTIONS, and GET (download)
// share it. `eventId`/`filename` are Hono's decoded path params (typed `string | undefined` through
// a mount, hence the guard); the filename is re-encoded per-segment when building the bunny URL, so
// the stored object is the real filename and keys stay flat. Config is injected (validated at
// startup), so the handlers have no config path. Upload invariants: pass-through only (never
// buffer/hash), faithful outcome (2xx only on confirmed store), last-write-wins. Download is
// ungated: it issues a single object GET and streams the body through; bunny 200 → 200, bunny 404 →
// 404 (missing object and unknown event collapse here — see the read-faithfulness note below), any
// other status / connect error / pre-body timeout → 502. Read-faithfulness is narrower than the
// upload's: status+headers commit before the body, so a mid-body upstream abort surfaces as a
// truncated 200 (not a 5xx); the relayed Content-Length makes that a client-detectable short-read.
//
// The list route reads instead: files live directly under `<eventId>/` (flat key), so a single
// bunny native Storage LIST of the event dir returns them. Faithful too: any genuine LIST failure →
// 502 (never a partial list); a 404 on the event dir is "no objects" → 200 [] (empty/unknown event
// are indistinguishable without a registry).

import { Hono } from "hono";
import { validateEventName, validateFilename, validateUUID } from "./validators.ts";
import type { Config } from "./config.ts";

// The event registry's marker prefix. Disjoint from any `<eventId>/` photo dir because an eventId is
// a UUID and can never be the literal string `events`.
const MARKER_PREFIX = "events";

/** Storage key of an event's marker object: `events/<eventId>.json`. */
function markerKey(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}.json`;
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

// A single entry from bunny's native Storage "List Files" response. We read only these fields; the
// timestamp field name differs across bunny's own docs (`LastChanged` vs `DateLastModified`), so we
// accept either. Everything else (Guid, ServerId, …) is ignored.
type BunnyEntry = {
  ObjectName: string;
  Length: number;
  IsDirectory: boolean;
  LastChanged?: string;
  DateLastModified?: string;
};

// One normalized object in our response — the four fields the contract promises (a closed shape).
// `url` is the absolute download URL (per `bunny-download-endpoint`); the storage key itself stays
// hidden. `contentType` is intentionally absent (bunny's canonical List schema doesn't return it).
type FileEntry = {
  filename: string;
  size: number;
  lastModified: string | null;
  url: string;
};

/**
 * The public download URL for a stored object: `<baseUrl>/event/<eventId>/file/<filename>`, each path
 * segment percent-encoded so the filename stays a single flat segment (eventId is a UUID, so its
 * encoding is identity). This is the sole builder of that URL — the list endpoint uses it and the
 * download route serves the matching path, so they agree by construction.
 */
function downloadUrl(config: Config, eventId: string, filename: string): string {
  return `${config.baseUrl}/event/${encodeURIComponent(eventId)}/file/${
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
 * tiny, and the same read serves the `GET /event/:eventId` metadata response.
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

export function createApp({ fetch: fetchImpl, config }: Deps): Hono {
  const file = new Hono();

  file.put("/", async (c) => {
    const eventId = c.req.param("eventId");
    const filename = c.req.param("filename");
    if (
      !eventId || !filename ||
      !validateUUID(eventId) || !validateFilename(filename)
    ) {
      return c.text("invalid key", 400);
    }

    // Gate on event existence: read the marker before streaming a single byte. Absent → 404 (no
    // upstream object PUT); a non-404 read failure → 502 (never mistaken for absence). The check reads
    // the EVENT marker, not the object key, so last-write-wins on the object write is unchanged.
    let marker: EventMarker | null;
    try {
      marker = await readMarker(fetchImpl, config, eventId);
    } catch (e) {
      console.error(`upload: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (marker === null) return c.text("event not found", 404);

    // eventId is a UUID (encoding is identity); encode the filename so the key stays a single flat
    // segment on the wire.
    const storageKey = `${eventId}/${encodeURIComponent(filename)}`;

    const target = `https://${config.host}/${config.zone}/${storageKey}`;
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
      console.error(`upload: upstream PUT errored for ${storageKey}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (!upstream.ok) {
      console.error(`upload: bunny returned ${upstream.status} for ${storageKey}`);
      return c.text("upstream rejected", 502);
    }
    // Bunny confirmed the stored object — and only now do we report success.
    return c.body(null, 201);
  });

  // Download (capability `bunny-download-endpoint`). A single bunny object GET, streamed straight
  // back. UNGATED — no marker read: the object GET already yields faithful absence (bunny 404 → 404),
  // so an unknown event and a missing object are indistinguishably 404 by design. Faithful read is
  // narrower than the upload's: status+headers commit before the body, so a mid-body upstream abort
  // is a truncated 200 (not a 5xx); the relayed Content-Length makes it a client-detectable short-read.
  file.get("/", async (c) => {
    const eventId = c.req.param("eventId");
    const filename = c.req.param("filename");
    if (
      !eventId || !filename ||
      !validateUUID(eventId) || !validateFilename(filename)
    ) {
      return c.text("invalid key", 400);
    }

    // Same flat key the upload route writes: encode the filename per-segment (eventId is a UUID).
    const storageKey = `${eventId}/${encodeURIComponent(filename)}`;
    const target = `https://${config.host}/${config.zone}/${storageKey}`;

    let upstream: Response;
    try {
      upstream = await fetchImpl(target, {
        method: "GET",
        headers: { AccessKey: config.accessKey },
      });
    } catch (e) {
      console.error(`download: upstream GET errored for ${storageKey}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (upstream.status === 404) return c.text("not found", 404); // missing object OR unknown event
    if (!upstream.ok) {
      console.error(`download: bunny returned ${upstream.status} for ${storageKey}`);
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
  file.options("/", (c) => {
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
  // check the list and upload gates rely on.
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

  // List every stored object for an event (capability `bunny-list-endpoint`). Files live directly
  // under `<eventId>/` (flat key), so a single LIST of the event dir returns them. Authorization is
  // the event id alone (same as upload). A non-UUID id → 400; any other method / unmatched path →
  // Hono's 404.
  app.get("/event/:eventId/files", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }
    // Gate on event existence: an unknown event → 404 (no LIST); a non-404 marker read failure → 502.
    // A created event with no objects still returns 200 [] below (existence ≠ emptiness).
    let marker: EventMarker | null;
    try {
      marker = await readMarker(fetchImpl, config, eventId);
    } catch (e) {
      console.error(`list: marker read failed for ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
    if (marker === null) return c.text("event not found", 404);
    try {
      // Single LIST of the event dir → its files. 404/absent → no objects → []. Any other failure
      // throws → 502, so a partial list is never returned.
      const entries = await listDir(fetchImpl, config, `${encodeURIComponent(eventId)}/`);
      if (entries === null) return c.json([] as FileEntry[]);
      const files: FileEntry[] = entries
        .filter((e) => !e.IsDirectory)
        .map((e) => {
          const filename = decodeObjectName(e.ObjectName);
          return {
            filename,
            size: e.Length,
            lastModified: e.LastChanged ?? e.DateLastModified ?? null,
            url: downloadUrl(config, eventId, filename),
          };
        });
      return c.json(files);
    } catch (e) {
      console.error(`list: bunny LIST failed for event ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Mount once under the per-object path; any unmatched path or wrong method → Hono's 404.
  app.route("/event/:eventId/file/:filename", file);
  return app;
}
