import { assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";

const E = "7a3f9c21-0000-4000-8000-000000000001"; // an eventId
const D = "11111111-0000-4000-8000-000000000002"; // a deviceId
const URLBASE = "https://edge.example";

const CONFIG = {
  zone: "snapsync-zone",
  host: "storage.bunnycdn.com",
  accessKey: "zone-password",
  baseUrl: "https://dl.example",
};

const ZONE = `https://storage.bunnycdn.com/snapsync-zone`;
const MARKER_URL = `${ZONE}/events/${E}/metadata.json`; // event registry marker
const MARKER_BODY = { eventId: E, name: "Party", createdAt: "2026-06-27T00:00:00Z" };
const markerPresent = { [MARKER_URL]: { body: MARKER_BODY } };

// Byte routes (`bunny-upload-endpoint` / `bunny-download-endpoint`): device-partitioned, event-independent.
const BYTE_PATH = `/files/device/${D}/IMG_0001-photo.jpg`;
const BYTE_OBJ_URL = `${ZONE}/files/${D}/IMG_0001-photo.jpg`;
// Per-device list (`bunny-list-endpoint`).
const DEVLIST_PATH = `/files/device/${D}`;
const DEVDIR_URL = `${ZONE}/files/${D}/`;
// Device-manifest write (`bunny-upload-endpoint`, gated).
const DEVMANIFEST_PATH = `/event/${E}/device/${D}`;
const DEVMANIFEST_URL = `${ZONE}/events/${E}/device/${D}.json`;

type Call = { url: string; init: RequestInit };

const putCall = (calls: Call[]) => calls.find((c) => c.init.method === "PUT")!;

/**
 * Records upstream calls. By default a GET (the event-existence marker, used only by the device-manifest
 * write and the metadata route) returns a present marker, and a PUT returns `status` (201) — or throws
 * if `throws`. Set `marker` to model an absent ("absent" → 404) or failing ("fail" → 500) marker read.
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
        new Response(JSON.stringify(MARKER_BODY), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      );
    }
    if (opts.throws) return Promise.reject(new Error("network boom"));
    return Promise.resolve(new Response(null, { status: opts.status ?? 201 }));
  };
  return { calls, fetchImpl };
}

// ── PUT /files/device/:deviceId/:filename (byte upload, UNGATED) ──────────────────────────────────

Deno.test("byte PUT → forwards once with bare files/<device>/<name> key, AccessKey, content-type, body", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "hello-bytes",
    headers: { "content-type": "image/jpeg" },
  });

  assertEquals(res.status, 201);
  assertEquals(calls.length, 1); // UNGATED: exactly one object PUT, NO marker GET
  const call = putCall(calls);
  assertEquals(call.url, BYTE_OBJ_URL);
  const h = new Headers(call.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "image/jpeg");
  assertEquals(await new Response(call.init.body as BodyInit).text(), "hello-bytes");
});

Deno.test("byte PUT → encoded filename round-trips to an encoded, flat key on the wire", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/device/${D}/IMG%20001.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 201);
  assertEquals(putCall(calls).url, `${ZONE}/files/${D}/IMG%20001.jpg`);
});

Deno.test("byte PUT → encoded slash (%2F) in filename → 400 (would un-flatten the key)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/device/${D}/a%2Fb.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("byte PUT → missing content-type defaults to application/octet-stream", async () => {
  const { calls, fetchImpl } = recorder();
  const req = new Request(`${URLBASE}${BYTE_PATH}`, {
    method: "PUT",
    body: new Uint8Array([1, 2, 3]),
  });
  assertEquals(req.headers.get("content-type"), null);
  await createApp({ config: CONFIG, fetch: fetchImpl }).request(req);
  assertEquals(
    new Headers(putCall(calls).init.headers).get("Content-Type"),
    "application/octet-stream",
  );
});

Deno.test("byte PUT → one unconditional object PUT, no existence check, no marker read", async () => {
  const { calls, fetchImpl } = recorder();
  await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(calls.length, 1);
  assertEquals(calls[0].init.method, "PUT");
  assertEquals(calls[0].url, BYTE_OBJ_URL);
});

Deno.test("byte PUT → bunny error → 502 (never a false 2xx)", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 502);
});

Deno.test("byte PUT → upstream throw/abort → 502", async () => {
  const { fetchImpl } = recorder({ throws: true });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 502);
});

Deno.test("byte PUT → non-UUID device segment → 400, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/device/nope/a.jpg`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("byte PUT → unmatched path → 404, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/nope", {
    method: "PUT",
    body: "x",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("byte PUT → empty filename (no resource) → 404 (route doesn't match)", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/device/${D}/`,
    { method: "PUT", body: "x" },
  );
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("byte OPTIONS → 204, no resumable advertised, no upstream request", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH, {
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

// ── PUT /event/:eventId/device/:deviceId (device-manifest write, GATED) ───────────────────────────

Deno.test("device-manifest PUT → marker GET + one object PUT to events/<e>/device/<d>.json, 201", async () => {
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: JSON.stringify({ deviceId: D, assets: [] }),
    headers: { "content-type": "application/json" },
  });
  assertEquals(res.status, 201);
  assertEquals(calls.length, 2); // marker GET (existence) + object PUT
  const get = calls.find((c) => c.init.method === "GET")!;
  assertEquals(get.url, MARKER_URL);
  const put = putCall(calls);
  assertEquals(put.url, DEVMANIFEST_URL);
  const h = new Headers(put.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "application/json");
});

Deno.test("device-manifest PUT → missing event (marker absent) → 404, no object PUT", async () => {
  const { calls, fetchImpl } = recorder({ marker: "absent" });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
});

Deno.test("device-manifest PUT → marker read failure → 502 (not absence), no object PUT", async () => {
  const { calls, fetchImpl } = recorder({ marker: "fail" });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 502);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
});

Deno.test("device-manifest PUT → bunny PUT error → 502", async () => {
  const { fetchImpl } = recorder({ status: 500 });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVMANIFEST_PATH, {
    method: "PUT",
    body: "{}",
  });
  assertEquals(res.status, 502);
});

Deno.test("device-manifest PUT → non-UUID event or device → 400, no upstream", async () => {
  for (const path of [`/event/nope/device/${D}`, `/event/${E}/device/nope`]) {
    const { calls, fetchImpl } = recorder();
    const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(path, {
      method: "PUT",
      body: "{}",
    });
    assertEquals(res.status, 400);
    assertEquals(calls.length, 0);
  }
});

// ── GET /files/device/:deviceId (per-device raw listing) ──────────────────────────────────────────

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

Deno.test("device list → raw files as { filename, size, url }, one LIST, no further reads", async () => {
  const { calls, fetchImpl } = listFake({
    [DEVDIR_URL]: {
      body: [file("A-primary.heic", 100), file("A-motion.mov", 200), dir("nested")],
    },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), [
    {
      filename: "A-primary.heic",
      size: 100,
      url: `https://dl.example/files/device/${D}/A-primary.heic`,
    },
    {
      filename: "A-motion.mov",
      size: 200,
      url: `https://dl.example/files/device/${D}/A-motion.mov`,
    },
  ]); // directory entry filtered out
  assertEquals(calls.length, 1); // single LIST, no manifest/content reads
  assertEquals(new Headers(calls[0].init.headers).get("AccessKey"), "zone-password");
});

Deno.test("device list → empty dir (200 []) → 200 []", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { body: [] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("device list → empty dir (bunny 404) → 200 []", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { status: 404 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), []);
});

Deno.test("device list → LIST failure (500) → 502 (never partial)", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { status: 500 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 502);
});

Deno.test("device list → non-UUID device id → 400, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request("/files/device/nope");
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("device list → wrong method (POST) → 404, no upstream request", async () => {
  const { calls, fetchImpl } = listFake({ [DEVDIR_URL]: { body: [] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH, {
    method: "POST",
  });
  assertEquals(res.status, 404);
  assertEquals(calls.length, 0);
});

Deno.test("device list → a percent-encoded filename round-trips and re-encodes into the url", async () => {
  const { fetchImpl } = listFake({ [DEVDIR_URL]: { body: [file("A-primary%201.heic", 7)] } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(DEVLIST_PATH);
  assertEquals(res.status, 200);
  const entry = (await res.json())[0];
  assertEquals(entry.filename, "A-primary 1.heic"); // decoded back to the uploaded name
  assertEquals(entry.url, `https://dl.example/files/device/${D}/A-primary%201.heic`); // re-encoded
});

// ── POST /event + GET /event/:eventId (capability `event-creation`) ───────────────────────────────

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.test("POST /event → 201 {eventId,name,createdAt} + one marker PUT to events/<id>/metadata.json", async () => {
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
  assertEquals(calls.length, 1);
  const put = calls[0];
  assertEquals(put.init.method, "PUT");
  assertEquals(put.url, `${ZONE}/events/${json.eventId}/metadata.json`);
  const h = new Headers(put.init.headers);
  assertEquals(h.get("AccessKey"), "zone-password");
  assertEquals(h.get("Content-Type"), "application/json");
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

Deno.test("GET /event/:id → 200 marker (events/<id>/metadata.json) when present", async () => {
  const { calls, fetchImpl } = listFake({ ...markerPresent });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(`/event/${E}`);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), MARKER_BODY);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, MARKER_URL);
});

Deno.test("GET /event/:id → 404 when marker absent", async () => {
  const { calls, fetchImpl } = listFake({});
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

// ── GET /files/device/:deviceId/:filename (capability `bunny-download-endpoint`) ──────────────────

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

Deno.test("download → 200 streams the body and relays content + cache headers (ungated, one GET)", async () => {
  const { calls, fetchImpl } = getFake({
    [BYTE_OBJ_URL]: {
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
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH);
  assertEquals(res.status, 200);
  assertEquals(await res.text(), "img-bytes");
  assertEquals(res.headers.get("Content-Type"), "image/jpeg");
  assertEquals(res.headers.get("Content-Length"), "9");
  assertEquals(res.headers.get("ETag"), '"abc"');
  assertEquals(res.headers.get("Last-Modified"), "Wed, 20 Jun 2026 10:31:00 GMT");
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=60");
  assertEquals(calls.length, 1); // ungated: object GET only, no marker read
  assertEquals(calls[0].url, BYTE_OBJ_URL);
  assertEquals(calls[0].init.method, "GET");
  assertEquals(new Headers(calls[0].init.headers).get("AccessKey"), "zone-password");
});

Deno.test("download → missing upstream content-type defaults to application/octet-stream", async () => {
  const { fetchImpl } = getFake({ [BYTE_OBJ_URL]: { body: new Uint8Array([1, 2, 3]) } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH);
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "application/octet-stream");
});

Deno.test("download → bunny 404 (missing object) → 404, ungated (no marker read)", async () => {
  const { calls, fetchImpl } = getFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH);
  assertEquals(res.status, 404);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, BYTE_OBJ_URL);
});

Deno.test("download → bunny non-404 error (500) → 502", async () => {
  const { fetchImpl } = getFake({ [BYTE_OBJ_URL]: { status: 500 } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH);
  assertEquals(res.status, 502);
});

Deno.test("download → upstream throw/abort → 502", async () => {
  const { fetchImpl } = getFake({ [BYTE_OBJ_URL]: { throws: true } });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(BYTE_PATH);
  assertEquals(res.status, 502);
});

Deno.test("download → non-UUID device id → 400, no upstream request", async () => {
  const { calls, fetchImpl } = getFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    "/files/device/nope/a.jpg",
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("download → unsafe filename (%2F) → 400, no upstream request", async () => {
  const { calls, fetchImpl } = getFake({});
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/device/${D}/a%2Fb.jpg`,
  );
  assertEquals(res.status, 400);
  assertEquals(calls.length, 0);
});

Deno.test("download → a list url round-trips: encoded filename → flat object key", async () => {
  const ENC_URL = `${ZONE}/files/${D}/IMG%20001.jpg`;
  const { calls, fetchImpl } = getFake({
    [ENC_URL]: { body: "x", headers: { "content-type": "image/jpeg" } },
  });
  const res = await createApp({ config: CONFIG, fetch: fetchImpl }).request(
    `/files/device/${D}/IMG%20001.jpg`,
  );
  assertEquals(res.status, 200);
  assertEquals(calls[0].url, ENC_URL);
});
