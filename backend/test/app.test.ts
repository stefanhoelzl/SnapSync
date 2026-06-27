import { assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";

const E = "7a3f9c21-0000-4000-8000-000000000001";
const PATH = `/event/${E}/file/IMG_0001-photo.jpg`;
const URLBASE = "https://edge.example";

const CONFIG = {
  zone: "snapsync-zone",
  host: "storage.bunnycdn.com",
  accessKey: "zone-password",
  baseUrl: "https://dl.example",
};

type Call = { url: string; init: RequestInit };

// The marker GET is the only GET the upload handler (and the create marker write is the only PUT the
// create handler) makes; tests distinguish upstream calls by method.
const putCall = (calls: Call[]) => calls.find((c) => c.init.method === "PUT")!;

/**
 * Records upstream calls. By default the event-existence marker GET (the upload handler's pre-check)
 * returns a present marker, and the object PUT returns `status` (201) — or throws if `throws`. Set
 * `marker` to model an absent ("absent" → 404) or failing ("fail" → 500) marker read. (The create
 * handler writes its marker via a PUT, so it falls through to the `status`/`throws` branch.)
 */
function recorder(
  opts: { status?: number; throws?: boolean; marker?: "present" | "absent" | "fail" } = {},
) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    if (init.method === "GET") {
      const m = opts.marker ?? "present";
      if (m === "absent") return Promise.resolve(new Response(null, { status: 404 }));
      if (m === "fail") return Promise.resolve(new Response("boom", { status: 500 }));
      return Promise.resolve(
        new Response(
          JSON.stringify({ eventId: E, name: "Party", createdAt: "2026-06-27T00:00:00Z" }),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
      );
    }
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
  assertEquals(calls.length, 2); // one marker GET (existence) + one object PUT
  const call = putCall(calls);
  assertEquals(call.url, `https://storage.bunnycdn.com/snapsync-zone/${E}/IMG_0001-photo.jpg`);
  const h = new Headers(call.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "image/jpeg");
  const sent = await new Response(call.init.body as BodyInit).text();
  assertEquals(sent, "hello-bytes");
});

Deno.test("encoded filename round-trips to an encoded, flat key on the wire", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/${E}/file/IMG%20001.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 201);
  // Hono decodes the param ("IMG 001.jpg"); the key re-encodes it → bunny gets a single flat segment
  // (not a decoded space, not a split path).
  assertEquals(putCall(calls).url, `https://storage.bunnycdn.com/snapsync-zone/${E}/IMG%20001.jpg`);
});

Deno.test("encoded slash (%2F) in filename → 400 (would un-flatten the key)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/${E}/file/a%2Fb.jpg`,
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
  assertEquals(
    new Headers(putCall(calls).init.headers).get("Content-Type"),
    "application/octet-stream",
  );
});

Deno.test("gated upload: one marker GET + one unconditional object PUT (no check on the object key)", async () => {
  const { calls, fetchImpl } = recorder();
  await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, { method: "PUT", body: "x" });
  // exactly one object PUT, straight to the object key (last-write-wins; no existence check on it)
  const puts = calls.filter((c) => c.init.method === "PUT");
  assertEquals(puts.length, 1);
  assertEquals(puts[0].url, `https://storage.bunnycdn.com/snapsync-zone/${E}/IMG_0001-photo.jpg`);
  // the only other call is the EVENT-marker GET — never a read of the object key
  const others = calls.filter((c) => c.init.method !== "PUT");
  assertEquals(others.length, 1);
  assertEquals(others[0].init.method, "GET");
  assertEquals(others[0].url, `https://storage.bunnycdn.com/snapsync-zone/events/${E}.json`);
});

Deno.test("upload → missing event (marker absent) → 404, no object PUT", async () => {
  const { calls, fetchImpl } = recorder({ marker: "absent" });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
});

