# Pending cleanups

Backwards-compatibility and legacy shims that are still here, and what lets each one go. Entries are grouped
by the condition that unlocks them. Any PR that adds a shim adds its entry here (`CLAUDE.md`, "Workflow"). The
PR that removes a shim deletes its entry. Anchors name files and symbols rather than line numbers.

## Once `MIN_APP_VERSION` ≥ 0.5

**Unlock condition:**
- 0.5 is live in both stores, and
- the nightly sweep's version table (`api/src/scripts/sweep.ts`, `versionTable`) shows no kept device below 0.5.
  Ignore its `unknown` row.

Before that, a 0.4 phone still talks to the backend. It may also update straight to the next build in the
middle of an event.

**How to ship it:**
- Use one OpenSpec change (proposed name: `require-0.5`). The token requirement changes `privacy-security`'s
  closed list, and the share form changes the invite link.
- The api half deploys when the PR merges. The app half ships with the next store release.

### The floor itself
- **What:** `MIN_APP_VERSION` in `api/src/config.ts`.
- **Raise it together with** the `MARKETING_VERSION` floor in `iosApp/Configuration/Config.xcconfig`, otherwise
  `api/test/min-app-version-floor.test.ts` fails and dev builds get `426`.
- **Also touches:** `api/test/config.test.ts` and the comment in `app/android/build.gradle.kts`.
- **Raising it is also what allows editing `api/test/v2.test.ts`'s frozen tests** for the backend items below.

### Anonymous event reads (api + app)
- **What:** `GET`/`HEAD` `/api/v2/events/<id>` and `/api/v2/events/<id>/files` pass the token gate with no token.
  - `api/src/app.ts`: `isPublicEventRead`. Keep `isDownloadRedirect`, which stays public for good.
  - `api/src/routes/shared.ts`: `optionalReader` on the event read and on the union.
  - The app's mirror: `adapter/generic/app/.../http/GatedPaths.kt` (`isGatedRequest`, `verifiesToken`), pinned
    by `test/architecture/.../GatedPathPinTest.kt`.
- **Why:** 0.4 calls both reads with no token. The event page no longer needs them; it reads
  `/web/events/<id>/photos`.
- **Remove:**
  - Require the token on both reads.
  - Drop `isPublicEventRead` and its mirror in `GatedPaths.kt`.
  - Update `BackendMock`, the Backend contract, `api/test/attest.test.ts` and `api/test/v2.test.ts`
    (the anonymous-read cases), `docs/architecture.md`, and `privacy-security`'s closed list.
  - Decide how the join preview fails when attestation fails (design D7, last bullet).
- **Record:** `openspec/changes/archive/2026-10-07-separate-event-page-from-device-api` (design D6/D7,
  proposal).

### The union's default shape with `url` (api)
- **What:** `api/src/routes/shared.ts` adds a `url` to every resource unless `?urls=false` is passed
  (`withUrls`). Related pieces:
  - `api/src/routes/support.ts`: `downloadPath`.
  - The `version` lookup in the union handler, which exists only to build that URL.
- **Why:** 0.4 decodes `url` as a required field. 0.5 always sends `urls=false` and builds the URL itself.
- **Remove:**
  - Always omit `url`; accept and ignore `urls=`.
  - Update `api/test/union-read.test.ts` and `api/test/shared-routes.test.ts`.
  - Keep `key`, `contentType` and `creationDate`, which 0.5 requires.
- **Record:** `openspec/changes/archive/2026-10-05-incremental-union` (design).

### Byte routes with no event in the path (api + app)
- **What:** the routes in `api/src/routes/v2.ts` that carry no event:
  - `PUT /files/devices/:deviceId/:assetId/:role` and its `OPTIONS`
  - `GET /files/devices/:deviceId`
  - Helpers in `api/src/db.ts`: `presentUploadMembership` and `presentMembership`. Check for other callers before
    deleting them.
