# Tasks

## 1. The record's key contract (api)

- [x] 1.1 Document the `fields` key contract (design D2: names, timestamp and id formats, list shape) in
  `docs/architecture.md`, "The request log", replacing its fields table; verify every key of the D2 table appears
  once with its type and source
- [x] 1.2 Write the declared app version into `fields.app_version` instead of the `version` column (the column stays,
  unwritten, until group 8) and keep `v=` in the console line; verify `api/test/request-log.test.ts` and
  `request-log-store.test.ts` assert both
- [x] 1.3 Read the device context headers (`device os model flavour access network mobile_data low_power
  background`) on every request into `fields`, ignoring absent and malformed ones like the version header;
  declare them in `api/openapi.yaml`; verify a request-log test with all, none and a malformed value
- [x] 1.4 Make `deno task logs` read `version` from `fields` and filter on any `fields` key; verify
  `api/test/scripts/logs.test.ts`

## 2. Server-side delivery facts (api)

- [x] 2.1 Byte PUT: record `kind` (fresh / resume; a restart is told when reading, design D5), `from`, `size`, `media`, the
  `taken`/`discovered`/`discovered_by`/`queued`/`queued_by` headers, `backlog` (taken before this device joined),
  and on the completing PUT `uploaded_all`; declare the headers in `api/openapi.yaml`; verify route tests in
  `api/test` for a fresh, a resumed and a restarted upload and for the last photo of a settled device
