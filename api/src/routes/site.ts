// The ROOT routes (capabilities `web-site`, `event-site`, `join-event`): the site proxied from the storage
// `site/` prefix, the two link-association documents, and the deploy's boot probe. Never under `/api/vN`,
// and never gated by the maintenance window.

// The browser-facing pages (capabilities `web-site` at `/` and `event-site` at `/join`) are
// no longer embedded here — they are built by the `site/` Astro module and served by proxying the storage
// `site/` prefix (capability `web-site`, see `serveSiteObject` + the `/`, `/join`, and `/_astro/*` routes
// below). The `shots` pipeline that inlined the landing screenshots is gone with them.

import { Hono } from "hono";
import type { Config } from "../config.ts";
import { type FetchLike, storageReachable } from "../storage.ts";
import { NO_CACHE, type RouteDeps } from "./support.ts";

// PUBLIC and static — the deliberate inverse of the listings' NO_CACHE. A `public` directive lets the
// bunny pull zone serve it from the edge, keeping the Edge Script off the request hot path. Still used by
// the AASA and (until Phase 2) the `/join` page.
const PUBLIC_CACHE = "public, max-age=300";

// Cache policy for the proxied `site/` objects (capability `web-site`). HTML entry points are the
// always-fresh shell — `no-cache` so a deploy is picked up immediately; the pull zone still revalidates
// cheaply. Fingerprinted assets are addressed by content hash, so they are immutable for a year — the hash
// is the version, and a changed asset gets a new URL.
const SITE_HTML_CACHE = "no-cache";
const SITE_ASSET_CACHE = "public, max-age=31536000, immutable";

// Content-Type by extension for proxied `site/` objects — deterministic, so we do not rest the served
// type on the storage API's guess. Falls back to octet-stream.
function siteContentType(sitePath: string): string {
  if (sitePath.endsWith(".html")) return "text/html; charset=utf-8";
  if (sitePath.endsWith(".webp")) return "image/webp";
  if (sitePath.endsWith(".css")) return "text/css; charset=utf-8";
  if (sitePath.endsWith(".js") || sitePath.endsWith(".mjs")) {
    return "text/javascript; charset=utf-8";
  }
  if (sitePath.endsWith(".svg")) return "image/svg+xml";
  if (sitePath.endsWith(".png")) return "image/png";
  if (sitePath.endsWith(".json")) return "application/json; charset=utf-8";
  if (sitePath.endsWith(".ico")) return "image/x-icon";
  if (sitePath.endsWith(".woff2")) return "font/woff2";
  return "application/octet-stream";
}

/**
 * Serve a built page/asset by streaming it from the storage `site/` prefix (capability `web-site`). The api
 * owns this routing in source (no pull-zone edge rules, no account key); the pull zone caches the response
 * by the `Cache-Control` we set here, so only cold misses reach the script. `sitePath` is the storage key
 * under `site/` (e.g. `index.html`, `_astro/app.<hash>.js`). `HEAD` returns the headers with no body. A
 * missing object is `404`; any other upstream failure is `502` — the same faithful-outcome contract as the
 * rest of the api (never a false success, never a partial body mislabelled `200`).
 */
async function serveSiteObject(
  fetchImpl: FetchLike,
  config: Config,
  sitePath: string,
  method: string,
  cacheControl: string,
): Promise<Response> {
  const url = `https://${config.host}/${config.zone}/site/${sitePath}`;
  let upstream: Response;
  try {
    upstream = await fetchImpl(url, { method: "GET", headers: { AccessKey: config.accessKey } });
  } catch (e) {
    console.error(`site: upstream GET errored for site/${sitePath}: ${e}`);
    return new Response("upstream error", { status: 502 });
  }
  if (upstream.status === 404) {
    await upstream.body?.cancel();
    return new Response("not found", { status: 404 });
  }
  if (!upstream.ok) {
    await upstream.body?.cancel();
    console.error(`site: bunny returned ${upstream.status} for site/${sitePath}`);
    return new Response("upstream error", { status: 502 });
  }
  const headers = new Headers({
    "Content-Type": siteContentType(sitePath),
    "Cache-Control": cacheControl,
  });
  if (method === "HEAD") {
    await upstream.body?.cancel();
    return new Response(null, { status: 200, headers });
  }
  return new Response(upstream.body, { status: 200, headers });
}

