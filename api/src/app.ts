// Hono app for the backend: the whole device API, composed here (capabilities `docs/architecture.md` for the route
// shapes, `database` for what each one reads and writes, `privacy-security` for the gate, plus
// `event-lifetime`, `manage-membership`, `manage-membership`, `receiving-photos`, `web-site` and `join-event`, over the
// shared `docs/deployment.md`).
//
// THIS FILE holds the three gates (maintenance, version, token) and the composition; the routes live in
// `routes/*.ts` as factories over one set of dependencies — `site.ts` (the root routes), `attest.ts` (the
// token issuers), `shared.ts` (what every version serves), `v1.ts` and `v2.ts` (what only one does), and
// `support.ts` (the device binding, the refusals and the event gate every route shares).
//
// VERSIONED PREFIX (`docs/deployment.md`): every device-API route below is served under the
// prefix `/api/v1` — the paths are written that way here, and that is the one shape they answer at. The
// web/link routes (`/`, `/join`, the AASA) stay at the ROOT only, never under `/api/v1`. The routing is
// version-parametric: a future `/api/v2` is one more mount in `createApp`.
//
// ── WHERE STATE LIVES ─────────────────────────────────────────────────────────────────────────────
//
// THE DATABASE HOLDS THE FACTS; STORAGE HOLDS THE BYTES. An event exists iff its `events` row exists; a
// membership is a row with a state, not a pair of objects whose timestamps are compared; the union is one
// join. This module therefore reaches storage for exactly one thing — the photo bytes — and everything
// else is a statement in `db.ts` (`docs/architecture.md`).
//
// A `devices` ROW EXISTS IFF THAT DEVICE HAS ATTESTED. That is forced by the gate rather than chosen:
// every route but `/attest/*` needs a token, and a token needs an attestation, so no device can reach any
// other device-scoped write first. It is why the push registration UPDATEs and never inserts.
//
// ── THE GATE (capability `privacy-security`) ────────────────────────────────────────────────────
//
// EVERY ROUTE REQUIRES A DEVICE TOKEN — obtainable only by completing App Attest or an Android key
// attestation, so the API is callable by a genuine, unmodified SnapSync on a genuine Apple device or a
// verified Android phone and by nothing else. (The Android attestation mints the token only where the
// deployment names the app's signing certificate — on a deployed backend, Play's app signing key —
// `android-attest.ts`.) The exceptions are a
// CLOSED LIST, and each is exempt for its own stated reason (see the middleware in `createApp`):
//
//   * the three `/attest/*` issuers — self-authenticating; they cannot require the token they mint.
//   * `OPTIONS` on any path — the pull zone may answer the preflight itself, so the script cannot gate it.
//   * `GET`/`HEAD` on exactly `/`, `/join` and the AASA — static, source-owned, read no storage.
//   * `GET`/`HEAD` on `/health` — the post-deploy probe runs before any credential exists.
//   * `GET`/`HEAD` on `/api/v1/events/<id>` and `/api/v1/events/<id>/files` — the no-app download page
//     holds no attestation, and possession of the eventId IS the read capability. Every non-GET method on
//     those paths stays gated.
//
// A TOKEN ACTS ONLY FOR ITS OWN DEVICE: every route naming a device id in its path refuses (403) any id
// but the one the token was minted for (`actsFor`). Device ids are public — the union lists them — so a
// genuine install could otherwise act as any member.
//
// VERIFYING A TOKEN TOUCHES NOTHING: one HMAC comparison, no read, no Apple call. That is load-bearing
// rather than an optimisation — verification runs on the streaming byte-upload path, where a round-trip
// per resource would be paid on every photo. A route that additionally needs the device's RECORD reads it
// itself, after the gate has passed; the gate never does.
//
//   GET /api/v1/attest/challenge
//     → a stateless, HMAC-signed, time-bounded nonce. Writes NOTHING.
//   POST /api/v1/attest/token
//     → verifies an App Attest attestation (chain → Apple's root, nonce, app-id hash, counter, aaguid),
//       records the attested key AND the minted token's expiry as the device's row, then mints a 30-day
//       bearer token. That record IS the device's enrolment. PERSISTS BEFORE MINTING: a token handed out
//       against a record we failed to write is a credential nothing knows about → 502, mint nothing.
//   POST /api/v1/attest/renew
//     → verifies a local Secure-Enclave ASSERTION against that stored key — no Apple round-trip, because
//       re-attestation is the throttled path — advances the recorded expiry, THEN mints. 401 when no
//       record is on file (attest afresh); 502 when the store cannot be read or written, because absence
//       and "could not ask" have different remedies and must not collapse.
//   Under /api/v2 both issuers answer a stale challenge `409 stale challenge` instead of v1's `401`
//   (see `attestIssuers`), and the mint takes a typed `proof` — `{format: "apple-appattest", keyId,
//   attestation}` or `{format: "android-key", chain}` (an Android Keystore key attestation,
//   `android-attest.ts`) — whose format chooses the verifier; renewal verifies by the platform the stored
//   row PROVED (`attest_platform`), an App Attest assertion or an Android signature. v1 stays flat and
//   App Attest only.
//
//   POST /api/v1/events
//     → mints an event: INSERTs the `events` row, stamping `capacity` and the `lifetimeSeconds` DURATION
//       and validating the creator's `endsAt` against the configured window maximum (capability
//       `event-lifetime`); returns {eventId,name,createdAt,startsAt,endsAt,capacity,deletesAt}.
//   GET /api/v1/events/:eventId
//     → the event row with the DERIVED `deletesAt`; 404 when absent. Never deletes on touch, even past
//       the deadline. UNGATED (GET/HEAD only).
//   PATCH /api/v1/events/:eventId
//     → renames (capability `manage-membership`): the ONLY write to an existing event row, and it sets `name`
//       ALONE — every other column is write-once, which is why the statement is spelled out in `db.ts`
//       rather than composed. No ownership check (there is no owner); the token gate is the whole
//       authorization.
//   PUT /api/v1/devices/:deviceId
//     → the push registration (capability `receiving-photos`): UPDATEs the device's push columns and
//       NEVER inserts. 401 when it affects no row — the token verified, but we hold no attestation for
//       this device. The shipped client recovers unaided: the 401 drops its token, it attests (which
//       creates the row), and re-sends the registration when the new credential arrives. A 201 here would
//       be a silent absence — the device would believe it is reachable while no push could reach it.
//   POST /api/v1/events/:eventId/notify
//     → a fixed SILENT (content-available) push to every PRESENT member (members who left are skipped).
//       GATED on the event row (404/502). Members come from one query, each member's token from its row;
//       the fan-out is best-effort — a member with no registered token is skipped and a per-token failure
//       never fails the request. Bare 202; 502 only if the member read fails.
//   PUT /api/v1/files/devices/:deviceId/:filename
//     → streams the request body into ONE bunny native Storage PUT, then BEST-EFFORT records the resource
//       row as uploaded (a failure there never changes the response — the response is the storage
//       outcome). Requires the token but reads NO event: bytes are device-partitioned and
//       event-independent, uploaded once and linked into events by reference. The path's device id must
//       be the one the token was minted for (403 otherwise — `actsFor`), like every route naming a device.
//       The OS performs this PUT and DOES carry the header (verified on device). There is no download GET here; the listing hands out a presigned S3 URL.
//   GET /api/v1/files/devices/:deviceId
//     → the device's uploaded resources, from ONE query — no storage LIST. Each entry is
//       `{ filename, url }`, where `filename` is the STORED OBJECT KEY (what the rejoin reconciler matches
//       its ledger against, capability `photo-sharing`) and `url` is a presigned S3 GET.
//       `Cache-Control: no-store, no-cache, max-age=0` (time-limited urls; see NO_CACHE — the pull zone
//       honors `no-cache`, not `no-store`).
//   PUT /api/v1/events/:eventId/devices/:deviceId
//     → publishes the device manifest. GATED on existence AND on CAPACITY by ONE conditional statement
//       (capability `event-lifetime`): a device never enrolled is refused 409 once `capacity` distinct ids
//       have ever enrolled — leaving frees no slot, a rejoin reuses its own — and a zero-row outcome is
//       disambiguated into 409-vs-404 by a follow-up read rather than collapsed. Capacity is the ONLY
//       refusal; enrollment is never closed by time, however long after `endsAt` it arrives. The write is
//       ONE ATOMIC BATCH: a membership that had left → sharing, the membership's assets REPLACED with exactly what the body
//       lists (an omitted asset is removed), each named resource upserted with `uploaded` MONOTONE.
//   DELETE /api/v1/events/:eventId/devices/:deviceId
//     → LEAVE (capability `manage-membership`): the membership becomes `done` or `left` (`?received=true`
//       is the device's word that it holds every photo of the others). GATED on the event row (404/502),
//       idempotent, and NON-DESTRUCTIVE — the assets are RETAINED, so the union keeps serving what the
//       device shared. No reap here and no leave-time GC. When this was the last member still present the
//       event becomes EMPTY and the nightly sweep reclaims it on its next run; after the end, the leave of
//       the last member still `sharing` closes the event.
//   GET /api/v1/events/:eventId/files
//     → the event-wide UNION, as ONE query joining the event's assets to their resources across EVERY
//       membership, present or gone (a member who left keeps contributing what it already shared). An asset
//       naming a resource with no recorded upload is dropped — the PRIMARY completeness mechanism, since
//       a manifest declares what its device will provide rather than what it has already uploaded.
//       Faithful: any read failure → 502, never a partial union. UNGATED
//       (GET/HEAD only). Identity-blind: own-vs-foreign skip is the client's concern.
//       `Cache-Control: no-store, no-cache, max-age=0`.
//   GET /health
//     → the post-deploy boot probe (`docs/deployment.md`): this bundle's stamped SHA and
//       the store's foreign-key posture, reported SEPARATELY so a misprovisioned store is distinguishable
//       from one that is merely still starting.
//
// ── EVENT LIFECYCLE (capability `event-lifetime`) ───────────────────────────────────────────────────
//
// Every event-scoped route resolves its event through ONE gate (`gateEvent`), and the lifecycle is
// BINARY — the event exists, or the sweep has deleted it. `endsAt` is NOT a lifecycle input: it bounds
// only which captures may be UPLOADED, so nothing closes when the window does (in particular, JOINING IS
// NEVER CLOSED BY TIME — a guest who scans days late still holds in-window captures that belong in the
// event).
//
// The nightly sweep (capability `event-lifetime`, run out-of-edge from GitHub Actions) is the ONLY
// deleter. It reclaims an event past its derived delete-by (`max(createdAt, startsAt) + lifetimeSeconds`
// — the guarantee) or EMPTY (ever joined, no member still present — opportunistic, since a leave whose
// DELETE never landed keeps a membership present). No route reaps on touch, even past the deadline: that
// is what makes a 404 a REAL deletion, and therefore safe as one of the two witnesses the client's
// self-leave requires (capability `manage-membership`).
//
// The token check ALWAYS runs before the existence gate, so an unauthenticated caller cannot tell an
// existing event from a missing one — except on the two routes the closed list deliberately opens, where
// eventId-possession is itself the read capability.
//
// ── THE BYTE ROUTE ────────────────────────────────────────────────────────────────────────────────
//
// The per-device byte WRITE route is defined on a child Hono (`byteFile`, `routes/v1.ts`) and mounted under
// `/files/devices/:deviceId/:filename` via app.route(), so PUT (upload) and OPTIONS share it.
// `deviceId`/`filename` are Hono's decoded path params (typed `string | undefined` through a mount, hence
// the guard); the filename is re-encoded per-segment when building the bunny URL, so the stored object is
// the real filename and keys stay flat. Config is injected (validated at startup). Upload invariants:
// pass-through only (never buffer/hash), faithful outcome (2xx only on confirmed store), last-write-wins.
// There is NO download route: the listing's `url` is a presigned S3 GET the device fetches directly from
// bunny's S3 endpoint (the short-read integrity check moves to the client).