Deno.test("upload → marker read failure → 502 (not treated as absence), no object PUT", async () => {
  const { calls, fetchImpl } = recorder({ marker: "fail" });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 502);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
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

// (GET on the file path is now the download route — see the `bunny-download-endpoint` section below.)

Deno.test("OPTIONS → 204, no resumable advertised, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH, {
    method: "OPTIONS",
  });
  assertEquals(res.status, 204);
  assertEquals(res.headers.get("Allow"), "GET, PUT, OPTIONS");
  assertEquals(calls.length, 0);
  for (const [name] of res.headers) {
    if (name.toLowerCase().startsWith("upload-")) {
      throw new Error(`unexpected resumable header advertised: ${name}`);
    }
  }
});

Deno.test("non-UUID event segment → 400, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/nope/file/a.jpg`,
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
    `/event/${E}/file/`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

// ── GET /event/:eventId/files (capability `bunny-list-endpoint`) ─────────────────────────────────

const ZONE = `https://storage.bunnycdn.com/snapsync-zone`;
const TOP = `${ZONE}/${E}/`; // event dir (files are direct children)
const FILES = `/event/${E}/files`;
const MARKER_URL = `${ZONE}/events/${E}.json`; // event registry marker (existence gate)
const MARKER_BODY = { eventId: E, name: "Party", createdAt: "2026-06-27T00:00:00Z" };
const markerPresent = { [MARKER_URL]: { body: MARKER_BODY } };

const dir = (name: string) => ({ ObjectName: name, IsDirectory: true, Length: 0 });
const file = (name: string, length: number) => ({
  ObjectName: name,
  IsDirectory: false,
  Length: length,
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

Deno.test("GET files → flat array from a single event-dir LIST, normalized entries", async () => {
  const { calls, fetchImpl } = listFake({
    ...markerPresent,
    [TOP]: {
      body: [
        file("IMG_0001-ios.photo.jpg", 1234),
        file("VID_0002-ios.video.mov", 5678),
      ],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), [
    {
      filename: "IMG_0001-ios.photo.jpg",
      size: 1234,
      url: `https://dl.example/event/${E}/file/IMG_0001-ios.photo.jpg`,
    },
    {
      filename: "VID_0002-ios.video.mov",
      size: 5678,
      url: `https://dl.example/event/${E}/file/VID_0002-ios.video.mov`,
    },
  ]);
  // marker GET (existence) + one LIST; the LIST carries the AccessKey (never the account API key)
  assertEquals(calls.length, 2);
  const list = calls.find((c) => c.url === TOP)!;
  assertEquals(new Headers(list.init.headers).get("AccessKey"), "zone-password");
});

Deno.test("GET files → directory entries are excluded", async () => {
  const { fetchImpl } = listFake({
    ...markerPresent,
    [TOP]: {
      body: [file("IMG_0001-ios.photo.jpg", 10), dir("nested")],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), [
    {
      filename: "IMG_0001-ios.photo.jpg",
      size: 10,
      url: `https://dl.example/event/${E}/file/IMG_0001-ios.photo.jpg`,
    },
  ]);
});

Deno.test("GET files → created event, empty dir (200 []) → 200 []", async () => {
  const { fetchImpl } = listFake({ ...markerPresent, [TOP]: { body: [] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("GET files → created event, empty dir (bunny 404) → 200 []", async () => {
  const { fetchImpl } = listFake({ ...markerPresent, [TOP]: { status: 404 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("GET files → unknown event (marker absent) → 404, no LIST", async () => {
  const { calls, fetchImpl } = listFake({}); // marker unmapped → 404
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 404);
  // only the marker GET happened; the event dir is never LISTed
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, MARKER_URL);
});

Deno.test("GET files → marker read failure (500) → 502 (not treated as absence)", async () => {
  const { fetchImpl } = listFake({ [MARKER_URL]: { status: 500 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 502);
});

Deno.test("GET files → non-UUID event id → 400, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event/nope/files");
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("GET files → the event-dir LIST failing (500) → 502 (never partial)", async () => {
  const { fetchImpl } = listFake({ ...markerPresent, [TOP]: { status: 500 } });
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
    ...markerPresent,
    [TOP]: {
      body: [file("IMG%20001.jpg", 7)],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(FILES);
  assertEquals(res.status, 200);
  const entry = (await res.json())[0];
  assertEquals(entry.filename, "IMG 001.jpg");
  // the url re-encodes the decoded filename back to a single flat segment (%20, not a raw space)
  assertEquals(entry.url, `https://dl.example/event/${E}/file/IMG%20001.jpg`);
});

// ── POST /event + GET /event/:eventId (capability `event-creation`) ──────────────────────────────

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.test("POST /event → 201 {eventId,name,createdAt} + one marker PUT (AccessKey, JSON)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
    method: "POST",
    body: JSON.stringify({ name: "Birthday" }),
    headers: { "content-type": "application/json" },
  });
  assertEquals(res.status, 201);
  const json = await res.json();
  assertEquals(json.name, "Birthday");
  assertEquals(UUID_RE.test(json.eventId), true);
  assertEquals(typeof json.createdAt, "string");
  // exactly one upstream call: the marker PUT to events/<id>.json
  assertEquals(calls.length, 1);
  const put = calls[0];
  assertEquals(put.init.method, "PUT");
  assertEquals(put.url, `https://storage.bunnycdn.com/snapsync-zone/events/${json.eventId}.json`);
  const h = new Headers(put.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "application/json");
  // the stored body is exactly the returned record
  assertEquals(JSON.parse(put.init.body as string), json);
});

Deno.test("POST /event → name is trimmed before store and echo", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
    method: "POST",
    body: JSON.stringify({ name: "  Birthday  " }),
  });
  assertEquals(res.status, 201);
  assertEquals((await res.json()).name, "Birthday");
  assertEquals(JSON.parse(putCall(calls).init.body as string).name, "Birthday");
});

Deno.test("POST /event → client-supplied id ignored, server mints a fresh UUID", async () => {
  const { fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
    method: "POST",
    body: JSON.stringify({ name: "X", eventId: "client-supplied", id: "also-ignored" }),
  });
  assertEquals(res.status, 201);
  const json = await res.json();
  assertEquals(UUID_RE.test(json.eventId), true);
  assertEquals(json.eventId === "client-supplied", false);
});

Deno.test("POST /event → 100-char name accepted (boundary)", async () => {
  const { fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
    method: "POST",
    body: JSON.stringify({ name: "a".repeat(100) }),
  });
  assertEquals(res.status, 201);
});

Deno.test("POST /event → empty / whitespace / over-long / missing / non-JSON → 400, no upstream", async () => {
  const bodies = [
    '{"name":""}',
    '{"name":"   "}',
    `{"name":"${"a".repeat(101)}"}`,
    "{}",
    "not json",
  ];
  for (const body of bodies) {
    const { calls, fetchImpl } = recorder();
    const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
      method: "POST",
      body,
    });
    assertEquals(res.status, 400);
    assertEquals(calls.length, 0);
  }
});

Deno.test("POST /event → marker PUT fails (500) → 502 (faithful create)", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
    method: "POST",
    body: JSON.stringify({ name: "X" }),
  });
  assertEquals(res.status, 502);
});

Deno.test("POST /event → marker PUT throws → 502", async () => {
  const { fetchImpl } = recorder({ throws: true });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
    method: "POST",
    body: JSON.stringify({ name: "X" }),
  });
  assertEquals(res.status, 502);
});

Deno.test("non-POST on /event → 404 (no route), no upstream", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event", {
    method: "GET",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("GET /event/:id → 200 marker when present", async () => {
  const { calls, fetchImpl } = listFake({ ...markerPresent });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/event/${E}`);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), MARKER_BODY);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, MARKER_URL);
});

Deno.test("GET /event/:id → 404 when marker absent", async () => {
  const { calls, fetchImpl } = listFake({}); // unmapped → 404
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/event/${E}`);
  assertEquals(res.status, 404);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, MARKER_URL);
});

