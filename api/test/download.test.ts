import { assert, assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import { readConfig } from "../src/config.ts";

// The no-app download page (capability `web-event-download`, built by the `site/` Astro module) is served
// by PROXYING the constant `site/join/index.html` object from storage (capability `web-site`). These tests
// exercise the PROXY mechanics — that `/join` reads the same constant object for every request (no
// per-event state), serves it `no-cache`, and yields a faithful 404/502 — against an injected fake
// storage, so they stay offline. The page's CONTENT (the download/install controls, the client zip logic)
// and its self-containment are the `site/` build's concern and are checked there.
//
// The gate interaction (`/join` served without a token) lives in attest.test.ts.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
  ADMIN_NOTIFY_KEY: "a",
});

const JOIN_HTML = "<!doctype html><title>SnapSync — event photos</title><body>join</body>";

// A fake storage serving only site/join/index.html.
function fakeStorage(): { fetch: FetchLike; keys: string[] } {
  const keys: string[] = [];
  const fetch: FetchLike = (url, init) => {
    assertEquals(init.method, "GET");
    const key = url.match(/\/snap-sync-dev\/(site\/.*)$/)?.[1] ?? "";
    keys.push(key);
    if (key === "site/join/index.html") {
      return Promise.resolve(new Response(JOIN_HTML, { status: 200 }));
    }
    return Promise.resolve(new Response("not found", { status: 404 }));
  };
  return { fetch, keys };
}

const app = (f: FetchLike) => createApp({ config: CONFIG, fetch: f });

Deno.test("download: GET /join proxies the constant site/join/index.html as no-cache HTML", async () => {
  const s = fakeStorage();
  const res = await app(s.fetch).request("/join");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "no-cache"); // always-fresh shell
  assertEquals(await res.text(), JOIN_HTML);
  assert(s.keys.includes("site/join/index.html"), "the proxy read the constant join page");
});

Deno.test("download: /join reads the SAME constant object for different links (no per-event state)", async () => {
  // The payload rides in the fragment (never sent), so the backend serves byte-identical bytes and makes
  // the same single storage read regardless of which event link is opened.
  const s = fakeStorage();
  const a = await (await app(s.fetch).request("/join")).text();
  const b = await (await app(s.fetch).request("/join")).text();
  assertEquals(a, b);
  assertEquals(s.keys.every((k) => k === "site/join/index.html"), true);
});

Deno.test("download: HEAD /join returns the headers with no body", async () => {
  const res = await app(fakeStorage().fetch).request("/join", { method: "HEAD" });
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Cache-Control"), "no-cache");
  assertEquals(await res.text(), "");
});

Deno.test("download: an upstream storage failure is a 502", async () => {
  const failing: FetchLike = () => Promise.resolve(new Response("boom", { status: 500 }));
  assertEquals((await app(failing).request("/join")).status, 502);
});