import { Hono } from "hono";
import { AwsClient } from "aws4fetch";
import { APP_VERSION_HEADER, compareVersions, splitVersion } from "./version.ts";
import { BUILD_SHA, type Config } from "./config.ts";
import { createPushSender } from "./push.ts";
import { verifyToken } from "./attest.ts";
import type { FetchLike } from "./storage.ts";
import type { Db } from "./db.ts";
import { attestRoutes } from "./routes/attest.ts";
import { sharedRoutes } from "./routes/shared.ts";
import { siteRoutes } from "./routes/site.ts";
import { NO_CACHE, type RouteDeps } from "./routes/support.ts";
import { v1Routes } from "./routes/v1.ts";
import { v2Routes } from "./routes/v2.ts";

// Re-exported so existing importers (tests, callers) keep their `from "./app.ts"` imports working.
export type { FetchLike } from "./storage.ts";

export type Deps = {
  /** Upstream fetch (global fetch in production; a fake in tests). */
  fetch: FetchLike;
  /**
   * The fetch Google's public attestation status list is read through (`android-attest.ts`) — NOT {@link fetch},
   * which is the STORAGE upstream: the local rig's filesystem shim behind it refuses every URL outside the zone,
   * and a test's recorder counts it. Defaults to the global fetch; a test without network permission then answers
   * "could not look" (a 502), never a verdict.
   */
  revocationFetch?: FetchLike;
  /** Validated storage config (built at startup via readConfig). */
  config: Config;
  /**
   * The relational store (`docs/architecture.md`). Injected like {@link fetch}: production passes the
   * libSQL driver built in `main.ts`, tests pass an in-process `node:sqlite` one. The port is narrow
   * enough that both are the same few methods, and neither can be mistaken for the other at a call site.
   */
  db: Db;
  /**
   * Wall clock, in epoch ms. Injected so tests can pin it — the device token and the challenge are both
   * time-bounded, and a test for "an expired token is refused" cannot wait 30 days. Defaults to `Date.now`.
   */
  now?: () => number;
  /**
   * The commit this bundle was built from, served by `GET /health` so the post-deploy probe can tell THIS
   * bundle from the previous one still being served (`docs/deployment.md`).
   *
   * A DEPENDENCY, not configuration — which is why it sits here beside {@link now} rather than on
   * `Config`: it varies per build, not per deployment, and a test must be able to pin it. Reading it as a
   * module-level import instead would make the health test assert against whatever the generated file
   * happened to hold. Defaults to the value resolved into this bundle.
   */
  buildSha?: string;
};

