import { assert, assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import { readConfig } from "../src/config.ts";

// The event link's two public routes (capability `event-link`): the AASA that makes the link a Universal
// Link, and the `/join` App Store fallback for someone who opened an invite without the app. These tests
// exercise the served RESPONSES. The gate interaction (served without a token; the exceptions do not leak)
// lives in attest.test.ts, next to the gate itself.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
});

// Serving either route must make NO upstream request and read no storage — any call here is a failure.
const noFetch: FetchLike = () => {
  throw new Error("serving an event-link route must make no upstream request");
};
const app = () => createApp({ config: CONFIG, fetch: noFetch });

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

Deno.test("event-link: GET /join redirects to the App Store", async () => {
  const res = await app().request("/join");
  assertEquals(res.status, 302);
  assertEquals(res.headers.get("Location"), CONFIG.appStoreUrl);
});

Deno.test("event-link: /join is identical for every link and reads no event data", async () => {
  // The payload rides in the fragment, which a browser NEVER transmits — so the backend cannot
  // distinguish one invite from another even in principle. That is the design: the eventId is the upload
  // capability, and it must never reach a server log or a cache key.
  const a = await app().request("/join");
  const b = await app().request("/join?v=3&d=eyJldmVudElkIjoieCJ9"); // a query cannot happen, but must not matter
  assertEquals(a.status, b.status);
  assertEquals(a.headers.get("Location"), b.headers.get("Location"));
});

Deno.test("event-link: HEAD on both routes behaves", async () => {
  assertEquals((await app().request(AASA, { method: "HEAD" })).status, 200);
  assertEquals((await app().request("/join", { method: "HEAD" })).status, 302);
});