- [x] 2.2 Upload probe: record the `offset` it served; verify a probe test for 200, 204 and 404
- [x] 2.3 Union read: record `reader`, the cursor `from`/`to` and the `notified`/`announced` headers (backlog is the
  redirect's, design D2); replace the union-log `fetch` write (and its `read-log` fault) with these
  fields; verify `api/test` union-read tests and that no `fetch` row is written
- [x] 2.4 Download redirect: record the `queued`, `queued_by` headers and `backlog` for the context's device; a
  browser's request records none; verify a redirect test with and without the header
- [x] 2.5 `received`: accept an optional `delivered` list in the body (`{owner, asset, downloaded, added, by}`),
  record it and `caught_up` when the position reaches the union head; declare it in `api/openapi.yaml`;
  verify a test with an empty, a long and a malformed list (the report itself still succeeds)
- [x] 2.6 Manifest: record `assets`, `withdrawn` (replacing the union-log `removed` rows) and the resulting
  `state`; create: `event`, `encrypted`, `window_days`, `open_days`, `backdated` (join / leave: nothing new,
  design D2); verify route tests
- [x] 2.7 Regenerate the wire from `api/openapi.yaml` and update `docs/architecture.md`'s route table and
  "Resumable uploads"; verify `./gradlew build` (wire gates included) and `deno task test` pass

## 3. Operational tables keep only what operations read (api, one migration — design D10)

- [x] 3.1 Probe `ALTER TABLE … DROP COLUMN` on the deployed bunny Database (ask the operator first: it writes a
  scratch table to the live store) and record the result in `PROBE-FINDINGS.md`; verify the probe's output is in
  the record
- [x] 3.2 Stop writing and reading `union_log.at`, `devices.app_version`, `attested_at`, `push_updated_at` and
  `request_log.version`; retire the `app-version` fault tag; ONE migration copies `request_log.version` into
  `fields.app_version`, drops the five columns and the union log's fetch-only columns, deletes its `fetch` and
  `removed` rows (or rebuilds the tables if 3.1 refuses); remove `fromRow`'s and `--v`'s column fallbacks and
  their `PENDING_CLEANUPS.md` entry; regenerate `api/schema.sql`, update `rehearsal-copy.ts`; verify `deno task
  test`, `deno task schema:check` and the wire-contract tests of every served version pass unmodified
  (re-expressed where they asserted a retired column, listed in the PR)
- [x] 3.3 Sweep: write one request-log row per event it completes or deletes (method `SWEEP`, `event`,
  `outcome`); verify `api/test` sweep tests
- [x] 3.4 Sweep: remove the job summary and its version table; keep deletions, the exit code and the daily 5xx
  report; replace the version table in `PENDING_CLEANUPS.md`'s unlock conditions with the request-log query
  (design D9); verify the sweep tests and the workflow's summary step is gone
- [x] 3.5 Update `docs/architecture.md` (union log, devices) and `docs/deployment.md` ("Nightly cleanup", and the
  rollback note of D10); verify no doc names a retired row, column, fault tag or the summary

## 4. Device context on every request (app)

- [x] 4.1 Add the context headers in the gateway beside the version header, from platform facts behind ports
  (OS and version, model, build flavour, photo access, network type and mobile-data setting, low power,
  background allowed), reusing the bug report's device-state reads where they exist; verify core tests over the
  mock backend assert every header
- [x] 4.2 Put the same headers on every background transfer (upload and download sessions, the PhotoKit
  extension, Android work); verify the transfer port contracts on iOS (simulator) and Android (emulator) — the all-real journeys passed on both (2026-11-11), every transfer carrying the context and its stage fields; an iOS BACKGROUND session exists only on a device, so there it is unmeasured
- [x] 4.3 Verify the iOS compile proxy `./gradlew compileIosMainKotlinMetadata` and `./gradlew build`

## 5. Photo stage facts (app)

- [x] 5.1 Keep each own photo's `discovered` time and entry point when the selection first admits it, bounded
  and oldest-first dropped; verify a data-layer test
- [x] 5.2 Send `taken`, `discovered`, `discovered_by`, `queued`, `queued_by` on every byte upload, and `queued`,
  `queued_by`, `x-snapsync-device` on every download redirect; verify `FileUploads` and download tests assert them
- [x] 5.3 Echo the push's union position and its arrival time (`announced`, `notified`) on the read the push
  causes; verify an `OnSilentPush` test on both platforms' push adapters
- [x] 5.4 Keep each received photo's `downloaded` and `added` time and entry point until reported, and send them
  as `delivered` with the `received` report, split above a fixed count; verify a receiving test, including a
  back-dated join of several hundred photos
- [x] 5.5 Document the delivery facts the app keeps and sends in `docs/architecture.md` (or the data layer's
  KDoc, as the placement guide says); verify `./gradlew build`

## 6. What users read

- [x] 6.1 Privacy Policy (`site/`): describe the delivery record — what it holds, nothing that identifies the
  person ("not linked to your identity", never "anonymous"), why (legitimate interest: keeping delivery working),
  30 days whether or not the photos were deleted; move the device facts out of "crash reports only"; correct
  "the app's activity logs never leave your phone", the read record's "deleted together with the event's photos"
  and "no analytics of any kind"; verify the site builds and every spec scenario of "The Privacy Policy states
  what leaves the device" holds on the rendered page
- [x] 6.2 `metadata/play/declarations.md`: the read record becomes the delivery record under the already-declared
  diagnostics and app performance data; verify no store form changes
- [x] 6.3 `PENDING_CLEANUPS.md`, "Re-enable encrypted creation": add the step raising the delivery record's
  retention to 12 months (with its spec change); verify the entry names the constant and the spec requirement

## 7. End to end

- [ ] 7.1 On two simulators against a local backend (`ios-simulator`, `local-backend`): a photo taken on A reaches
  B's library; verify the request log holds every stage of D2 for it, with triggers and device context, and no
  `fetch` row exists
- [ ] 7.2 The same on the Android emulator as receiver; verify the same stages
  - Measured 2026-11-11 by the all-real journeys (one app, the second member played over HTTP), on the Android
    emulator and an iOS simulator: every upload stage with its triggers (`taken` `discovered[_by]` `queued[_by]`),
    the download's `queued[_by]`, the device context on every app request, and `union_log` holding `gained` rows
    only. A back-dated (ended) event on the emulator then showed each received photo told once in the report's
    `delivered`, with both times and its entry points — after `EntryPoint` was widened to `+`-joined names (D6), whose
    absence had dropped the whole list. NOT yet seen live: the push echo
    (`notified` `announced`: no push on either host) and the extension's triggers.
- [ ] 7.3 With the sync/archive (not before — `VerifiesGateTest` reads the main specs): re-point every
  `@Verifies` claim of "The service records who reads an event's photo list" to "The service records how photos
  are delivered", and claim its new scenarios; verify `./gradlew build`