// What a maintenance `503` suggests waiting (`docs/deployment.md`). HTTP pairs `Retry-After`
// with `503`, which is the status it defines for scheduled maintenance (RFC 9110 §15.6.4).
//
// IT IS A POLL INTERVAL, NOT AN ESTIMATE OF THE WINDOW, and that distinction is what makes the number
// defensible rather than invented. Every `503` re-issues this header, so a caller that honours it asks
// again, gets a fresh hint, and converges — which means the value only has to answer "how long until it
// is worth asking again", never "how long will this last".
//
// That framing is what keeps it robust to a window whose length nobody can predict. The MIGRATION is not
// the term that sets the duration — v1–v3 are small DDL batches, milliseconds against the remote store.
// What dominates is TWO publishes and their propagation, which `probe.ts` polls at 5 s intervals with a
// 120 s deadline apiece. So the window plausibly runs anywhere from ~10 s (propagation instant, each
// probe satisfied on its first request) to ~250 s (both probes near their deadlines). A single constant
// cannot name that range; a poll interval does not have to.
//
// UNDER-ESTIMATING IS THE SAFER ERROR, which is why this sits below even the fast case. Too low costs a
// few wasted requests during the window. Too high keeps a caller that honours it away AFTER the service
// is back — unavailability we would be inflicting ourselves, past the outage we actually chose.
//
// Deliberately NOT tied to `probe.ts`'s interval: they answer different questions (how fast may CI ask
// again vs how fast may a stranger), and coupling them would be false precision. No shipped SnapSync
// client reads this at all — the upload engine retries forever with no attempt budget, and create/join
// map any unrecognised status to their existing transient states — so its audience is a human, a proxy,
// or a curl.
const MAINTENANCE_RETRY_AFTER_SECONDS = 30;

