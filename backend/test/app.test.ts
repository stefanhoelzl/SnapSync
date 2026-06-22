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
