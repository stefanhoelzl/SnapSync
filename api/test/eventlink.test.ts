import type { Config } from "../src/config.ts";
import { emptyStore } from "./support/db.ts";
import { assert, assertEquals } from "@std/assert";
import { NO_CACHE } from "../src/routes/support.ts";
import { createApp, type FetchLike } from "../src/app.ts";
import { DEPLOYMENT, readConfig } from "../src/config.ts";

// The event link's two public routes (capability `join-event`): the AASA that makes the link a Universal
// Link, and the `/join` no-app download page for someone who opened an invite without the app (capability
// `event-site`). These tests exercise the served RESPONSES. The gate interaction (served without a
// token; the exceptions do not leak) lives in attest.test.ts, next to the gate itself.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
  BUNNY_DATABASE_URL: "libsql://example.invalid",
  BUNNY_DATABASE_AUTH_TOKEN: "dbt",
  ADMIN_NOTIFY_KEY: "a",
});

// The AASA reads no storage — serving it must make NO upstream request (any call is a failure).
const noFetch: FetchLike = () => {
  throw new Error("serving the AASA must make no upstream request");
};
const DB = await emptyStore();
const app = () => createApp({ config: CONFIG, db: DB, fetch: noFetch });

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
  return createApp({ config: CONFIG, db: DB, fetch });
};

const AASA = "/.well-known/apple-app-site-association";

Deno.test("event-link: the AASA is served as JSON with no redirect", async () => {
  const res = await app().request(AASA);
  assertEquals(res.status, 200); // NOT a 3xx — Apple does not follow redirects for the AASA
  assertEquals(res.headers.get("Content-Type"), "application/json");
  assert((await res.text()).length > 0);
});

Deno.test("event-link: the AASA declares the app and the two /join forms only", async () => {
  const body = await (await app().request(AASA)).json();
  const details = body.applinks.details;
  assertEquals(details.length, 1);
  // The same <teamId>.<bundleId> the attestation gate uses — derived, never restated, so the AASA
  // cannot name a different app than the one that attests.
  assertEquals(details[0].appIDs, [CONFIG.attestAppId]);
  // The extension never handles URLs and must not appear.
  assert(!JSON.stringify(body).includes("BackgroundUpload"));
  // Path-only match: no query and no fragment constraint, so a malformed link still opens the app and
  // shows the invalid-link error rather than dead-ending silently in a browser. `/join` is the fragment form,
  // `/join/*` the path form (`/join/<eventId>`).
  assertEquals(details[0].components, [{ "/": "/join" }, { "/": "/join/*" }]);
});

const ASSETLINKS = "/.well-known/assetlinks.json";

/** The app with an Android attestation policy of its own. */
const appWith = (android: Partial<Config>) =>
  createApp({
    config: { ...CONFIG, ...android },
    db: DB,
    fetch: () => Promise.reject(new Error("no storage")),
  });

Deno.test("event-link: the asset links name the app and the certificates the attestation policy accepts", async () => {
  const digest = Array(32).fill("AB").join(":");
  const res = await appWith({
    androidPackageName: "app.snapsync",
    androidSigningCertDigests: [digest],
  })
    .request(ASSETLINKS);
  assertEquals(res.status, 200); // NOT a 3xx — Android's verifier does not follow redirects either
  assertEquals(res.headers.get("Content-Type"), "application/json");
  assertEquals(await res.json(), [{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: {
      namespace: "android_app",
      package_name: "app.snapsync",
      sha256_cert_fingerprints: [digest],
    },
  }]);
});

Deno.test("event-link: with no certificate named — every deployed backend until Play, and the local rig — they claim nothing", async () => {
  // `[]` is the honest file: no app may take the link from the browser, and the invite page loads there.
  assertEquals(
    await (await appWith({ androidSigningCertDigests: [] }).request(ASSETLINKS)).json(),
    [],
  );
  const digest = Array(32).fill("AB").join(":");
  const local = appWith({ androidSigningCertDigests: [digest], androidAttestationTrust: "any" });
  assertEquals(
    await (await local.request(ASSETLINKS)).json(),
    [],
    "`any` names no certificate, so it claims nothing",
  );
});

Deno.test("event-link: the backend has exactly one source for the link domain", () => {
  // Deliberately NOT `assertEquals(CONFIG.linkDomain, "<the real host>")`: that would test
  // CONFIGURATION rather than behaviour, and turn this suite red on any deployment change. What
  // matters here is that the domain comes from the resolved deployment and is a usable host. That the
  // app's entitlement and LINK_ORIGIN agree with it is now CONSTRUCTED (both are generated from the
  // same deployment) and staleness-checked in :test:architecture.
  assertEquals(CONFIG.linkDomain, DEPLOYMENT.domain);
  assert(CONFIG.linkDomain.length > 0 && !CONFIG.linkDomain.includes("/"));
});

Deno.test("event-link: GET /join serves the no-app download page", async () => {
  const res = await siteApp().request("/join");
  assertEquals(res.status, 200); // a static page (proxied from site/), not a 302 to the App Store
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), NO_CACHE); // filled per request, never cached
  assert((await res.text()).length > 0);
  // The download control and the App Store link are part of the page BUILT by site/ and checked there.
});

Deno.test("event-link: /join is identical for every fragment link and reads no event data", async () => {
  // A fragment invite's payload is never transmitted, so `/join` cannot tell one invite from another: it
  // serves the same generic page, whose island moves the browser to the event's own page `/join/<eventId>`
  // (capability `event-site`; that page is pinned in download.test.ts).
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