export function createApp(
  {
    fetch: fetchImpl,
    config,
    db,
    now = Date.now,
    buildSha = BUILD_SHA,
    revocationFetch = (url, init) => fetch(url, init),
  }: Deps,
): Hono {
  // The S3 signer used ONLY to presign download URLs (`docs/architecture.md`). Access Key ID =
  // the zone name, secret = the storage-zone `AccessKey`; pure Web-Crypto, no network. Uploads/reads/
  // listings stay on the native API and are not signed with this.
  const aws = new AwsClient({
    accessKeyId: config.zone,
    secretAccessKey: config.accessKey,
    region: config.s3Region,
    service: "s3",
  });

  // The silent-wake sender (capability `receiving-photos`): each token through the push service its kind names —
  // APNs for an iPhone, FCM for an Android phone — each memoizing its own credential across sends. Every wake goes
  // through it: the notify fan-out and the close.
  const pushSender = createPushSender(config, fetchImpl);
  const deps: RouteDeps = { fetchImpl, revocationFetch, config, db, now, aws, pushSender };

  const app = new Hono();

  // ── THE MAINTENANCE GATE (`docs/deployment.md`) ──────────────────────────────────────
  //
  // While this bundle carries the maintenance flag, every route under `/api/` answers `503` and touches
  // neither storage nor the database. It exists to close the one interval the deploy pipeline could not:
  // between a migration landing and the bundle written against it serving, the PREVIOUS bundle answers
  // requests against the MIGRATED store, with statements written for a shape that no longer exists.
  //
  // REGISTERED FIRST — ahead of the token gate below — so maintenance wins over `401`. That is cheaper (no
  // HMAC verification) and truthful: the service is unavailable, and the caller's credentials are not what
  // is wrong. It discloses nothing `/health` does not already disclose publicly.
  //
  // MATCHED AS A PREFIX, NEVER AS A LIST OF ROUTES, and that is the whole design rather than a shortcut.
  // A closed list can be omitted from — a route added later lands ungated by nobody's decision — whereas
  // a prefix cannot be, and a future `/api/v2` mount inherits this by construction. It is also why the
  // gate is here rather than wrapped around the `Db` and storage ports: port decoration would gate by
  // USAGE, which is the better property on paper, but this file's best-effort `catch` blocks deliberately
  // swallow (`markUploaded` logs and still answers `201`), so a thrown maintenance error would let a byte
  // upload stream to storage, silently lose its `resources` row, and report success — after which the
  // device never retries. Decision record: `changes/add-deploy-maintenance-mode/design.md` D1.
  //
  // ROOT ROUTES ARE NOT GATED: `/`, `/join`, `/_astro/*` and the AASA read only the public storage `site/`
  // prefix or nothing at all, so a schema migration has no bearing on them — and `/health` is how the
  // deploy learns the window's state, so gating it would blind the step that lifts the window.
  //
  // `NO_CACHE` IS LOAD-BEARING HERE, not decoration: the pull zone caches on the origin's directives, and
  // a cached `503` would outlive the window — turning a bounded, deliberate outage into an unbounded
  // accidental one. CI cannot configure the pull zone (that needs the account key), so this header is the
  // only lever, and its behaviour is verified THROUGH the pull zone rather than at the origin.
  //
  // Downloads now pass through here (decision record `changes/incremental-union`, D1): each starts at the
  // redirect route, which reads the store, so a maintenance window answers it `503` too. A download that
  // already took its redirect is unaffected — its presigned URL goes straight to bunny's S3 endpoint — and
  // one refused here is retried (Android) or re-enqueued by the next reconcile (iOS).
  if (config.maintenance) {
    // `next` is deliberately never called: this middleware SHORT-CIRCUITS, so no handler runs and no
    // upstream request is made. `async` because Hono's middleware signature returns a promise.
    app.use("/api/*", async (c) => {
      c.header("Cache-Control", NO_CACHE);
      c.header("Retry-After", String(MAINTENANCE_RETRY_AFTER_SECONDS));
      return await Promise.resolve(c.text("maintenance", 503));
    });
  }
  // ── THE VERSION GATE (capability `app-update-required`) ─────────────────────────────────────────────
  //
  // Registered BEFORE the token gate, and that ordering is deliberate — it inverts `docs/architecture.md`'
  // "on a gated route the token check comes first" for three reasons:
  //
  //   * it reads NOTHING upstream — no storage, no database, no Apple call — so it cannot grow the bill
  //     or reach user data, which is the same property that makes an unmatched path's 404 safe ahead of
  //     authorization;
  //   * a build below the minimum cannot be helped by a valid token, so verifying one first spends work
  //     on a request that is refused either way;
  //   * an old build holding an EXPIRED token would otherwise be told `401` — reporting an
  //     authentication problem to a user whose actual remedy is to update the app.
  //
  // It must be a TOP-LEVEL middleware rather than one mounted on the v2 router: Hono runs a parent's
  // middleware for mounted sub-apps, so anything registered on the v2 mount would run AFTER the token
  // gate, which is precisely the order this exists to avoid.
  //
  // v1 is exempt. It is spoken by builds that predate this header and cannot be updated to send it, so
  // requiring it there would refuse the entire install base at once.
  app.use("*", async (c, next) => {
    const { version, path } = splitVersion(new URL(c.req.url).pathname);
    if (version !== 2) return await next();
    // The download redirect is exempt too (decision record `changes/incremental-union`, D1): the OS download
    // transports fetch it and send no app header, and a build too old for v2 never learns such a link.
    if (isDownloadRedirect(c.req.method, path)) return await next();
    const declared = c.req.header(APP_VERSION_HEADER);
    // ABSENT, UNPARSEABLE and TOO OLD collapse into one answer, deliberately. All three mean the caller
    // cannot be trusted to speak v2, and the remedy is identical — install a build that can — so no
    // consequence distinguishes them and nothing is lost by giving them one status.
    if (declared !== undefined && compareVersions(declared, config.minAppVersion) >= 0) {
      return await next();
    }
    c.header("Cache-Control", NO_CACHE);
    // The minimum rides in the BODY, which is what makes the refusal actionable rather than merely
    // legible: the client can name the version to install instead of saying only that something is wrong.
    return c.json({ error: "app too old", minAppVersion: config.minAppVersion }, 426);
  });

  // ── THE GATE (capability `privacy-security`) ──────────────────────────────────────────────────
  //
  // Every route requires a device token, obtainable ONLY by completing App Attest — so the API is
  // callable by a genuine, unmodified SnapSync on a genuine Apple device, and by nothing else. What this
  // closes is bill/storage abuse: the byte route resolves no event, the device id is self-asserted, and the
  // host ships in plaintext in every IPA, so before this an unbounded write to the zone was available to
  // anyone who read the binary.
  //
  // Registered FIRST, as one middleware, which gives three properties for free:
  //   * it runs BEFORE every event-existence gate, so an unauthenticated caller cannot even probe which
  //     events exist (a 404-vs-401 difference would leak that);
  //   * the ungated set is a CLOSED LIST in one readable place, so a future route cannot land ungated by
  //     omission — it has to be added here deliberately;
  //   * verification costs one HMAC comparison — no storage read, no Apple call — so the streaming
  //     photo-upload hot path pays nothing for it.
  //
  // The exceptions, exhaustively:
  //   * `/attest/*` — the three routes that ISSUE the token cannot require the token they issue. Each is
  //     self-authenticating: the challenge is HMAC-signed and stateless, and token/renew carry an
  //     attestation or an assertion that is verified before anything is minted.
  //   * `OPTIONS` — the pull zone is free to answer the preflight ITSELF (it has been observed doing so),
  //     so the script cannot gate it even if it wanted to; and a 401 here would break the plain-PUT
  //     fallback the iOS uploader depends on.
  app.use("*", async (c, next) => {
    const method = c.req.method;
    // Device-API routes are served under a versioned prefix (`/api/v1`, `docs/deployment.md`),
    // and Hono does NOT strip the mount prefix from the path accessors — so normalize a leading `/api/vN`
    // away HERE, once, before the closed-list checks below, which are written in un-prefixed terms. This is
    // deliberately version-agnostic: a further `/api/vN` mount is gated identically with no change here.
    // `/api/v1` → `/`, `/api/v2/attest/x` → `/attest/x`. The split is SHARED with the version gate above
    // (`version.ts`) rather than copied — two copies of "what counts as a version prefix" would drift in
    // silence, since nothing fails when they disagree; a request simply gets gated by one and not the
    // other.
    const { path } = splitVersion(new URL(c.req.url).pathname);
    // Ungated (closed list): OPTIONS, the `/attest/*` token issuers, the public marketing page at
    // EXACTLY `/` (capability `web-site`), and the event link's two public routes (capability
    // `join-event`) — the AASA, which Apple's CDN and the device fetch with no Authorization header and
    // cannot be made to send one, and `/join`, whose entire audience is people who have no app and so no
    // attestation. These three (`/`, `/join`, the AASA) are exact-path and GET/HEAD-only — never a prefix,
    // never a mutating method — and read no storage, so serving them unauthenticated grows neither the bill
    // nor the storage this gate protects. They are served at the ROOT only, never under `/api/v1`; the
    // normalization above is what lets `/attest/*` (a device route, so it arrives prefixed) AND the two
    // event READS added below — also device routes — be matched here on the normalized `path`.
    // `/` and the site's fingerprinted assets under `/_astro/*` are the browser-facing site (capability
    // `web-site`), proxied by the api from the PUBLIC storage `site/` prefix. Unlike the other public GETs
    // they DO read storage — but only the public `site/` prefix, never the bill-/photo-protected user data
    // this gate guards, so serving them unauthenticated is safe. GET/HEAD only.
    // `/health` is the third reason a path is ungated, and a different one from the two above: it is
    // OPERATIONAL. It exists so the deploy workflow can tell a booted script serving THIS bundle from a
    // corpse or a previous deployment (`docs/deployment.md`), and it is the cheapest route in
    // the backend — no storage read, no crypto, one constant string. Serving it unauthenticated
    // discloses only the commit of a PUBLIC repository, and costs strictly less than `/join` or the two
    // public event reads below, which are already ungated and uncacheable and do touch storage.
    // `/join/<eventId>` is the event's own page (capability `event-site`): the same audience as `/join`, and it
    // reads only what the two public event reads below already serve to anyone holding the identifier. One
    // segment exactly, so it is never a prefix into anything else.
    const publicGet = path === "/" || path === "/join" || /^\/join\/[^/]+$/.test(path) ||
      path === "/health" ||
      path === "/.well-known/apple-app-site-association" ||
      path === "/.well-known/assetlinks.json" ||
      path.startsWith("/_astro/");
    // The two event READS the no-app download page fetches (capability `event-site`): the event
    // metadata `/events/<id>` and the photo union `/events/<id>/files`. These are authorized by
    // eventId-possession alone — the eventId IS the read capability — so a browser that holds no attestation
    // can fetch them. This narrows the gate's READ posture (attestation never proved who may read whose
    // photos, and the presigned bytes it fronts were always ungated); it does NOT open any WRITE. The match
    // is GET/HEAD-only and shape-anchored to exactly these two paths, so every mutating `/events/<id>/…`
    // method (device manifest, leave, notify), `POST /events`, and — landing on the SAME path shape as
    // the read below, which makes it the closest call here — `PATCH /events/<id>` (the rename, capability
    // `manage-membership`) all stay gated. The method check is the ONLY thing separating the rename from the
    // ungated read; `attest.test.ts` pins both directions. Decision record:
    // `changes/web-event-download`. This is an accepted, eyes-open widening: a leaked eventId becomes a
    // perpetual read grant (no per-event opt-in, no rate limit).
    // The reads themselves are `isPublicEventRead`, below the app, so this middleware's own branches stay
    // within the complexity ceiling.
    if (
      method === "OPTIONS" ||
      path.startsWith("/attest/") ||
      ((method === "GET" || method === "HEAD") && publicGet) ||
      isPublicEventRead(method, path)
    ) {
      return await next();
    }

    const auth = c.req.header("authorization") ?? "";
    const token = auth.startsWith("Bearer ") ? auth.slice("Bearer ".length).trim() : "";

    // A valid device token is the ONLY credential this backend accepts. There is no admin key, master
    // key, or route-scoped bypass: the former notify-only ADMIN_NOTIFY_KEY existed solely so the
    // out-of-edge sweep could announce an expiring event before deleting it, and that announcement is
    // gone (capability `event-lifetime`) — so the credential is retired rather than left standing as
    // an authorization path with no caller.
    const tokenDeviceId = token ? await verifyToken(config, token, now()) : null;
    if (!tokenDeviceId) {
      return c.text("unattested", 401);
    }
    // Remembered for the routes that name a device: they refuse any other one (`actsFor`). Verifying
    // stays the whole cost here — the id rides inside the token, so binding needs no read.
    c.set("tokenDeviceId", tokenDeviceId);
    return await next();
  });

  // The ROOT routes — the site, the link-association documents, the boot probe (`routes/site.ts`).
  app.route("/", siteRoutes(deps, buildSha));

  // ── THE DEVICE API (`docs/deployment.md`) ────────────────────────────────────────────
  //
  // Every device-API route is registered on a sub-app (`routes/*.ts`) mounted under `/api/vN` below.
  // Keeping the sub-apps is what makes the routing version-parametric by construction: a further version
  // is one more `app.route(...)` of its own router, without touching the others. The root routes above
  // stay at the ROOT, never under `/api/vN`.
  //
  // The gate (`app.use("*")`) runs for the mount (verified: Hono runs parent middleware for mounted
  // sub-apps) and normalizes the `/api/vN` prefix, so the ungated `/attest/*` set holds under it.
  //
  // Each version's table is CLOSED: the shared router carries what both serve, and each version's own
  // router carries what only it does — so a v1-only path under `/api/v2` (`…/notify`) and a v2-only path
  // under `/api/v1` (`…/manifest`) are both 404. Gated by the two `app.use("*")` middlewares above, which
  // resolve the `/api/vN` prefix through one shared splitter, so a further version needs no change to
  // either. The two token issuers differ by version only in how a stale challenge is refused and in the
  // mint body's shape (`routes/attest.ts`).
  const shared = sharedRoutes(deps);
  const v1 = new Hono();
  v1.route("/", shared);
  v1.route("/", attestRoutes(deps, 401, "flat"));
  v1.route("/", v1Routes(deps));
  const v2 = new Hono();
  v2.route("/", shared);
  v2.route("/", attestRoutes(deps, 409, "typed"));
  v2.route("/", v2Routes(deps));
  app.route("/api/v1", v1);
  app.route("/api/v2", v2);
  return app;
}

