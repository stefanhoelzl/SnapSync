import { assert, assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import { readConfig } from "../src/config.ts";

// The event link's two public routes (capability `event-link`): the AASA that makes the link a Universal
// Link, and the `/join` no-app download page for someone who opened an invite without the app (capability
// `web-event-download`). These tests exercise the served RESPONSES. The gate interaction (served without a
// token; the exceptions do not leak) lives in attest.test.ts, next to the gate itself.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
  ADMIN_NOTIFY_KEY: "a",
});

// The AASA reads no storage — serving it must make NO upstream request (any call is a failure).
const noFetch: FetchLike = () => {
  throw new Error("serving the AASA must make no upstream request");
};
const app = () => createApp({ config: CONFIG, fetch: noFetch });

// `/join` is now built by `site/` and PROXIED from the constant `site/join/index.html` object; this app
// injects a fake storage that serves it (capability `web-site`).
const JOIN_HTML = "<!doctype html><title>SnapSync — event photos</title><body>join</body>";
const siteApp = () => {
  const fetch: FetchLike = (url) =>
    Promise.resolve(
      url.endsWith("/site/join/index.html")
        ? new Response(JOIN_HTML, { status: 200 })
        : new Response("nf", { status: 404 }),
    );
  return createApp({ config: CONFIG, fetch });
};

const AASA = "/.well-known/apple-app-site-association";

Deno.test("event-link: the AASA is served as JSON with no redirect", async () => {
  const res = await app().request(AASA);
  assertEquals(res.status, 200); // NOT a 3xx — Apple does not follow redirects for the AASA
  assertEquals(res.headers.get("Content-Type"), "application/json");
  assert((await res.text()).length > 0);
});

Deno.test("event-link: the AASA declares the app and the /join path only", async () => {
  const body = await (await app().request(AASA)).json();
  const details = body.applinks.details;
  assertEquals(details.length, 1);
  // The same <teamId>.<bundleId> the attestation gate uses — derived, never restated, so the AASA
  // cannot name a different app than the one that attests.
  assertEquals(details[0].appIDs, [CONFIG.attestAppId]);
  // The extension never handles URLs and must not appear.
  assert(!JSON.stringify(body).includes("BackgroundUpload"));
  // Path-only match: no query and no fragment constraint, so a malformed link still opens the app and
  // shows the invalid-link error rather than dead-ending silently in a browser.
  assertEquals(details[0].components, [{ "/": "/join" }]);
});

Deno.test("event-link: the AASA's domain matches the configured link domain", () => {
  // The guard that the app's entitlement/LINK_ORIGIN agree with this lives in :test:architecture —
  // Gradle cannot reach backend/. Here we only pin that the backend has one source for it.
  assertEquals(CONFIG.linkDomain, "snapsync.stho.net");
});

Deno.test("event-link: GET /join serves the no-app download page", async () => {
  const res = await siteApp().request("/join");
  assertEquals(res.status, 200); // a static page (proxied from site/), not a 302 to the App Store
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), "no-cache"); // the always-fresh shell (web-site)
  assert((await res.text()).length > 0);
  // The download control and the App Store link are part of the page BUILT by site/ and checked there.
});

Deno.test("event-link: /join is identical for every link and reads no event data", async () => {
  // The payload rides in the fragment, which a browser NEVER transmits — so the backend cannot
  // distinguish one invite from another even in principle. That is the design: the eventId is the read
  // capability, and it must never reach a server log or a cache key. The served bytes are the same for
  // every link; anything per-event is done by the page's own JS off the fragment, client-side.
  const a = await siteApp().request("/join");
  const b = await siteApp().request("/join?v=3&d=eyJldmVudElkIjoieCJ9"); // a query cannot happen, but must not matter
  assertEquals(a.status, 200);
  assertEquals(b.status, 200);
  assertEquals(await a.text(), await b.text());
});

Deno.test("event-link: HEAD on both routes behaves", async () => {
  const aasa = await app().request(AASA, { method: "HEAD" });
  await aasa.body?.cancel();
  assertEquals(aasa.status, 200);
  const join = await siteApp().request("/join", { method: "HEAD" });
  await join.body?.cancel();
  assertEquals(join.status, 200);
});
