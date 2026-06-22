// Hono app for the streaming proxy upload endpoint (capability `bunny-upload-endpoint`).
//
//   PUT /event/:eventId/device/:deviceId/file/:filename
//     → streams the request body into ONE bunny native Storage PUT.
//
// The route is defined once on a child Hono and mounted under the upload path via app.route(), so
// PUT and OPTIONS share it. `eventId`/`deviceId`/`filename` are Hono's decoded path params (typed
// `string | undefined` through a mount, hence the guard); the filename is re-encoded per-segment
// when building the bunny URL, so the stored object is the real filename and keys stay flat. Config
// is injected (validated at startup), so the handler has no config path. Invariants: pass-through
// only (never buffer/hash), faithful outcome (2xx only on confirmed store), last-write-wins.

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

  // Mount once under the upload path; any unmatched path or wrong method → Hono's 404.
  const app = new Hono();
  app.route("/event/:eventId/device/:deviceId/file/:filename", upload);
  return app;
}
