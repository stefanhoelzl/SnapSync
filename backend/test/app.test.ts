import { assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";

const E = "7a3f9c21-0000-4000-8000-000000000001";
const D = "9c21aa00-0000-4000-8000-000000000002";
const PATH = `/event/${E}/device/${D}/file/IMG_0001-photo.jpg`;
const URLBASE = "https://edge.example";

const CONFIG = { zone: "snapsync-zone", host: "storage.bunnycdn.com", accessKey: "zone-password" };

type Call = { url: string; init: RequestInit };

/** Records upstream calls and returns a fixed Response (or throws if `throws`). */
function recorder(opts: { status?: number; throws?: boolean } = {}) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    if (opts.throws) return Promise.reject(new Error("network boom"));
    return Promise.resolve(new Response(null, { status: opts.status ?? 201 }));
  };
  return { calls, fetchImpl };
}

Deno.test("valid PUT → forwards once with bare key, AccessKey, content-type, body", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "PUT",
    body: "hello-bytes",
    headers: { "content-type": "image/jpeg" },
  });

  assertEquals(res.status, 201);
  assertEquals(calls.length, 1); // exactly one upstream subrequest
  const call = calls[0];
  assertEquals(call.init.method, "PUT");
  assertEquals(call.url, `https://storage.bunnycdn.com/snapsync-zone/${E}/${D}/IMG_0001-photo.jpg`);
  const h = new Headers(call.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "image/jpeg");
  const sent = await new Response(call.init.body as BodyInit).text();
  assertEquals(sent, "hello-bytes");
});

Deno.test("encoded filename round-trips to an encoded, flat key on the wire", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/${E}/device/${D}/file/IMG%20001.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 201);
  // Hono decodes the param ("IMG 001.jpg"); the key re-encodes it → bunny gets a single flat segment
  // (not a decoded space, not a split path).
  assertEquals(calls[0].url, `https://storage.bunnycdn.com/snapsync-zone/${E}/${D}/IMG%20001.jpg`);
});

Deno.test("encoded slash (%2F) in filename → 400 (would un-flatten the key)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/${E}/device/${D}/file/a%2Fb.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("missing content-type defaults to application/octet-stream", async () => {
  const { calls, fetchImpl } = recorder();
  // A typed-array body does NOT auto-set a content-type (a string body would set text/plain).
  const req = new Request(`${URLBASE}${PATH}`, { method: "PUT", body: new Uint8Array([1, 2, 3]) });
  assertEquals(req.headers.get("content-type"), null);
  await createApp({ config: CONFIG, fetch: fetchImpl }).request(req);
  assertEquals(new Headers(calls[0].init.headers).get("Content-Type"), "application/octet-stream");
});

Deno.test("last-write-wins: single unconditional PUT, no HEAD/GET pre-check", async () => {
  const { calls, fetchImpl } = recorder();
  await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, { method: "PUT", body: "x" });
  assertEquals(calls.length, 1);
  assertEquals(calls[0].init.method, "PUT");
});

Deno.test("bunny error → 502 (never a false 2xx)", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 502);
});

Deno.test("upstream throw/abort → 502", async () => {
  const { fetchImpl } = recorder({ throws: true });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 502);
});

Deno.test("wrong method (GET) → 404 (Hono default), no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "GET",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("OPTIONS → 204, no resumable advertised, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "OPTIONS",
  });
  assertEquals(res.status, 204);
  assertEquals(res.headers.get("Allow"), "PUT, OPTIONS");
  assertEquals(calls.length, 0);
  for (const [name] of res.headers) {
    if (name.toLowerCase().startsWith("upload-")) {
      throw new Error(`unexpected resumable header advertised: ${name}`);
    }
  }
});

