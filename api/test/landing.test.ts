import { emptyStore } from "./support/db.ts";
import { assert, assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import { readConfig } from "../src/config.ts";

// The landing page (capability `marketing-site`, built by the `site/` Astro module) is served by PROXYING
// the storage `site/` prefix (capability `web-site`). These tests exercise the PROXY mechanics — routing,
// streaming, cache policy, and the faithful 404/502 outcome — against an injected fake storage, so they
// stay offline (no network). The page's CONTENT (Privacy/Terms, screenshots, self-containment) is now the
// `site/` build's concern and is checked there, not in the api.
//
// The gate interaction (`/` and `/_astro/*` served without a token) lives in attest.test.ts, next to the
// gate itself.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
  BUNNY_DATABASE_URL: "libsql://example.invalid",
  BUNNY_DATABASE_AUTH_TOKEN: "dbt",
  ADMIN_NOTIFY_KEY: "a",
});

const INDEX_HTML = "<!doctype html><title>SnapSync</title><body>hello</body>";
const ASSET_BYTES = new Uint8Array([0x52, 0x49, 0x46, 0x46]); // pretend-webp bytes

// A fake bunny storage that serves the `site/` prefix and 404s everything else, recording the keys asked
// for. Only GETs are expected on this path.
function fakeStorage(): { fetch: FetchLike; keys: string[] } {
  const keys: string[] = [];
  const fetch: FetchLike = (url, init) => {
    assertEquals(init.method, "GET");
    // Addressed against the RESOLVED zone, not a pinned literal: this asserts the proxy targets the
    // zone it was configured with, which stays true for any deployment.
    const key = url.match(new RegExp(`/${CONFIG.zone}/(site/.*)$`))?.[1] ?? "";
    keys.push(key);
    if (key === "site/index.html") {
      return Promise.resolve(new Response(INDEX_HTML, { status: 200 }));
    }
    if (key === "site/_astro/app.abc123.webp") {
      return Promise.resolve(new Response(ASSET_BYTES, { status: 200 }));
    }
    return Promise.resolve(new Response("not found", { status: 404 }));
  };
  return { fetch, keys };
}

const DB = await emptyStore();
const app = (f: FetchLike) => createApp({ config: CONFIG, db: DB, fetch: f });

Deno.test("landing: GET / proxies site/index.html as no-cache HTML", async () => {
  const s = fakeStorage();
  const res = await app(s.fetch).request("/");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "no-cache"); // the always-fresh shell
  assertEquals(await res.text(), INDEX_HTML);
  assert(s.keys.includes("site/index.html"), "the proxy read site/index.html");
});

Deno.test("landing: /_astro/* proxies the fingerprinted asset with an immutable cache", async () => {
  const s = fakeStorage();
  const res = await app(s.fetch).request("/_astro/app.abc123.webp");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "image/webp");
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=31536000, immutable");
  assertEquals(new Uint8Array(await res.arrayBuffer()), ASSET_BYTES);
  assert(s.keys.includes("site/_astro/app.abc123.webp"), "the proxy read the hashed asset key");
});

Deno.test("landing: HEAD / returns the headers with no body", async () => {
  const res = await app(fakeStorage().fetch).request("/", { method: "HEAD" });
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "no-cache");
  assertEquals(await res.text(), "");
});

Deno.test("landing: a missing site object is a 404, never a false 200", async () => {
  // An asset the build did not emit. Faithful outcome: never a 200 with an empty/wrong body.
  const res = await app(fakeStorage().fetch).request("/_astro/never-built.webp");
  assertEquals(res.status, 404);
});

Deno.test("landing: an upstream storage failure is a 502", async () => {
  const failing: FetchLike = () => Promise.resolve(new Response("boom", { status: 500 }));
  const res = await app(failing).request("/");
  assertEquals(res.status, 502);
});

Deno.test("landing: a transport error is a 502", async () => {
  const throwing: FetchLike = () => Promise.reject(new Error("network down"));
  const res = await app(throwing).request("/");
  assertEquals(res.status, 502);
});
