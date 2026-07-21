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
  ADMIN_NOTIFY_KEY: "a",
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
  // Asset-loading attributes must never point off-host (the icon and screenshots are inlined data: URIs).
  // Anchor hrefs to Apple/GitHub are navigation, not asset loads, so only asset-loading forms are checked.
  assert(
    !/(?:src|srcset)\s*=\s*["']https?:/i.test(body),
    "external src=/srcset= asset reference found",
  );
  assert(!/<link\b[^>]*href\s*=\s*["']https?:/i.test(body), "external <link> stylesheet found");
  assert(!/<script\b/i.test(body), "unexpected <script> tag (page must ship no JS)");
});

// The app's screenshots (capability `marketing-site`). They are DERIVED at build time from the committed
// raws in screenshots/ — the same source the App Store listing composites from — so the page and the
// listing can never depict different software.
Deno.test("landing: the page shows the app, inlined", async () => {
  const body = await (await app().request("/")).text();
  const imgs = [...body.matchAll(/<img[^>]+src="(data:image\/webp;base64,[^"]+)"/g)];
  assertEquals(imgs.length, 3, "expected three inlined screenshots");
  // A data: URI proves the derive ran AND that nothing is fetched at render time. Guard against a
  // truncated/empty encode shipping as a technically-valid URI.
  for (const [, uri] of imgs) {
    assert(uri.length > 2000, `screenshot data URI implausibly small: ${uri.length}b`);
  }
});

Deno.test("landing: each screenshot has a dark rendering selected by prefers-color-scheme", async () => {
  const body = await (await app().request("/")).text();
  const darks = [
    ...body.matchAll(
      /<source[^>]+srcset="(data:image\/webp;base64,[^"]+)"[^>]*media="\(prefers-color-scheme: dark\)"/g,
    ),
  ];
  assertEquals(darks.length, 3, "expected a dark <source> per screenshot");
  // A light shot inside the page's dark palette is exactly what the theme work exists to avoid, so the
  // dark rendering must differ from the light one — not merely be present.
  const lights = [...body.matchAll(/<img[^>]+src="(data:image\/webp;base64,[^"]+)"/g)].map((m) =>
    m[1]
  );
  for (let i = 0; i < darks.length; i++) {
    assert(darks[i][1] !== lights[i], `dark screenshot ${i} is identical to the light one`);
  }
});

Deno.test("landing: no screenshot placeholder survives into the page", async () => {
  const body = await (await app().request("/")).text();
  // A renamed state or stale placeholder would otherwise ship `{{SHOT_…}}` as literal text on the public
  // page. app.ts throws at module init; this pins the guarantee to the served response.
  assert(
    !/\{\{SHOT_[A-Z_]+\}\}/.test(body),
    "unsubstituted {{SHOT_*}} placeholder in the served page",
  );
});

Deno.test("landing: screenshot headlines are document text, not baked pixels", async () => {
  const body = await (await app().request("/")).text();
  // The App Store composite bakes its headline in because Apple gives no text layer; the page has one, so
  // its copy stays selectable, searchable and available to assistive technology.
  for (const caption of ["Start an event", "Scan to join", "Everyone's in sync"]) {
    assertStringIncludes(body, caption);
  }
  // The scroll container must be reachable and named for keyboard/AT users.
  assertStringIncludes(body, 'tabindex="0"');
  assertStringIncludes(body, 'aria-label="Screenshots of SnapSync, scrollable"');
});

Deno.test("landing: the app icon is the favicon, inlined", async () => {
  const body = await (await app().request("/")).text();
  const m = body.match(/<link[^>]+rel="icon"[^>]+href="(data:image\/png;base64,[^"]+)"/);
  assert(m, "no inlined favicon link");
  // Sized for a tab, not reused from the full-resolution app icon: a favicon renders at 16-32px, and the
  // page already inlines a ~98KB 1024px blob for the brandmark. Guard against that creeping back.
  // (PNG, not WebP: a favicon must render in every browser, and Safari's WebP-favicon support is patchy.)
  assert(
    m[1].length < 20_000,
    `favicon is ${(m[1].length / 1024).toFixed(0)}KB — too big for a tab icon`,
  );
});

// The ETag does NOT invalidate anything — `max-age` already caps staleness, since a cache serves its stored
// copy for that long without asking. It makes the revalidation AFTER that cheap: the page is ~290KB (mostly
// inlined screenshots), so re-checking it costs an empty 304 instead of re-sending the whole thing.
Deno.test("landing: GET / carries an ETag", async () => {
  const res = await app().request("/");
  const etag = res.headers.get("ETag");
  assert(etag && /^"[a-z0-9]+-[a-z0-9]+"$/.test(etag), `implausible ETag: ${etag}`);
  await res.text();
});

Deno.test("landing: a matching If-None-Match is answered 304 with no body", async () => {
  const etag = (await app().request("/")).headers.get("ETag")!;
  const res = await app().request("/", { headers: { "If-None-Match": etag } });
  assertEquals(res.status, 304);
  assertEquals(await res.text(), "");
  // A 304 must still carry the caching headers, or the cache learns nothing from it.
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=300");
  assertEquals(res.headers.get("ETag"), etag);
});

Deno.test("landing: a stale If-None-Match gets the page, not a 304", async () => {
  const res = await app().request("/", { headers: { "If-None-Match": '"stale-etag"' } });
  assertEquals(res.status, 200);
  assert((await res.text()).length > 0);
});

Deno.test("landing: the ETag tracks the content", async () => {
  // Pins the property that makes it maintenance-free: it is derived from the built page, so it moves with
  // the page and cannot be forgotten on a deploy. Same content => same tag.
  const a = (await app().request("/")).headers.get("ETag");
  const b = (await app().request("/")).headers.get("ETag");
  assertEquals(a, b);
});

Deno.test("landing: HEAD / returns the headers with no body", async () => {
  const res = await app().request("/", { method: "HEAD" });
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=300");
  assertEquals(await res.text(), "");
});
