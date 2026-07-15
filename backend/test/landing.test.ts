import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import { readConfig } from "../src/config.ts";

// The marketing/landing page (capability `marketing-site`): a single source-owned static page served at
// `GET /`. These tests exercise the served RESPONSE — its status, headers, self-containment, and that
// App-Store-required content (Privacy Policy, Terms, support, contact) is present. The gate interaction
// (served without a token; exact-path exception) lives in attest.test.ts, next to the gate itself.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
});

// Serving the page must make NO upstream request — any call here is a failure.
const noFetch: FetchLike = () => {
  throw new Error("serving the landing page must make no upstream request");
};
const app = () => createApp({ config: CONFIG, fetch: noFetch });

Deno.test("landing: GET / serves the page as cacheable HTML", async () => {
  const res = await app().request("/");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=300"); // inverse of the listings' no-cache
  assert((await res.text()).length > 0);
});

Deno.test("landing: the page carries the App Store submission surface", async () => {
  const body = await (await app().request("/")).text();
  assertStringIncludes(body, 'id="privacy"'); // Privacy Policy section (→ /#privacy)
  assertStringIncludes(body, 'id="terms"'); // Terms / EULA section (→ /#terms)
  assertStringIncludes(body, "https://github.com/stefanhoelzl/SnapSync/issues"); // support
  assertStringIncludes(body, "mailto:"); // contact
});

Deno.test("landing: the page is self-contained — no external assets, no tracking", async () => {
  const body = await (await app().request("/")).text();
  // Asset-loading attributes must never point off-host (the icon is an inlined data: URI). Anchor hrefs to
  // Apple/GitHub are navigation, not asset loads, so only asset-loading forms are checked.
  assert(
    !/(?:src|srcset)\s*=\s*["']https?:/i.test(body),
    "external src=/srcset= asset reference found",
  );
  assert(!/<link\b[^>]*href\s*=\s*["']https?:/i.test(body), "external <link> stylesheet found");
  assert(!/<script\b/i.test(body), "unexpected <script> tag (page must ship no JS)");
});

Deno.test("landing: HEAD / returns the headers with no body", async () => {
  const res = await app().request("/", { method: "HEAD" });
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=300");
  assertEquals(await res.text(), "");
});
