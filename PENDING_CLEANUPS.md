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
- **Deadline: before the release that re-enables encrypted creation** (below). That release creates encrypted
  events, whose invite is the keyed path form `/join/<uuid>#k=…`. 0.4 reports that as a damaged invite, not as
  "update required"; the floor turns it into the update screen. 0.6 creates plain events, so it does not need the
  floor. Records: `openspec/changes/encrypt-events-by-default` (design, Migration Plan),
  `openspec/changes/archive/2026-10-12-defer-encrypt-by-default`.
- **Extra check before raising it:** no 0.4 device is a member of an open event, so nobody is cut off mid-event.
  Run against the database and expect `0` (2026-10-12: `5`, in 2 open events — why 0.6 deferred encrypted
  creation):
  ```sql
  SELECT count(*) FROM memberships m
    JOIN events e ON e.id = m.event_id
    JOIN devices d ON d.device_id = m.device_id
   WHERE e.closed_at IS NULL AND e.completed_at IS NULL
     AND m.state IN ('sharing', 'settled')
     AND (d.app_version = '0.4' OR d.app_version IS NULL);
  ```
- **Raise it together with** the `MARKETING_VERSION` floor in `iosApp/Configuration/Config.xcconfig`, otherwise
  `api/test/min-app-version-floor.test.ts` fails and dev builds get `426`.
- **Also touches:** `api/test/config.test.ts` and the comment in `app/android/build.gradle.kts`.
- **Raising it is also what allows editing `api/test/v2.test.ts`'s frozen tests** for the backend items below.

### Anonymous event reads (api + app)
- **What:** `GET`/`HEAD` `/api/v2/events/<id>` and `/api/v2/events/<id>/files` pass the token gate with no token.
  - `api/openapi.yaml`: the `{}` alternative in `getEvent`'s and `eventFiles`' `security` — what the token gate
    reads (`api/src/route-security.ts`, `isUngatedOperation`).
  - `api/src/routes/shared.ts`: `optionalReader` on the event read and on the union.
  - The app needs nothing removed: its generated routes type both reads as gated, so it always sends its token.
- **Why:** 0.4 calls both reads with no token. The event page no longer needs them; it reads
  `/web/events/<id>/photos`.
- **Remove:**
  - Require the token on both reads: drop the `{}` from both operations' `security` in `api/openapi.yaml`, and
    `optionalReader`.
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
    - `BackendMock`'s and `BackendRouter`'s event-less routes.
    - `test/rig/.../MockWorld.kt`.
    - The `LedgerStoreContract` clause "a row keeps the destination its own request carried across a route
      change".
    - The doc note in `domain/services/.../backend/BackendUploadRequests.kt`.
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
  - **What:** 12.sqm moved every row under the empty event `''`; the membership's legacy copy adopts them into the
    event it copies, or drops them when there is none (`LegacyRecords.copyMembership`, `Ledger.sq` `adoptParked` /
    `dropParked`).
  - **Remove:** both queries, their calls in `LegacyRecords`, and `LedgerMigrationTest`'s parked-row cases.
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

## Re-enable encrypted creation

**Unlock condition:** the `MIN_APP_VERSION` 0.5 bump above is merged and deployed.

**Order:** floor merged → this re-enable merged → the privacy claim (next section) merged → promote that release.

