## Why

SnapSync is iOS-only, so a guest at an event who carries an Android phone, a desktop browser, or an
iPhone without the app currently gets nothing from an invite link — `GET /join` bounces every no-app
visitor to the App Store (a dead end off iOS, and a 404 until the listing is live). The photos exist and
are shareable; the only missing piece is a way for a no-app visitor to **retrieve them from a browser**.
This change gives that visitor a one-click "download all photos as a zip" page, with no app and no
account.

## What Changes

- **New `/join` behavior**: `GET /join` stops being a `302` to the App Store and instead returns a
  single **static, self-contained HTML+JS page** (identical bytes for every link; the backend still reads
  no storage and cannot read the fragment). The App Store link becomes an element *on* that page. No
  User-Agent detection — iOS-with-app never reaches `/join` (the OS claims the Universal Link first), so
  every visitor who lands here is a no-app visitor and sees both a **Download all photos (zip)** button
  and a **Get SnapSync** App Store link.
- **Client-side zip**: the page's JS reads the `eventId` from the URL fragment (never sent to any
  server on its own), fetches the event name and the event photo union, then fetches each photo's
  presigned URL and builds the zip **in the browser's memory** (works on every browser including Safari)
  and triggers a normal download. No service worker, no server-side zip. (Bunny Edge Scripting caps a
  request at 50 subrequests / 128 MB / 30 s CPU — a server-side zip is impossible for a real event; the
  browser has no such cap and CORS on the presigned URLs is already open.)
- **BREAKING (security posture)**: the two backend **read** routes the page needs — the photo union
  `GET /events/:id/files` and the event marker `GET /events/:id` — are moved off the App-Attest gate;
  they are answered **with or without** an attestation token. This intentionally narrows attestation to
  *writes and existence-probing* and makes **event photo reads authorized by eventId-possession alone**,
  for **every** event, with **no opt-in and no rate limit**. Consequence, accepted eyes-open: a leaked
  `eventId` becomes a *perpetual* full-event read grant to any HTTP client (billed as storage egress),
  where today attestation renders a leaked `eventId` inert. All **writes** (upload, create, enroll,
  manifest, leave, notify) and the per-device raw listing stay gated.
- **No membership side effects**: a web download registers nothing — no device record, no manifest, no
  push token, no analytics. It is a pure read.

## Capabilities

### New Capabilities
- `web-event-download`: the no-app browser download experience — the `/join` static download page, its
  client-side fragment decode, event-name + union fetch, in-memory zip assembly, and the download
  affordance; standalone (does not reuse the `marketing-site` landing chrome).

### Modified Capabilities
- `event-link`: the `GET /join` requirement changes from "redirect to the App Store" to "serve the
  static download page (which links to the App Store)"; the server-side invariants (reads no storage,
  identical for every link, cannot read the fragment) are preserved.
- `device-attestation`: the ungated-route whitelist ("Ungated routes are a closed list") gains the event
  union read and the event marker read; the capability's read posture is restated — attestation gates
  writes and existence-probing, not reads.
- `bunny-list-endpoint`: the event union `GET /events/:id/files` no longer requires an attestation
  token; a tokenless request is served (not `401`).
- `event-creation`: the event marker read `GET /events/:id` no longer requires an attestation token; a
  tokenless request is served (not `401`).

## Impact

- **Backend** (`backend/src/app.ts`): the `/join` route (302 → static HTML), the attestation middleware
  whitelist (two read routes added), and a new static download-page asset (imported at build like
  `landing.html`). No new secret, no new storage namespace, no new write path.
- **New web asset**: a self-contained HTML+JS download page with an inlined zip library (no third-party
  resources — a third-party subresource would leak the fragment via `Referer`).
- **No app/Kotlin/iOS change**: the app's join and read paths are untouched — the app keeps attesting and
  reading exactly as before; these routes merely *also* answer tokenless. `:domain`, `:adapter:*`,
  `:ui:*`, and `iosApp/` are unaffected.
- **Specs**: one new capability spec + four delta specs (above). No `marketing-site` or `join-event`
  change.
