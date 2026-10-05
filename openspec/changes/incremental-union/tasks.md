# Tasks

## 1. The read log (api)

- [x] 1.1 Add migration `api/migrations/0006_union_log.sql` (table, `(event_id, seq)` index, `ON DELETE CASCADE` from `events`) and regenerate `api/schema.sql`; verify `deno task test` passes, including the schema assertion, and `ci.yml`'s `migration-rehearsal` passes on the PR
- [x] 1.2 Byte route: write a `gained` row for each event `eventsCompletedBy` answered, beside the `resources` record; verify with an api test that completing an asset logs exactly one `gained` per declaring event, and a re-upload logs none
- [x] 1.3 Publish: change `publishAddsFetchableAsset` to answer the gained refs and the previously complete refs no longer declared, and write `gained` / `removed` rows in the publish batch under its version and closed-event gates; verify with api tests for a widening (gains), a narrowing (removals), a republish of an unchanged set (no rows) and a stale-version publish (no rows)
- [x] 1.4 `unionRows` gains an optional `after` that returns only assets with a `gained` row past it, through the same completeness/declaration filter; verify with api tests that a delta excludes an asset removed after its gain and includes a re-gained one once
- [x] 1.5 Explain the table and its writers in `docs/architecture.md` (storage layout, the gain points); verify by review

## 2. The download redirect and the `/files` parameters (api)

- [x] 2.1 Add the redirect route under `sharedRoutes` (`/events/<e>/files/devices/<d>/<asset>/<role>`): `302` to a 7-day presign with `no-store, no-cache, max-age=0`, `404` when the event does not declare the asset/role or its bytes are absent; verify with api tests for 302 (Location host and key), 404 after a narrowing publish, 404 for an unknown event, and the cache header
- [x] 2.2 `/files`: `urls=false` omits `url`; the default shape's `url` becomes the absolute redirect URL; `cursor=` serves the delta; every response carries `SnapSync-Cursor`; verify with api tests for each combination and a malformed cursor (`400`)
- [x] 2.3 `/files`: verify an optional bearer token (valid → logged device, invalid → `401`, none → anonymous), read `SnapSync-Trigger` (unknown → logged as unknown) and write a best-effort `fetch` row (kind, trigger, cursor from→to, count), storing no request detail for anonymous reads; verify with api tests, including that a failing log write still answers `200`
- [x] 2.4 Push payloads carry the gained `seq`: an APNs top-level `seq` beside `eventId`, an FCM `data.seq`; the close push carries none; verify with `apns`/`fcm` payload tests and a route test that the byte route's fan-out names the logged `seq`
- [x] 2.5 Update `docs/architecture.md`'s route table (the redirect route, `/files` parameters/headers, the token handling) and `isGatedRequest`'s pin in `HttpBackend` against `app.ts`; verify the pin test and `api-test` pass
- [x] 2.6 Backend contract: union clauses for the cursor delta (the position, a read from it, a withdrawal after a gain), the bad-token 401 and the tokenless read, in `:test:contracts`' `BackendContract`, bound Live against the real api/ and through `BackendMock` (which gains the same rules: positions, the gained/removed log persisted across relaunch, fetch records, the position on each push); the redirect's 302/404 are not a port route (the OS fetches it) and stay api tests (2.1); verify `:adapter:generic:app:jvmTest`, `:adapter:generic:mock:jvmTest` and `ContractCoverageTest`

## 3. The redirect contract clause

- [ ] 3.1 Finish the working-tree draft (`DownloadContract.A_REDIRECT_IS_FOLLOWED_TO_THE_BODY`, the fixture's `r3xx` route and its grammar twin, the bindings' exhaustive `when`s) and run `./gradlew build`; verify green, and `androidPlatformTest` green on the emulator
- [x] 3.2 Record in `docs/testing.md` that the iOS background session's redirect and resume behaviour are a measured fact without a contract host (no URLSession seam in `IosDownload`), citing design.md's Measured table; verify by review

## 4. The app reads incrementally