### Shipped builds create encrypted events again (app + site + spec)
- **What:** 0.6 ships with encrypted creation switched off in production, so every invite it shares opens on 0.4
  (`openspec/changes/archive/2026-10-12-defer-encrypt-by-default`). Every encrypted-event path stays built and tested; only creation is
  off. A rig build already encrypts by default.
  - `adapter/generic/app/.../dev/InertDevControls.kt`: `createsPlainEvents()` answers `true` — and its KDoc, and
    `InertDevControlsTest`.
  - The KDoc of `DevControls.createsPlainEvents` (`domain/ports`) and `EventKeyMinting`
    (`domain/services/.../crypto/EventKeys.kt`).
  - `DevControlsContract`: the shipped clause expects `true`, the switched clause `false`, and
    `RigDevControlsContractTest` sets `encrypts = true` for `SWITCHED`.
  - The `invite-link` spec: "Every invite form opens in every future version" says every event this version creates
    is plain with the fragment form, and "Only the whole invite opens an event's photos" speaks of "an encrypted
    event".
  - The Privacy Policy in `site/src/pages/index.astro` ("What we process, and why", "Photos you share") lost its
    encryption paragraph.
  - `docs/architecture.md` ("The encrypted file format"), `CLAUDE.md` (the `:domain` entry's Crypto line),
    `.claude/skills/rig-channel/SKILL.md` (`device/encrypt-new-events`), the comment at `encrypt-new-events` in
    `test/rig/.../MockLevers.kt`, and the KDoc of `EncryptedEventIntegrationTest`.
- **Why:** 0.4 cannot open an encrypted event's invite, and the floor could not rise before the 0.6 promote.
- **Re-enable:** one OpenSpec change. It MODIFIES both `invite-link` requirements back: every event this version
  creates is encrypted, with the keyed path form as its QR code and shared link, and the scenario "A new event's
  invite carries its key" (re-point `EncryptedEventIntegrationTest`'s `@Verifies` to it). In the code:
  - flip `InertDevControls` to `false`, with its test and KDoc;
  - restore the KDoc, the contract and the rig binding as they were before `defer-encrypt-by-default`;
  - reword the docs and the skill.
  - Put the policy paragraph back after "…deleted with the event (see below).", with `<version>` the release's own:
    > In an event created with SnapSync `<version>` or later, every photo is stored encrypted, with a key that exists
    > only on the phones in the event and in the event's invite link; our service is never given that key, so it
    > cannot open the stored photos. The one exception is an iPhone that uploads photos in the background through the
    > system's own photo uploader: there, each photo reaches our service unencrypted together with a key that opens
    > only that photo, and our service encrypts it as it arrives and keeps neither the key nor the unencrypted photo.
- **Record:** `openspec/changes/archive/2026-10-12-defer-encrypt-by-default` (design D1–D5), `openspec/changes/encrypt-events-by-default`.

## At the promote that re-enables encrypted creation

### The privacy claim on the site and the store listing (site + metadata)
- **What:** "Private by design" gains a concrete line, true on every upload path: photos are stored so only your
  group can open them. Wording rule: `metadata/messaging.md` (no "encrypted", "secure" or "safe").
  - the `privacy-story` section of `site/src/pages/index.astro`
  - `metadata/listing/en-US.json` (the description), which both stores render from at the promote
- **Why not earlier:** the site deploys on every merge, and before that release no shipped build creates an
  encrypted event (0.6 creates plain ones, `openspec/changes/archive/2026-10-12-defer-encrypt-by-default`). It stays untrue for the plain
  events 0.5 and 0.6 create until those are retired (below); accepted.
- **Ship:** merge just before dispatching that release's promote, after the floor and the re-enable above.
- **Record:** `openspec/changes/encrypt-events-by-default` (design D7).

## Once `MIN_APP_VERSION` ≥ 0.6

**Unlock condition:** as for 0.5 above — 0.6 live in both stores, and the sweep's version table shows no kept
device below it.

### Header-less download redirects (api)
- **What:** the version gate lets the download redirect through with no `x-snapsync-app-version` header.
  - `api/src/app.ts`, the version gate: `isDownloadRedirect` (`api/src/route-security.ts`).
- **Why:** builds before 0.6 hand the redirect to the OS download services without the header. From 0.6 the app
  sends it on every request, downloads included (`api/openapi.yaml`, `downloadResource`).
- **Remove:** drop the `isDownloadRedirect` exemption from the version gate, and `isDownloadRedirect` with it.
  `api/test/union-read.test.ts` (the download redirect) gains the header on its redirect requests.

## Once `MIN_APP_VERSION` ≥ the release that re-enables encrypted creation, and no plain event is open

**Unlock condition:**
- the floor is at or above the release that re-enables encrypted creation (above) — 0.5 and 0.6 still create plain
  (unencrypted) events, and
- no open event is plain. Run against the database and expect `0`:
  ```sql
  SELECT count(*) FROM events WHERE key_id IS NULL AND closed_at IS NULL AND completed_at IS NULL;
  ```

**How to ship it:** its own OpenSpec change — every event a member meets is then encrypted, which touches
`join-event`, `event-site` and `privacy-security`.

### Plain events (api + app + site)
- **What:** everything that still creates, joins, uploads to, downloads from or shows an event without a key:
  - the rig's plain create: `DevControls.createsPlainEvents`, `device/encrypt-new-events?on=false`, the tests
    that use it (`EncryptedEventIntegrationTest`, `PathFormLinkIntegrationTest`)
  - the api's keyless `POST /events` (`keyId` optional in `api/src/routes/shared.ts`, `key_id` NULL in
    `api/src/db.ts`) and the plain branch of `api/src/routes/encrypted-upload.ts`
  - the app's plain branches: `EventKeys.opens` with no link key, `EventConfig.inviteUrl`'s fragment form,
    `UploadSealing` and `DownloadOpening` for an event with no `keyId`, `KeyPresence.NotNeeded` for a joined event
  - the event page's plain path (`site/src/pages/join.astro`, `encryptionOf`)