/** The root routes, served from `createApp`'s app at `/`. `buildSha` is what `/health` answers with. */
export function siteRoutes({ fetchImpl, config, db }: RouteDeps, buildSha: string): Hono {
  const app = new Hono();

  // The public marketing/landing page (capability `web-site`, built by `web-site`): served by
  // proxying `site/index.html` from storage. The HTML entry point is `no-cache` — the always-fresh shell —
  // so a deploy is picked up immediately; it references immutable, content-hashed `/_astro/*` assets. The
  // token gate (`app.ts`) admits `/` (GET/HEAD).
  app.on(
    ["GET", "HEAD"],
    "/",
    (c) => serveSiteObject(fetchImpl, config, "index.html", c.req.method, SITE_HTML_CACHE),
  );

  // The landing page's fingerprinted assets (capability `web-site`): served by proxying `site/_astro/*`
  // from storage with a year-long immutable cache — the content hash in the name is the version, so the
  // pull zone serves repeat hits from the edge and only cold misses reach the script. The wildcard segment
  // after `/_astro/` is the storage key tail. The token gate (`app.ts`) admits `/_astro/*` (GET/HEAD).
  app.on(["GET", "HEAD"], "/_astro/*", (c) => {
    const tail = new URL(c.req.url).pathname.slice("/".length); // "_astro/app.<hash>.js"
    return serveSiteObject(fetchImpl, config, tail, c.req.method, SITE_ASSET_CACHE);
  });

  // The Apple App Site Association document (capability `join-event`): what makes the event link a
  // Universal Link instead of a web page. Apple's CDN and the device fetch it unauthenticated, so the
  // token gate (`app.ts`) admits it; it MUST be served as application/json with NO redirect.
  //
  // `appIDs` is `config.attestAppId` — the same `<teamId>.<bundleId>` the attestation gate gnaws on —
  // derived, never restated, so the AASA cannot drift from the app it names. The extension is absent
  // deliberately: it never handles URLs.
  //
  // The path is matched with `components` on `/join` ALONE — no query, no fragment constraint. That is
  // deliberate on two counts. A malformed link then still opens the app and surfaces the invalid-link
  // error rather than dead-ending invisibly in a browser (a visible failure beats a silent one). And it
  // sidesteps the documented iOS bug where a `?` nested inside a `#` cannot be matched — we ask for
  // neither. Narrow matching also keeps `/`, `/events/:id`, and `/attest/*` opening in a browser; a
  // broad `/*` would hijack our own marketing page into the app.
  const aasa = JSON.stringify({
    applinks: { details: [{ appIDs: [config.attestAppId], components: [{ "/": "/join" }] }] },
  });
  app.on(["GET", "HEAD"], "/.well-known/apple-app-site-association", (c) => {
    c.header("Cache-Control", PUBLIC_CACHE);
    c.header("Content-Type", "application/json");
    return c.req.method === "HEAD" ? c.body(null) : c.body(aasa);
  });

  // The Android counterpart (capability `join-event`): Digital Asset Links, which Android's verifier fetches to
  // let the app claim `https://<domain>/join` (the intent filter narrows the path; this file names the app). It
  // names the package and the signing certificates the attestation policy accepts — ONE list
  // (`androidSigningCertDigests`), so the app a link opens is the app that may attest. Empty under trust `any`
  // (which names no certificate) and wherever no certificate is named: `[]` claims nothing, so no app can take
  // the link from the browser.
  const assetlinks = JSON.stringify(
    config.androidAttestationTrust === "hardware" && config.androidSigningCertDigests.length > 0
      ? [{
        relation: ["delegate_permission/common.handle_all_urls"],
        target: {
          namespace: "android_app",
          package_name: config.androidPackageName,
          sha256_cert_fingerprints: config.androidSigningCertDigests,
        },
      }]
      : [],
  );
  app.on(["GET", "HEAD"], "/.well-known/assetlinks.json", (c) => {
    c.header("Cache-Control", PUBLIC_CACHE);
    c.header("Content-Type", "application/json");
    return c.req.method === "HEAD" ? c.body(null) : c.body(assetlinks);
  });

  // The no-app download page (capabilities `join-event`, `event-site`, built by `web-site`): the
  // path a browser requests when an event link is opened on a device with no app to claim it. Served by
  // proxying the CONSTANT `site/join/index.html` object from storage — byte-identical for every link, and
  // `no-cache` (the always-fresh shell). GET returns the page; HEAD returns the headers with no body.
  //
  // The handler does not — cannot — read the payload: that rides in the URL fragment, which a browser never
  // transmits, so this handler sees `/join` and nothing more, and it reads the same constant object for
  // every request (no per-event state). Everything per-event — the event name, the photo union, the zip —
  // is done by the page's own client island, off the fragment. The eventId never reaches the server here.
  app.on(
    ["GET", "HEAD"],
    "/join",
    (c) => serveSiteObject(fetchImpl, config, "join/index.html", c.req.method, SITE_HTML_CACHE),
  );

  // The BOOT PROBE's target (`docs/deployment.md`). Answers with the commit this bundle was
  // built from, so deploy.yml's `api` job can tell the deploy it just made from the one that was already live —
  // `POST /code` + `POST /publish` succeed whether or not the bundle can boot, and a bare `200` cannot
  // distinguish a new deployment from an old corpse still being served.
  //
  // TOTAL BY CONSTRUCTION: it returns 200 or it did not run. `readConfig` is called at module top level in
  // `main.ts`, OUTSIDE this app, so a configuration failure means the script never serves at all rather
  // than that this route answers with an error — a `503` branch here would be dead code. Distinguishing
  // causes is the probe's job, from the combination of status and body.
  //
  // `NO_CACHE` because the pull zone must never answer a probe from the PREVIOUS deploy's copy — which is
  // exactly the false green this exists to prevent. Root-mounted, never under `/api/v1`: no device calls
  // it, so a future `/api/vN` should neither duplicate nor strand it. It is also NOT gated by the
  // maintenance middleware (`app.ts`) — it is how the deploy learns the window's state, so gating it would
  // blind the thing that lifts the window.
  //
  // IT REPORTS `maintenance` ONLY WHEN THE WINDOW IS OPEN, and its ABSENCE MEANS CLOSED. The two bundles a
  // migrating deploy publishes are built from the SAME COMMIT, so `sha` alone cannot tell them apart —
  // this field is what does, and the probe asserts it in both directions.
  //
  // The absence is a DELIBERATE COLLAPSE, and it is safe because both causes it absorbs are the same
  // answer: a bundle that predates the flag, and a bundle whose flag is false. The first is not merely
  // *probably* not in maintenance — maintenance mode did not exist when it was built, so it is NECESSARILY
  // serving the device API. Nothing else can produce the absence: a response from something that is not
  // this backend fails the `sha` check first, and this commit's own bundle always carries the field when
  // the window is open.
  //
  // IT IS NO LONGER INERT, and that is a deliberate trade. It now reaches BOTH dependencies this
  // deployment has — the relational store and the storage zone — because a bundle identifier alone cannot
  // say whether either is addressable. The storage half is new coverage and closes the documented half of
  // the 2026-07 outage that the probe could not previously see: a `BUNNY_STORAGE_ZONE` that is present but
  // names a zone that does not exist boots and probes green. Serving this unauthenticated is still the
  // right call, and the reason has changed: it is no longer "the cheapest route in the backend" but "the
  // probe carries no credential this backend accepts, so any route it can reach is equally ungated" —
  // moving the checks behind another path would relocate the exposure, not remove it.
  //
  // AN UNREACHABLE DEPENDENCY IS A BARE NON-SUCCESS, not a `200` describing itself. The route used to
  // report the store's state in the body so the probe could split a TERMINAL cause (foreign keys off) from
  // a RETRYABLE one (unreachable); with the foreign-key assertion gone (`docs/architecture.md` — the
  // measurement is now trusted, and what would falsify it is recorded there) the only condition left is
  // unreachability, which is retryable, and a non-success status already carries that. The cost, accepted:
  // a red probe says `server-error` rather than naming which dependency was unreachable — so the causes
  // are logged here, where a human reading the script's output can still tell them apart.
  //
  // BOTH CHECKS RUN, always, even when the first fails: a deploy that has broken both should not need two
  // rounds to find out.
  app.on(["GET", "HEAD"], "/health", async (c) => {
    c.header("Cache-Control", NO_CACHE);
    c.header("Content-Type", "application/json");
    const [store, zone] = await Promise.all([
      db.execute("SELECT 1").then(() => true).catch((e) => {
        console.error(`health: relational store unreachable: ${e}`);
        return false;
      }),
      storageReachable(fetchImpl, config).then((ok) => {
        if (!ok) console.error(`health: storage zone '${config.zone}' unreachable`);
        return ok;
      }),
    ]);
    if (!store || !zone) return c.body(null, 503);
    const body = JSON.stringify(
      config.maintenance ? { sha: buildSha, maintenance: true } : { sha: buildSha },
    );
    return c.req.method === "HEAD" ? c.body(null) : c.body(body);
  });
  return app;
}