Deno.test("GET /event/:id → 400 on non-UUID, no upstream", async () => {
  const { calls, fetchImpl } = listFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/event/nope");
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("GET /event/:id → 502 on non-404 marker read failure", async () => {
  const { fetchImpl } = listFake({ [MARKER_URL]: { status: 500 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/event/${E}`);
  assertEquals(res.status, 502);
});

// ── GET /event/:eventId/file/:filename (capability `bunny-download-endpoint`) ─────────────────────

const OBJ_URL = `${ZONE}/${E}/IMG_0001-photo.jpg`; // the object key the upload route writes

/** Serves canned object responses keyed by URL; any unmapped URL → 404 (object absent). */
function getFake(
  routes: Record<
    string,
    { status?: number; body?: BodyInit; headers?: Record<string, string>; throws?: boolean }
  >,
) {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    calls.push({ url, init });
    const r = routes[url];
    if (r?.throws) return Promise.reject(new Error("network boom"));
    if (!r) return Promise.resolve(new Response("not found", { status: 404 }));
    return Promise.resolve(
      new Response(r.body ?? null, { status: r.status ?? 200, headers: r.headers }),
    );
  };
  return { calls, fetchImpl };
}

Deno.test("GET file → 200 streams the body and relays content + cache headers (ungated, one GET)", async () => {
  const { calls, fetchImpl } = getFake({
    [OBJ_URL]: {
      body: "img-bytes",
      headers: {
        "content-type": "image/jpeg",
        "content-length": "9",
        "etag": '"abc"',
        "last-modified": "Wed, 20 Jun 2026 10:31:00 GMT",
        "cache-control": "public, max-age=60",
      },
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH);
  assertEquals(res.status, 200);
  assertEquals(await res.text(), "img-bytes");
  assertEquals(res.headers.get("Content-Type"), "image/jpeg");
  assertEquals(res.headers.get("Content-Length"), "9");
  assertEquals(res.headers.get("ETag"), '"abc"');
  assertEquals(res.headers.get("Last-Modified"), "Wed, 20 Jun 2026 10:31:00 GMT");
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=60");
  // exactly one upstream call — the object GET — and NO marker read (download is ungated)
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, OBJ_URL);
  assertEquals(calls[0].init.method, "GET");
  assertEquals(new Headers(calls[0].init.headers).get("AccessKey"), "zone-password");
});

Deno.test("GET file → missing upstream content-type defaults to application/octet-stream", async () => {
  // A typed-array body does NOT auto-set a content-type (a string body would set text/plain).
  const { fetchImpl } = getFake({ [OBJ_URL]: { body: new Uint8Array([1, 2, 3]) } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH);
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "application/octet-stream");
});

Deno.test("GET file → bunny 404 (missing object / unknown event) → 404, ungated (no marker read)", async () => {
  const { calls, fetchImpl } = getFake({}); // OBJ_URL unmapped → 404
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH);
  assertEquals(res.status, 404);
  // exactly one upstream call — the object GET — never a marker GET of events/<id>.json
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, OBJ_URL);
});

Deno.test("GET file → bunny non-404 error (500) → 502", async () => {
  const { fetchImpl } = getFake({ [OBJ_URL]: { status: 500 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH);
  assertEquals(res.status, 502);
});

Deno.test("GET file → upstream throw/abort → 502", async () => {
  const { fetchImpl } = getFake({ [OBJ_URL]: { throws: true } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(PATH);
  assertEquals(res.status, 502);
});

Deno.test("GET file → non-UUID event id → 400, no upstream request", async () => {
  const { calls, fetchImpl } = getFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    "/event/nope/file/a.jpg",
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("GET file → unsafe filename (%2F) → 400, no upstream request", async () => {
  const { calls, fetchImpl } = getFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/${E}/file/a%2Fb.jpg`,
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("GET file → a list url round-trips: encoded filename → flat object key", async () => {
  // The list builds `…/file/IMG%20001.jpg`; fetching that decodes to "IMG 001.jpg" and the handler
  // re-encodes it → the single flat object key the upload route wrote. Closes the list→download loop.
  const ENC_URL = `${ZONE}/${E}/IMG%20001.jpg`;
  const { calls, fetchImpl } = getFake({
    [ENC_URL]: { body: "x", headers: { "content-type": "image/jpeg" } },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/event/${E}/file/IMG%20001.jpg`,
  );
  assertEquals(res.status, 200);
  assertEquals(calls[0].url, ENC_URL); // single flat segment upstream (%20, not a raw space or split)
});