- **Why:** 0.4 uploads and lists there. An OS upload job that 0.4 enqueued also survives the update to a newer
  build and still targets the old route.
  - Once the floor is 0.5, such a job carries 0.4's version header and is refused `426` anyway.
  - The app then re-creates the job on the new route (`UploadJobDecisions`, `terminalDisposition`). This costs a
    re-upload, never a lost photo.
- **Remove:**
  - Delete both routes.
  - Fix the `db.ts` header and `api/src/dev/fallback.ts` (its `files/devices/<d>` matcher).
  - Update `docs/architecture.md`, and the api tests that use the old path (`v2`, `app`, `completion`,
    `encrypted-upload`, `union-log`, `dev/fallback`, …).
  - In the app:
    - `BackendMock`'s event-less routes.
    - `test/rig/.../MockWorld.kt`.
    - The `LedgerStoreContract` clause "a row keeps the destination its own request carried across a route
      change".
    - The doc note in `domain/model/.../EdgeUploadRequestProvider.kt`.
- **Also blocks:** concurrent multi-event membership.
- **Record:** `openspec/changes/archive/2026-10-06-per-event-storage-layout` (design D4/D5).

### Share `/join/<uuid>` instead of the fragment form (app)
- **What:** `domain/model/.../EventLink.kt` still shares, and QR-encodes, `/join#v=3&d=…`.
- **Why:** builds older than 0.5 cannot decode the path form.
- **Remove:** switch the shared form and the QR to `/join/<uuid>`.
- **Keep forever:**
  - the fragment decoder (a printed QR opens forever)
  - the backend's `/join` page and the AASA's `/join` claim
- **Record:** `openspec/changes/archive/2026-10-06-server-rendered-event-page` (design: phase 2).

### Leftovers from events joined under ≤0.4 (app)
A 0.4 phone that updates straight to a newer build in the middle of an event still needs these. Once the floor
is 0.5, no such membership is live.

- **Parked ledger rows.**
  - **What:** 12.sqm moved every row under the empty event `''`; `LedgerService` adopts them into the joined
    event on first use (`queries.adoptParked`, `Ledger.sq` `adoptParked`).
  - **Remove:** the adopt step, the query, and `LedgerServiceTest`'s parked-row cases.
  - **Keep:** 12.sqm itself, until the migration chain is squashed again.
- **3-field download task tags.**
  - **What:** `domain/services/.../downloads/DownloadJobs.kt` (`LEGACY_TAG_FIELDS`) reads tags written by a
    transfer that ≤0.4 started.
  - **Remove:** it and its cases in `DownloadJobsTest`.
- **Download rows with an empty event.**
  - **What:** rows planned before 6.sqm, handled in:
    - `DownloadJobs.stagingPath`, which stages them in the root
    - `domain/model/.../DownloadRecords.kt`
    - `domain/services/.../crypto/DownloadOpening.kt` (`eventId.isNotEmpty()`)
    - the `download-staging/` row of `docs/architecture.md`
  - **Remove:** add a downloads migration that deletes the `eventId = ''` rows, then drop the tolerance.

### Leave without `?received` (api, optional)
- **What:** `api/src/routes/shared.ts` reads an absent `received` as "no", so a 0.4 leave always records `left`,
  never `done`.
- **Value:** the default is harmless. Making the parameter required is optional.

## One-off ops

### Bytes still in the old storage layout (api)
- **What:** bytes written before migration 0010 live at `files/devices/<deviceId>/<assetId>-<role>.<ext>`, and
  their `resources.path` rows point there.
  - The nightly sweep skips that folder (`api/src/scripts/sweep.ts`).
  - `docs/architecture.md` and `docs/deployment.md` describe it.
- **Remove:**
  1. A local copy script, run once: download each `files/devices/…` row, PUT it to `files/<e>/<sha256>`, and
     rewrite its `path`.
  2. At least 7 days later (the presign lifetime): delete `files/devices/`, the sweep's skip, and the docs'
     mentions.
- **Record:** `openspec/changes/archive/2026-10-06-per-event-storage-layout` (design, follow-ups).