/**
 * The event reads the token gate serves without a token, by eventId-possession alone (see the gate's comment):
 * GET/HEAD on the event's details `/events/<id>` and its union `/events/<id>/files`, and the download redirect.
 * The union's own token is OPTIONAL rather than absent (decision record `changes/incremental-union`, D5): the
 * route verifies one when it is sent, to name the reader in its log, and answers `401` for a bad one itself —
 * the gate never sees it, because the read is public either way. The DOWNLOAD REDIRECT (D1) is the third
 * public read, by the same capability: it resolves one resource of the union an eventId already lists, and
 * answers with a presigned link like those the union carried inline before — so it opens nothing the union
 * read did not.
 */
function isPublicEventRead(method: string, path: string): boolean {
  return ((method === "GET" || method === "HEAD") &&
    (/^\/events\/[^/]+$/.test(path) || /^\/events\/[^/]+\/files$/.test(path))) ||
    isDownloadRedirect(method, path);
}

/**
 * The download redirect's shape (decision record `changes/incremental-union`, D1), GET/HEAD only — the
 * one route both gates exempt by path, so they must agree on it: one predicate, not two regexes.
 * `/events/<e>/files/devices/<d>/<asset>/<role>`, with each segment percent-encoded, so none holds a `/`.
 */
function isDownloadRedirect(method: string, path: string): boolean {
  return (method === "GET" || method === "HEAD") &&
    /^\/events\/[^/]+\/files\/devices\/[^/]+\/[^/]+\/[^/]+$/.test(path);
}
