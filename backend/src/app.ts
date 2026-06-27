// Hono app for the backend (capabilities `bunny-upload-endpoint` + `bunny-list-endpoint`).
//
//   PUT /event/:eventId/file/:filename
//     → streams the request body into ONE bunny native Storage PUT.
//   GET /event/:eventId/files
//     → lists every stored object for the event.
//
// The upload route is defined once on a child Hono and mounted under the upload path via
// app.route(), so PUT and OPTIONS share it. `eventId`/`filename` are Hono's decoded path
// params (typed `string | undefined` through a mount, hence the guard); the filename is re-encoded
// per-segment when building the bunny URL, so the stored object is the real filename and keys stay
// flat. Config is injected (validated at startup), so the handler has no config path. Invariants:
// pass-through only (never buffer/hash), faithful outcome (2xx only on confirmed store),
// last-write-wins.
//
// The list route reads instead: files live directly under `<eventId>/` (flat key), so a single
// bunny native Storage LIST of the event dir returns them. Faithful too: any genuine LIST failure →
// 502 (never a partial list); a 404 on the event dir is "no objects" → 200 [] (empty/unknown event
// are indistinguishable without a registry).

import { Hono } from "hono";
import { validateFilename, validateUUID } from "./validators.ts";
import type { Config } from "./config.ts";

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

// One normalized object in our response — the three fields the contract promises. `contentType` is
// intentionally absent (bunny's canonical List schema doesn't return it, and the consumer only needs
// the filename).
type FileEntry = {
  filename: string;
  size: number;
  lastModified: string | null;
};

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

export function createApp({ fetch: fetchImpl, config }: Deps): Hono {
  const upload = new Hono();

  upload.put("/", async (c) => {
    const eventId = c.req.param("eventId");
    const filename = c.req.param("filename");
    if (
      !eventId || !filename ||
      !validateUUID(eventId) || !validateFilename(filename)
    ) {
      return c.text("invalid key", 400);
    }
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

  // OPTIONS: do NOT advertise resumable uploads → the iOS uploader falls back to a plain PUT.
  upload.options("/", (c) => {
    c.header("Allow", "PUT, OPTIONS");
    return c.body(null, 204);
  });

  const app = new Hono();

  // List every stored object for an event (capability `bunny-list-endpoint`). Files live directly
  // under `<eventId>/` (flat key), so a single LIST of the event dir returns them. Authorization is
  // the event id alone (same as upload). A non-UUID id → 400; any other method / unmatched path →
  // Hono's 404.
  app.get("/event/:eventId/files", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }
    try {
      // Single LIST of the event dir → its files. 404/absent → no objects → []. Any other failure
      // throws → 502, so a partial list is never returned.
      const entries = await listDir(fetchImpl, config, `${encodeURIComponent(eventId)}/`);
      if (entries === null) return c.json([] as FileEntry[]);
      const files: FileEntry[] = entries
        .filter((e) => !e.IsDirectory)
        .map((e) => ({
          filename: decodeObjectName(e.ObjectName),
          size: e.Length,
          lastModified: e.LastChanged ?? e.DateLastModified ?? null,
        }));
      return c.json(files);
    } catch (e) {
      console.error(`list: bunny LIST failed for event ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Mount once under the upload path; any unmatched path or wrong method → Hono's 404.
  app.route("/event/:eventId/file/:filename", upload);
  return app;
}