- **Remove:** require a key on create; refuse every keyless event in the app and on the page; drop the rig switch.
- **Keep forever:** the fragment-form decoder (a printed QR opens forever) — a plain invite then opens the
  incomplete-invite screen.
- **Record:** `openspec/changes/encrypt-events-by-default` (proposal, design Non-Goals).

## Once `MIN_APP_VERSION` ≥ the release that moved the shared records

**Unlock condition:** the floor is at or above the first store release that carries the shared database's records
(`13.sqm`; the next release after 0.5 at the time of writing). Every device that can still talk to the backend has
then run an app that copied its legacy records, so no legacy file or key is left to read.

### Legacy homes of the shared records (app)
- **What:** an updated device holds the records the app and the extension share in their pre-database homes. The app
  copies each into `ledger.db` once, at its first open, and deletes it; the extension pauses until it has.
  - `domain/services/.../databases/LegacyRecords.kt`: the copy — `eventconfig.json`,
    `device-manifest/last-uploaded.json`, `membership/pending-leaves.txt`, the Preferences keys
    `app.snapsync.album.map` / `.filled` and `app.snapsync.check.*`.
  - `SharedDatabase.imported` / `readiness`, the `legacyImport` table (`13.sqm`, `LegacyImport.sq`), and the
    membership read's "still in its legacy file" answer (`ConfigService.read`).
  - `PauseReason.LEGACY_RECORDS` and its check at the head of `CycleGateRead.read`.
  - `PushRegistrationRecord.fromLegacyFile` (`push-registration/last-registered.txt` into the Preferences key) and
    its `files` parameter.
  - `model/ConfigFile.kt` (the envelope codec), read only by the copy.
- **Why:** a device that updated mid-event must keep its membership, its pending leaves and its albums.
- **Remove:** `LegacyRecords` and `LegacyRecordsTest`; `SharedDatabase`'s `legacy` parameter, `imported` and
  `readiness`; the pause reason and its gate branch; the membership read's legacy answer; the push record's legacy
  copy and its two tests; `ConfigFile.kt`. Add a migration that drops `legacyImport`. Then the `Files` adapter's
  not-found classification is no longer load-bearing for the leave decision (`FileAbsence.kt`, `CLAUDE.md`).
- **Record:** this file's PR (phase 2b, "ports: sql + shared records").

## Once `MIN_APP_VERSION` ≥ the release that stopped events closing

**Unlock condition:** the floor is at or above the first store release built with `back-dated-events` (open-until
dates; the app never leaves an event on its own). Every device that can still talk to the backend then reads
`openUntil` and `members.waiting`, reports what it received, and ignores `closedAt`.

### Derived `closedAt` for builds that predate open-until dates (api)
- **What:** events no longer close, but the event read still serves `closedAt`, because a build before this release
  treats a non-null value as "closed" and leaves once it holds everything.
  - `api/src/routes/support.ts`: `closedForOldBuilds` — the stored stamp, else the open-until date once it has
    passed and everyone still in the event has shared everything.
  - `api/src/db.ts`: `sharedByEveryone`, read only by it; `memberCounts`' `final`, read only by those builds' waiting
    line.
  - `api/openapi.yaml`: `Event.closedAt` and `MemberCounts.final`.
- **Why:** without it such a build would never leave a finished event, and its membership would hold the fast
  deletion back until the 3-day clock.
- **Remove:** `closedForOldBuilds` (serve the stored stamp, or drop the field with a `/api/v3`), `sharedByEveryone`,
  `MemberCounts.final`, and their tests in `api/test/completion.test.ts`.
- **Record:** `openspec/changes/archive/2026-10-12-back-dated-events` (design, D5).

## Once every event closed before open-until dates is gone

**Unlock condition:** no event row carries a `closed_at` written by the old close. The sweep drops every such row at
its deadline, so this holds 30 days after the later of the rollout and the longest such event's end — check with
`SELECT count(*) FROM events WHERE closed_at IS NOT NULL AND completed_at IS NULL` (expect `0`; a completed event
also carries the stamp, and keeps it).

