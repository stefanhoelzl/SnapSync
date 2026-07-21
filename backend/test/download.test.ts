import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import { readConfig } from "../src/config.ts";

// The no-app download page (capability `web-event-download`): a single source-owned static page served at
// `GET /join`. These tests exercise the served RESPONSE — its status, headers, self-containment, and that
// the download affordance and the App Store link are present. The gate interaction (served without a
// token) and the /join response shape live in attest.test.ts and eventlink.test.ts respectively; the
// per-event zip logic runs client-side in the browser and is out of scope for these origin tests.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
});

// Serving the page must make NO upstream request — any call here is a failure.
const noFetch: FetchLike = () => {
  throw new Error("serving the download page must make no upstream request");
};
const app = () => createApp({ config: CONFIG, fetch: noFetch });

Deno.test("download: GET /join serves the page as cacheable HTML", async () => {
  const res = await app().request("/join");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=300");
  assert((await res.text()).length > 0);
});

Deno.test("download: the page offers both download and install", async () => {
  const body = await (await app().request("/join")).text();
  assertStringIncludes(body, "Download all photos"); // the zip control
  assertStringIncludes(body, CONFIG.appStoreUrl); // the App Store link, ON the page (not a 302 target)
  assertStringIncludes(body, "Invalid or expired link"); // the bad/missing-event state
});

Deno.test("download: the page is fully self-contained — no third-party resource", async () => {
  // Load-bearing for the event-link fragment property: a subresource fetched off-origin would carry the
  // page URL (and the eventId risk) away via `Referer`. Inline <script> IS expected here (unlike the
  // landing page) — the page's whole job is client-side — but nothing may be LOADED from another host.
  const body = await (await app().request("/join")).text();
  assert(
    !/(?:src|srcset)\s*=\s*["']https?:/i.test(body),
    "external src=/srcset= asset reference found",
  );
  assert(!/<link\b[^>]*href\s*=\s*["']https?:/i.test(body), "external <link> found");
  assert(
    !/<script\b[^>]*\bsrc\s*=/i.test(body),
    "external <script src=> found (script must be inline)",
  );
  assert(!/\bimport\b[^;]*\bfrom\s*["']https?:/i.test(body), "off-origin ES import found");
});
