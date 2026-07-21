## Context

SnapSync is iOS-only. An invite is an HTTPS Universal Link `https://snapsync.stho.net/join#v=3&d=<payload>`
whose payload — including the `eventId`, which is the read/upload capability — rides in the **fragment**,
which a browser never transmits (capability `event-link`). On a device with the app, iOS claims the link
and opens the app before any HTTP request is made. On a device **without** the app (Android, desktop, or
an iPhone that hasn't installed), the browser requests `GET /join`, which today `302`s to the App Store —
a dead end off iOS. The event's photos exist and are shareable, but a no-app guest has no way to get them.

Three facts about the existing backend shape this design:

- **Photo bytes are already capability-by-URL.** The listing routes return AWS SigV4 **presigned S3 GET
  URLs** (`de-s3.storage.bunnycdn.com`, 7-day expiry). Bytes are fetched directly from bunny's S3 endpoint
  with no credential — the query signature is the sole authorization — and that endpoint already answers
  cross-origin browser `fetch` with `Access-Control-Allow-Origin: *` (verified against the live endpoint).
- **The only gated step on the read path is the *listing*.** As of `changes/archive/…-add-device-attestation`,
  every route except a closed whitelist requires an App-Attest bearer token. The event union
  `GET /events/:id/files` and the marker `GET /events/:id` are gated; a browser cannot mint a token (App
  Attest needs genuine Apple hardware).
- **Bunny Edge Scripting is deliberately kept off the byte path.** Its limits — 50 subrequests, 128 MB,
  30 s CPU per request (docs.bunny.net/scripting/limits) — make a server-side zip impossible for any real
  event and are *why* the architecture hands out presigned URLs instead of proxying bytes.

## Goals / Non-Goals

**Goals:**
- A no-app visitor (Android, desktop, iPhone-without-app) can retrieve **all** of an event's photos as a
  single zip, from a browser, with no app and no account.
- Keep the change small and backend-shaped: the app's Kotlin/iOS code is untouched; no new secret, storage
  namespace, or write path.
- Preserve the `event-link` fragment-privacy property for anyone who merely *opens* a link (as opposed to
  deliberately downloading).

**Non-Goals:**
- **No non-iOS upload / parity.** Browsers only *download*; contributing photos stays iOS-only.
- **No per-event opt-in and no rate limit.** Reads are opened for *every* event, unconditionally (see
  Decision 2 and the Risks section — this is an accepted, eyes-open posture).
- **No streaming-to-disk in v1.** The zip is assembled in browser memory (see Decision 4).
- **No preview / selection UI.** The page offers "download all", not a gallery or per-photo picking.
- **No `marketing-site` reuse.** The download page is standalone (Decision 6).

## Decisions

### Decision 1: `GET /join` becomes a static page, not a redirect

`GET /join` returns one **static, self-contained HTML+JS page** (`200`) for every link. The page's JS
reads the `eventId` from `location.hash`, then drives the whole flow client-side. The App Store link
becomes an element on the page rather than the `302` target.

- **Why a page and not a UA-branched redirect:** the download UI can *only* be built client-side, because
  the backend cannot read the fragment (`d`) by construction — that is the entire point of the fragment.
  So the non-iOS path needs JS regardless. Given that, one static page is best on every axis: identical
  bytes for every event → the pull zone caches it hard and the backend still reads nothing; no `Vary:
  User-Agent`; no unreliable iPadOS-reports-as-Mac UA sniffing.
- **Why no User-Agent detection at all:** iOS-with-app never reaches `/join` (the OS claims the Universal
  Link first), so *everyone* who lands here is a no-app visitor. Show both a "download" control and an App
  Store link unconditionally; an Android user just ignores the App Store link.
- **Alternative rejected — backend UA sniff (`302` for iOS, page for others):** adds unreliable UA logic
  and `Vary`, and the non-iOS branch still needs the JS page. No benefit.
- **Reverses** `event-link` Decision 3 (which chose a `302` precisely because a per-event page was
  "impossible in principle"). That reasoning held for a *server-rendered* page; a *client-rendered* one
  reads the fragment in JS, so the server still renders nothing per-event. The property is intact; only
  the response shape moved.

### Decision 2: Open the two *read* routes; keep every *write* gated

The union read `GET /events/:id/files` and the marker read `GET /events/:id` answer **with or without** a
token. Everything else — byte `PUT`, `POST /events`, manifest, leave, notify, the per-device raw listing
`GET /files/devices/:id` — stays gated.

- **Framing: this narrows an anti-*probing* measure; it does not delete an authorization check.**
  Attestation "says nothing about which device may read whose photos" (its own non-goal), and the presigned
  bytes it fronts were always ungated. The listing gate's only read-side job was to stop existence-probing
  and casual scraping. Opening it makes **event reads authorized by `eventId`-possession alone** — the
  `eventId` is the read capability, which the whole architecture already assumes.
- **Only the two routes the page consumes are opened** (union + marker). `GET /files/devices/:id` has no
  web consumer and stays gated — defense in depth for the device-partitioned raw listing.
- **Method-scoped:** only `GET`/`HEAD`. Non-`GET` methods on `/events/:id/…` (manifest `PUT`/`DELETE`,
  `POST …/notify`) and `POST /events` stay gated, so opening the reads cannot open a write.
- **Alternative rejected — a separate `/web-files` route:** functionally identical data behind a second
  name; an abuser just uses whichever is open, so a twin route buys nothing over relaxing the gate, while
  adding a route to maintain. (The interview initially leaned this way, then chose to relax `/files`.)

### Decision 3: `eventId` in read-request paths is consistent with today

The fragment property protects *opening a link without acting*. **Deliberate use of the capability** —
the app listing/uploading, or a guest clicking download — has always sent the `eventId` to the backend
over TLS and into its request logs. A web download is the same category, so `GET /events/:id/…` with the
id in the path is fine. The one browser-specific leak vector, third-party `Referer`, is closed by
Decision 5 (self-contained page).

### Decision 4: Client-side, in-memory zip — no server zip, no service worker

The page fetches each photo from its presigned URL and builds the zip **in browser memory**, then triggers
a normal download. This runs on **every** browser, including Safari.

- **Server-side zip is impossible:** Bunny Edge's 50-subrequest cap bounds a server zip to ~50 photos —
  a real event blows through it. The browser has no such cap and CORS is already open.
- **No service worker in v1:** StreamSaver (service worker) is the only way to stream a client-generated
  zip to disk with a "normal" download, and it is a long-standing failure on Safari (and a Mac / iPad-as-
  Mac is a "non-iOS device"). The File System Access API is desktop-Chromium-only. In-memory zip is the
  one mechanism that works everywhere. Cost: memory (see Risks). Streaming-to-disk (service worker) is a
  deferred enhancement to add *only if* large events prove a problem in practice.
- **Whole union, no selection:** a no-app guest has no own-device scope, so "everyone's photos" is the only
  meaningful unit; download-all matches the interview scope (no preview, no picking).

### Decision 5: The page is fully self-contained (no third-party resources)

All CSS/JS and the zip library are inlined or same-origin. A third-party subresource would carry the page
URL off-origin via `Referer`, risking the `eventId` fragment — so the zip lib (e.g. an ~8 KB `fflate`) is
bundled into the page, not loaded from a CDN.

### Decision 6: Standalone page, no `marketing-site` reuse

The download page does not reuse the landing page's chrome, so `marketing-site` is untouched. Keeps the
delta set to `event-link` + `device-attestation` + `bunny-list-endpoint` + `event-creation`.

## Risks / Trade-offs

- **A leaked `eventId` becomes a *perpetual* full-event read grant** → accepted, no mitigation. Because
  the union mints a fresh 7-day presigned URL on every call and the call is now open, any HTTP client that
  ever learns an `eventId` can re-list forever and pull every byte (billed as storage egress). Today
  attestation renders a leaked `eventId` inert and caps a leaked *URL* at 7 days. This is the exact
  property attestation was added to buy, spent deliberately. The strongest available mitigation — a
  per-event creator opt-in — was considered and **declined** for simplicity; it can be added later without
  a wire change if abuse appears.
- **Existence-probing returns** → accepted. A tokenless `GET /events/:id` / `…/files` now reveals whether
  an event exists. `eventId`s are unguessable high-entropy UUIDs, so this is enumeration-resistant, but it
  is no longer impossible.
- **Large event OOMs a browser tab** → mitigated by scope, deferred fix. In-memory zip is bounded by tab
  memory; a multi-GB event can crash the tab (worst on phones). Accepted for v1; the fix (service-worker
  streaming for supported browsers) is deferred until it bites. The backend imposes no per-request asset
  cap of its own, so the failure is the browser's own OOM, not a silent truncation.
- **Cross-origin `download` semantics** → validate during implementation. The in-memory path fetches bytes
  and downloads a `Blob`, so it does not depend on the cross-origin `download` attribute; CORS on the S3
  host (confirmed `*`) is what it relies on. Production uses zone `snap-sync` (dev is `snap-sync-dev`); the
  CORS headers come from the shared S3 nginx host, origin-independent, so production should behave
  identically — confirm on first deploy.

## Open Questions

- **Zip library choice** (`fflate` vs `JSZip` vs `client-zip`) and exact bundling into the static page —
  deferred to implementation; all build an in-memory `Blob`.
- **Zip filename** (e.g. `snapsync-photos.zip` vs event-name-derived) and page copy/styling — deferred.
- **Production CORS confirmation** on the `snap-sync` zone's presigned URLs — verify on first deploy.