### The old close's rules for legacy closed events (api)
- **What:** an event closed before this change keeps refusing joins, renames and a changed asset set.
  - `api/src/db.ts`: the `closed_at IS NULL` test in `ENROLL`, the close gate in `publishStatements`,
    `declaredAssets`.
  - `api/src/routes/v2.ts`: `declaresSameSet` and the `event.closedAt` branch of `publishManifest`;
    `api/src/routes/shared.ts`: the `closedRefusal` in `changeEvent`.
  - `api/test/completion.test.ts`: the `legacy closed →` tests.
- **Why:** its members were promised that what they shared was fixed; reopening it would let a late change through.
- **Remove:** the branches above, keeping the refusals a COMPLETED event still needs (`completedAt`); then
  `ClosedError`'s `SetFixed` outcome on `publishManifest` and the app's handling of it.
- **Record:** `openspec/changes/archive/2026-10-12-back-dated-events` (design, D2/D5).

### What stands in for the old close in the app and its mocks (app)
- **What:** the app and its mocks still know an event can have closed.
  - `domain/model/.../EventConfig.kt`: `EventConfig.closed`, which only a membership persisted by an earlier build
    carries (the `closed` column of ledger.db's `membership` row, `13.sqm`), and what reads it.
  - `adapter/generic/mock/.../BackendMock.kt`: `BackendState.Event.closed`, which only a state saved by an earlier mock
    carries, and its branches in `join`, `publish` and `BackendRouter.change`.
  - `test/contracts/.../BackendContract.kt`: the wire-fixture state `CLOSED_BEFORE_OPEN_UNTIL` and its clause.
- **Why:** a member joined to an event that closed before this release must not be shown it as open while the
  backend still refuses its rename.
- **Remove:** the field and its readers (a migration drops the column), the mock's branches and the fixture state, with
  the api entry above.
- **Record:** `openspec/changes/archive/2026-10-12-back-dated-events` (design, D2/D5).

## Once `MIN_APP_VERSION` ≥ the first release built from `remove-event-device-cap`

**Unlock condition:** that release is live in both stores, and the sweep's version table shows no kept device
below it. Older builds decode `Event.capacity` as a required field; that release and later ones generate it optional.

### The event's `capacity` on the wire (api + app)
- **What:** every event body still carries `capacity`, as `CAPACITY_PLACEHOLDER` (`api/src/config.ts`). It
  carries no meaning, because an event admits any number of devices.
  - `api/openapi.yaml`, `Event`: `capacity` in `properties` (already not `required`).
  - `api/src/routes/support.ts`, `publicEvent`: overrides the row's value with the placeholder.
  - `adapter/generic/mock/src/commonMain/kotlin/app/snapsync/mock/BackendRouter.kt`, `onWire`:
    `BackendMock.CAPACITY_PLACEHOLDER`.
- **Why:** builds before that release generate `Event.capacity` as a non-null property, so a body without it
  fails to decode on create and lookup. From that release on it is nullable and nothing reads it. Record:
  `openspec/changes/archive/2026-10-12-remove-event-device-cap` (design D4).
- **Remove:** drop `capacity` from `Event`'s `properties` in `openapi.yaml` and its override from `publicEvent`,
  and drop the mock's `CAPACITY_PLACEHOLDER` and its `onWire` field. `api/test/shared-routes.test.ts`
  re-expresses its key list and its two placeholder assertions. Then ship the column's removal below.

### The `events.capacity` column (api, its own deploy)
- **What:** `events.capacity` (`NOT NULL`) is still written on create, with `CAPACITY_PLACEHOLDER`, and read by
  nothing.
  - `api/src/db.ts`: `EventRow.capacity`, `readEvent`, `insertEvent`.
- **Why:** a failed deploy republishes the previous bundle, and that bundle's join reads the column and its
  create writes it. The large placeholder keeps that bundle's capacity gate admitting on events created
  since. Dropping the column in the same deploy that stopped reading it would break join and create on a
  rollback.
- **Unlock:** the wire entry above has shipped, so no rollback candidate reads the column.
- **Remove:** a migration dropping `events.capacity` (one migration per deploy; `docs/deployment.md`, "Schema
  migrations"). Remove `capacity` and `CAPACITY_PLACEHOLDER` from `db.ts`, `config.ts` and `shared.ts`. Drop
  `capacity` from the test fixtures that stamp it (`api/test/support/harness.ts` `EVENT`, and every
  `INSERT INTO events` naming it). Regenerate `schema.sql`. Update `docs/architecture.md`'s events table.

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