- [x] 4.1 Backend port + `HttpBackend`: `eventFiles(eventId, cursor?, trigger, token?)` sending `urls=false`, the trigger header and the token, decoding `url` as optional, and answering the `SnapSync-Cursor` header with the assets; verify with `HttpBackendTest` cases (cursor parsed, missing header → failure, `url` absent)
- [x] 4.2 `EventUnionSource` carries cursor + trigger and answers `(assets, cursor)`; `AuthenticatedBackend` treats the union as token-carrying (401 → re-attest, retry once); verify with `BackendServicesTest` and `CredentialedBackendTest`
- [x] 4.3 A per-event cursor in `downloads.db` (SQLDelight migration `5.sqm`) and `DownloadService` methods to read it and to plan + advance it in ONE transaction; verify with `DownloadStoreContract` clauses (cursor advances only with its plan; a failed plan leaves it) on the JVM and over the mocks
- [x] 4.4 `HttpBackend` builds each resource's URL from its base and the D1 template when the union sends none (it owns every route path; asset ids are URL-safe by construction), keeping a url an older backend still sends; verify with `HttpBackendTest` cases
- [x] 4.5 `DownloadController`: delta for `push`/`wake`, full for `foreground`/`join`/`grant`/`reconfigure`/`leave-check` and whenever no cursor is stored; verify with controller tests in `:test:feature` for each trigger
- [x] 4.6 Full reads prune: delete foreign rows that are only planned/enqueued/staged, not claimed, not terminal and absent from the union, releasing their staged bytes; a staging callback for a deleted row discards its bytes; verify with controller tests (withdrawn → pruned; imported → kept; claimed → kept; withdrawn then re-gained → planned again)
- [x] 4.7 Wire the trigger at every caller (push receiver, `reconcileIfDue`, the foreground flow, `JoinUnion`/adoption, the grant's adoption, reconfigure, `everythingReceived`); verify with the flow tests and `./gradlew architectureDiagrams` committed if the flow transcriptions change
- [x] 4.8 The push receivers read `seq` (both platform adapters forward the payload whole, so the `model/` codec `pushSeq` reads APNs' number and FCM's string) and skip the read when the stored cursor is at or past it; verify with `PushSeqTest`, `SilentPushTest` and `DownloadPushReceiverTest`
- [x] 4.9 Update the KDoc of `DownloadController`, `EventUnionSource` and the receivers that describes "re-reads the whole union" and "re-presigns on every foreground"; verify by review

## 5. The event page

- [x] 5.1 `join.astro` reads `/files?urls=false` and builds each URL from the D1 template; verify with the site's build and a local `deno task dev:local` run downloading a zip of a seeded event

## 6. Privacy

- [x] 6.1 Update the Privacy Policy (`site/src/pages/index.astro#privacy`) to describe the recorded reads (install id, reason, time, deleted with the event; browsers recorded without identification); verify the site builds and the text matches `privacy-security`
- [x] 6.2 Review the App Store privacy answers (`metadata/review/notes.md`) and Play's Data safety record (`metadata/play/declarations.md`) for the recorded reads, and record the decision there; verify by review with the operator — decided 2026-10-05: diagnostics, already declared in both stores; recorded in `declarations.md`

## 7. Integration and on-device checks

- [x] 7.1 `:test:integration` rig tests on the JVM host (`IncrementalUnionIntegrationTest`; the rig now plays a push as the mock backend sent it, `seq` included): a burst of pushes after one read reads nothing more; a foreground after a narrowing prunes the not-yet-received photo and keeps a received one (mutation-checked: fails with the prune disabled). An old-shape read through the redirect is not reachable on the JVM rig (its download mock ignores urls) and is covered by `union-read.test.ts` plus the `DownloadContract` redirect clause; verify green under `./gradlew build`
- [ ] 7.2 Journeys on the iOS simulator and the Android emulator against a local api/ (`scripts/sim-contracts`, `scripts/android-journeys`): two members, photos arrive via deltas; verify both jobs green on the PR
- [ ] 7.3 On the SE2 against the dev backend's real S3: a photo downloads through the edge→S3 cross-host redirect, and a background download interrupted by an airplane-mode gap of more than an hour completes; record both in design.md's Measured table
- [x] 7.4 Measure whether the deployed edge compresses `/files` (D9) and compress in the script if it does not; verified 2026-10-05: origin `application/json` responses through the pull zone come back `content-encoding: br` (D9), so nothing is built
