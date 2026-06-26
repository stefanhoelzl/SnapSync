// Hono app for the backend (capabilities `bunny-upload-endpoint` + `bunny-list-endpoint`).
//
//   PUT /event/:eventId/device/:deviceId/file/:filename
//     → streams the request body into ONE bunny native Storage PUT.
//   GET /event/:eventId/files
//     → lists every stored object for the event, flat across all devices.
//
// The upload route is defined once on a child Hono and mounted under the upload path via
// app.route(), so PUT and OPTIONS share it. `eventId`/`deviceId`/`filename` are Hono's decoded path
// params (typed `string | undefined` through a mount, hence the guard); the filename is re-encoded
// per-segment when building the bunny URL, so the stored object is the real filename and keys stay
// flat. Config is injected (validated at startup), so the handler has no config path. Invariants:
// pass-through only (never buffer/hash), faithful outcome (2xx only on confirmed store),
// last-write-wins.
//
// The list route reads instead: bunny native Storage LIST is per-directory (non-recursive), so it
// fans out — list `<eventId>/` for device dirs, then each `<eventId>/<deviceId>/` for files — and
// flattens. Faithful too: any genuine LIST failure → 502 (never a partial list); a 404 on the event
// dir is "no objects" → 200 [] (empty/unknown event are indistinguishable without a registry).

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

// One normalized object in our response — the four fields the contract promises. `deviceId` is the
// directory the file was found under (not a bunny field); `contentType` is intentionally absent
// (bunny's canonical List schema doesn't return it, and the consumer only needs the filename).
type FileEntry = {
  filename: string;
  deviceId: string;
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

export function createApp({ fetch: fetchImpl, config }: Deps): Hono {
  const upload = new Hono();

  upload.put("/", async (c) => {
    const eventId = c.req.param("eventId");
    const deviceId = c.req.param("deviceId");
    const filename = c.req.param("filename");
    if (
      !eventId || !deviceId || !filename ||
      !validateUUID(eventId) || !validateUUID(deviceId) || !validateFilename(filename)
    ) {
      return c.text("invalid key", 400);
    }
    // eventId/deviceId are UUIDs (encoding is identity); encode the filename so the key stays a
    // single flat segment on the wire.
    const storageKey = `${eventId}/${deviceId}/${encodeURIComponent(filename)}`;

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

  // List every stored object for an event, flat across all devices (capability
  // `bunny-list-endpoint`). Authorization is the event id alone (same as upload). A non-UUID id →
  // 400; any other method / unmatched path → Hono's 404.
  app.get("/event/:eventId/files", async (c) => {
    const eventId = c.req.param("eventId");
    if (!validateUUID(eventId)) {
      return c.text("invalid event", 400);
    }
    try {
      // 1) List the event dir → device sub-directories. 404/absent → no objects → [].
      const top = await listDir(fetchImpl, config, `${encodeURIComponent(eventId)}/`);
      if (top === null) return c.json([] as FileEntry[]);
      const deviceIds = top
        .filter((e) => e.IsDirectory)
        .map((e) => e.ObjectName.replace(/\/$/, "")); // some bunny responses suffix dir names with /

      // 2) List each device dir → files; flatten. Concurrent — any genuine failure rejects the
      //    whole walk (→ 502), so a partial list is never returned.
      const perDevice = await Promise.all(
        deviceIds.map(async (deviceId): Promise<FileEntry[]> => {
          const entries = await listDir(
            fetchImpl,
            config,
            `${encodeURIComponent(eventId)}/${encodeURIComponent(deviceId)}/`,
          );
          if (entries === null) return []; // device dir vanished mid-walk → contributes nothing
          return entries
            .filter((e) => !e.IsDirectory)
            .map((e) => ({
              filename: e.ObjectName,
              deviceId,
              size: e.Length,
              lastModified: e.LastChanged ?? e.DateLastModified ?? null,
            }));
        }),
      );
      return c.json(perDevice.flat());
    } catch (e) {
      console.error(`list: bunny LIST failed for event ${eventId}: ${e}`);
      return c.text("upstream error", 502);
    }
  });

  // Mount once under the upload path; any unmatched path or wrong method → Hono's 404.
  app.route("/event/:eventId/device/:deviceId/file/:filename", upload);
  return app;
}