Deno.test("non-UUID segment → 400, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/nope/device/${D}/file/a.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("unmatched path → 404, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/nope", {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("empty filename (no resource) → 404 (route doesn't match)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/${E}/device/${D}/file/`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

// ── GET /event/:eventId/files (capability `bunny-list-endpoint`) ─────────────────────────────────

const D2 = "9c21aa00-0000-4000-8000-000000000003";
const ZONE = `https://storage.bunnycdn.com/snapsync-zone`;
const TOP = `${ZONE}/${E}/`; // event dir (device sub-dirs)
const FILES = `/event/${E}/files`;

const dir = (name: string) => ({ ObjectName: name, IsDirectory: true, Length: 0 });
const file = (
  name: string,
  length: number,
  ts: { LastChanged?: string; DateLastModified?: string },
) => ({
  ObjectName: name,
  IsDirectory: false,
  Length: length,
  ...ts,
});

/** Serves canned bunny LIST JSON keyed by URL; any unmapped URL → 404 (→ "no objects"). */
function listFake(routes: Record<string, { status?: number; body?: unknown }>) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    const r = routes[url];
    if (!r) return Promise.resolve(new Response("not found", { status: 404 }));
    const body = r.body === undefined ? null : JSON.stringify(r.body);
    return Promise.resolve(
      new Response(body, {
        status: r.status ?? 200,
        headers: { "content-type": "application/json" },
      }),
    );
  };
  return { calls, fetchImpl };
}

Deno.test("GET files → flat array across all devices, normalized entries", async () => {
  const { calls, fetchImpl } = listFake({
    [TOP]: { body: [dir(D), dir(D2)] },
    [`${ZONE}/${E}/${D}/`]: {
      body: [file("IMG_0001-ios.photo.jpg", 1234, { LastChanged: "2026-06-20T10:31:00Z" })],
    },
    [`${ZONE}/${E}/${D2}/`]: {
      // second device uses the alternate timestamp field name bunny's docs also show
      body: [file("VID_0002-ios.video.mov", 5678, { DateLastModified: "2026-06-21T08:00:00Z" })],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), [
    {
      filename: "IMG_0001-ios.photo.jpg",
      deviceId: D,
      size: 1234,
      lastModified: "2026-06-20T10:31:00Z",
    },
    {
      filename: "VID_0002-ios.video.mov",
      deviceId: D2,
      size: 5678,
      lastModified: "2026-06-21T08:00:00Z",
    },
  ]);
  // 1 + deviceCount subrequests, each carrying the AccessKey (never the account API key)
  assertEquals(calls.length, 3);
  for (const call of calls) {
    assertEquals(new Headers(call.init.headers).get("AccessKey"), "zone-password");
  }
});

Deno.test("GET files → directory entries inside a device dir are excluded", async () => {
  const { fetchImpl } = listFake({
    [TOP]: { body: [dir(D)] },
    [`${ZONE}/${E}/${D}/`]: {
      body: [file("IMG_0001-ios.photo.jpg", 10, { LastChanged: "t" }), dir("nested")],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), [
    { filename: "IMG_0001-ios.photo.jpg", deviceId: D, size: 10, lastModified: "t" },
  ]);
});

Deno.test("GET files → empty event dir (200 []) → 200 []", async () => {
  const { fetchImpl } = listFake({ [TOP]: { body: [] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("GET files → unknown event dir (bunny 404) → 200 []", async () => {
  const { fetchImpl } = listFake({ [TOP]: { status: 404 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("GET files → non-UUID event id → 400, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event/nope/files");
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("GET files → a per-device LIST failure (500) → 502 (never partial)", async () => {
  const { fetchImpl } = listFake({
    [TOP]: { body: [dir(D), dir(D2)] },
    [`${ZONE}/${E}/${D}/`]: { body: [file("IMG_0001-ios.photo.jpg", 1, { LastChanged: "t" })] },
    [`${ZONE}/${E}/${D2}/`]: { status: 500 },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 502);
});

Deno.test("GET files → wrong method (POST) → 404, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({ [TOP]: { body: [] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES, {
    method: "POST",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("GET files → listed filename round-trips a percent-encoded upload name", async () => {
  // The upload handler stores at encodeURIComponent(filename); bunny returns that as ObjectName.
  // The listing SHALL decode it back to the filename the client uploaded, so a re-joining device can
  // match by the reinstall-stable key (raw uploadKeys are encoding-safe; this guards the general case).
  const { fetchImpl } = listFake({
    [TOP]: { body: [dir(D)] },
    [`${ZONE}/${E}/${D}/`]: {
      body: [file("IMG%20001.jpg", 7, { LastChanged: "2026-06-20T10:31:00Z" })],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals((await res.json())[0].filename, "IMG 001.jpg");
});
