# Measured platform facts: where each one lives after the spec diet

**Scope.** This covers every *measured* runtime-platform fact in `openspec/specs/*/spec.md` and in CLAUDE.md, deduplicated. Platforms: iOS, PhotoKit, URLSession, Keychain, App Attest, BGTaskScheduler, MetricKit, Compose on iOS, SQLite, bunny, and Sentry/Bugsink. A fact the specs state without a measurement ("[S]") is listed only when code depends on it. CI and tooling facts are in the last section; they belong in `docs/`, not in contracts.

**Legend**
- **Covered**: a clause in `test/contracts/.../*Contract.kt`, or a recording in `test/contracts/recordings/`, asserts the fact. A fact the clause only partly asserts is marked **partial**.
- **NOT**: no clause asserts it.
- **Documented at**: repo-relative `file:line`. A ★ marks a KDoc or comment added in this pass. Every addition is comment-only, and nothing is committed.
- **Clause host**: the host a follow-up clause could run on. `REC` means recorded on a device and replayed in CI. "none" means no host can observe the fact.

Spec abbreviations: iph = ios-photokit-upload, ul = upload-lifecycle, lpa = limited-photo-access, ius = ios-url-session-upload, pc = port-contracts, ag = architecture-guards, ias = ios-app-shell, pd = photo-download, ea = event-album, psp = photo-selection-policy, gs = gallery-status, db = database, cr = crash-reporting, dl = diagnostic-logging.

## A. PhotoKit upload-job registration (iOS ≥ 26.1)

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| A1 | A disable with no record returns false with `3201`; this is the clean-device answer. | iph:892, iph:41 | **Clause** `DISABLE_WITH_NO_RECORD_IS_EXPECTED`, recording `UploadExtensionRegistry@IOS_DEVICE_APP.GRANTED.rec` | UploadExtensionRegistryContract.kt:32 | — |
| A2 | Under `.limited`, both enable and disable are refused with `3311`. | iph:802, lpa:21, ul:632 | **Clauses** `ENABLE_/DISABLE_IS_REFUSED_UNDER_A_PARTIAL_GRANT`, recording `…LIMITED.rec` | UploadExtensionRegistryContract.kt:33 | — |
| A3 | A disable that finds a record returns true with no error, and an applied enable reads back true (full grant). | iph:914 | **Clauses** `DISABLE_REMOVES_A_RECORD`, `ENABLE_CREATES_A_RECORD` (GRANTED rec) | PhotoKitExtensionRegistry.kt:52 | — |
| A4 | The configuration record is keyed by bundle id and survives delete/reinstall and reboot. A bare enable over a stale record fails with `3202`; a leading disable repairs it. | iph:239, ul:654, ias:538 | NOT | ★ adapter/ios/app-only/.../registry/PhotoKitExtensionRegistry.kt:74; UploadExtensionRegistry.kt:20 | IOS_DEVICE_APP REC. It needs a "stale record" state, which only another signing identity can create, so it is hard. |
| A5 | `isUploadJobExtensionEnabled` answered false under NOT_DETERMINED for a live record, and true once granted. LIMITED and a differently-signed record are unmeasured. | iph:966, ul:638 | NOT (read-back asserted only under GRANTED) | ★ PhotoKitExtensionRegistry.kt:74 | IOS_DEVICE_APP REC under a third grant file (`…NOT_DETERMINED.rec`, `…LIMITED.rec` read-back) |
| A6 | The selector does not exist below 26.1; calling it traps (unrecognized selector). | iph:959, ul:774, ag:1344 | NOT (structural: the class is not constructed there). A JVM guard in `:test:architecture` pins "never registrable below 26.1". | PhotoKitExtensionRegistry.kt:30 | none (a process abort) |
| A7 | Without `BackgroundUploadURLBase` in the extension's own Info.plist, the enable fails with `PHPhotosErrorDomain -1` and the extension is never launched (SE2 A/B, 2026-08-28). | iph:41, iph:69 | NOT. The CI archive check asserts only that the key is present. | iosApp/BackgroundUploadExtension/Info.plist:29; ★ adapter/ios/ext-safe/.../config/UploadBase.kt:12 | none (build-configuration fact; needs a second build) |
| A8 | Default ATS exempts a loopback IP literal: a registration against `http://127.0.0.1:<port>/…` succeeds, and assetsd delivers the job bytes there in plaintext. | iph:41, iph:81, ios-ci:156 | **partial**: every re-record of `BackgroundTransfer@IOS_DEVICE_PHOTOKIT_EXT` relies on it, but a replay does not re-test it | ★ UploadBase.kt:14 | IOS_DEVICE_PHOTOKIT_EXT REC (already implicit) |
| A9 | Simulator (iOS 26.5): an enable returns false with `-1`, not 3201/3202/3311, and read-back is false. With no record, `creationRequestForJob…` raises `NSInvalidArgumentException` and kills the process. | iph:1027 | NOT | adapter/ios/app-only/.../registry/UploadExtensionRegistry.kt:14; adapter/ios/ext-safe/.../upload/UploadJobQueue.kt:20 | IOS_SIM_APP live, for the `-1` answer only. The exception is a process abort. |

## B. PhotoKit upload jobs and how the OS invokes the extension

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| B1 | A presented job that is not acknowledged makes the OS report `50008`, discard the outstanding jobs, and defer the extension ~300 s, escalating with each attempt. | iph:353, ul:111, ul:441, reconfigure-membership:204 | **partial**: the adapter's side is asserted (a drained job is not presented again: `PRESENTED_RETRY_SPENT_IS_HANDED_UP_ONCE`). The OS's side appears only in the system log. | IosPhotoKitUploadPlatform.kt:80, ★ :85 | none (unobservable to any process) |
| B2 | `resource` is nil for a succeeded job; the destination is the only field reliably present. | iph:317, sync-ledger:1083 | **partial** `PRESENTED_SUCCESS_IS_RECORDED_IN_PLACE` (EXT rec) | IosPhotoKitUploadPlatform.kt:38; PhotoKitJobMapping.kt:38; ledger 7.sqm:4 | IOS_DEVICE_PHOTOKIT_EXT REC (make the nil explicit) |
| B3 | A retry-spent job is presented with no `resource` (SE2, iOS 26.6.2), so it is re-created from the photo its key names. | iph:350 | **Clause** `PRESENTED_RETRY_SPENT_IS_HANDED_UP_ONCE` (EXT rec) | IosPhotoKitUploadPlatform.kt:113; UploadJobApi.kt:73 | — |
| B4 | The destination's headers (Content-Type) survive the OS job store, in both the `.retry` and the `.acknowledge` sets. | iph:347 | **Clauses** `PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY`, `PRESENTED_SUCCESS_IS_RECORDED_IN_PLACE` (EXT rec) | PhotoKitJobMapping.kt:232 | — |
| B5 | The OS-performed upload carries the extension's custom request headers (Authorization) to the wire. | device-attestation:425 | **partial**: Content-Type landing is asserted; Authorization is not | PhotoKitJobMapping.kt:232 | IOS_DEVICE_PHOTOKIT_EXT REC (the fixture records a custom header) |
| B6 | One `process()` call runs ~60 s and is then killed without `notifyTermination`. `notifyTermination` arrives ~55 ms after every normal return. A killed call is followed by a 6–11 min backoff. | iph:1171, pc:301 | NOT (the rig's run tolerates it) | ★ iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift:46; test/contracts/.../Host.kt:41 | IOS_DEVICE_PHOTOKIT_EXT REC (the timing would be provenance, not an assertion) |
| B7 | Invocation cadence: an enable brings a call in ~1 s, a new photo in ~3 s, and a finished job brings none. A job created in `process()` uploads only after the call returns; `PROCESSING` after creating brings a call in ~1 s, after creating nothing in 5 min. | iph:1174–1181, iph:483 | NOT (it is the premise of the PRESENTED-state preparation) | ★ BackgroundUploadExtension.swift:31; BackgroundTransferContract.kt:29; rig ExtensionContractRun.kt:23 | IOS_DEVICE_PHOTOKIT_EXT REC |
| B8 | Job states settle 0.1–5 s after creation or retry. A first failure appears in both the retry and the ack sets; a retry-spent job only in the ack set. Acknowledging removes a job from both. | iph:1183 | **partial**: the PRESENTED clauses assert what is presented, not the settle time | adapter/ios/ext-safe/src/iosSimulatorArm64Main/.../upload/UploadJobQueue.kt:55, :97 | IOS_DEVICE_PHOTOKIT_EXT REC |
| B9 | `creationRequestForJob` raises `PHPhotosErrorLimitExceeded` at the OS job cap. [S] | iph:230 | **partial**: `AT_CAP_DEFERS` asserts deferral; the device cap itself is not reached | PhotoKitJobMapping.kt:267 | IOS_DEVICE_PHOTOKIT_EXT REC (expensive) |
| B10 | A registration that survives a downgrade to `.limited` is still invoked: `process()` ran 4 s after a photo joined the selection (2026-09-21). | iph:834, lpa:22, lpa:404, ul:633, pc:301; CLAUDE.md ② | NOT | ★ app/ios/extension/.../UploadExtensionRoot.kt:221 | IOS_DEVICE_PHOTOKIT_EXT REC under LIMITED (needs a person to narrow the grant) |
| B11 | Four jobs created under GRANTED survived GRANTED→LIMITED→GRANTED. The withheld call was shown none of them; the first call after full access presented all four as succeeded. **Conflict:** usr:474 says the objects landed while `.limited`; iph:852 says that is not established. | iph:845, lpa:400, upload-state-reconciliation:474 | NOT | ★ IosPhotoKitUploadPlatform.kt:50 | IOS_DEVICE_PHOTOKIT_EXT REC across a grant change (manual) |
| B12 | `PHBackgroundResourceUploadExtension` (26.1) is deprecated but is the only protocol runnable on GM devices; iOS 27 brings the async successor. | iph:195 | NOT | BackgroundUploadExtension.swift (header) | none |

## C. PhotoKit reads, the library, and limited access

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| C1 | Predicate parser: `(mediaSubtypes & N) == 0` silently returns zero rows while `NOT ((… & N) != 0)` works. Arithmetic, or the `hasAdjustments` key, aborts the process. | gs:298 | NOT | PhotoKitCandidateSource.kt:182 | IOS_SIM_APP live for the zero-rows form (a CandidateSource clause over a seeded screenshot). The aborts cannot be clauses. |
| C2 | A walk with no capture-date lower bound is watchdog-killed. | gs:295 | NOT | PhotoKitCandidateSource.kt:215; IosDiscovery.kt:76 | none (a process kill) |
| C3 | Resolving by key costs ~4.5 ms per request plus ~3.45 ms per photo (SE2, iOS 26.6). A full walk took 6.1–7.2 s for 224 candidates (iOS 18.7.9) and 145 ms for 1084 (iOS 26.6). A walk stayed outstanding 774 s after suspension. | ius:798, ius:814, sync-status:561 | NOT | ★ adapter/ios/ext-safe/.../discovery/IosDiscovery.kt:107 | IOS_DEVICE_APP REC or IOS_SIM_APP with a generous bound (outcome `NotWithin`). Timing is fragile, so it may be better left as documentation. |
| C4 | `assetResourcesForAsset` is a synchronous XPC call of ~110 ms per asset (SE2). | join-share-count:92 | NOT | PhotoKitAssetFacts.kt:18 | as C3 |
| C5 | A `PHAssetCollection` fetch under NOT_DETERMINED raises the permission dialog (simulator, iOS 26.4, `AUTHREQ_PROMPTING`). | ul:195, iph:1144 | NOT | ★ adapter/ios/ext-safe/.../album/IosAlbumManager.kt:73 | IOS_SIM_APP with a never-granted install (hard: the dialog blocks) |
| C6 | Under `.limited` the user-album walk returns no albums, with no error, even when a selected asset is a member, so the denylist is inert. | psp:768, CLAUDE.md ③ | NOT | ★ IosAlbumManager.kt:70 | IOS_DEVICE_APP REC under LIMITED (AlbumManagerContract `…_UNDER_A_PARTIAL_GRANT`) |
| C7 | Adding an asset already in a collection is a no-op (simulator, iOS 26.5). | ea:166, ea:266, ea:439 | NOT | ★ IosAlbumManager.kt:122 | **IOS_SIM_APP live — the cheapest follow-up**: an `ADD_OF_A_PRESENT_ASSET_IS_A_NO_OP` clause in AlbumManagerContract |
| C8 | The upload extension may create and mutate a `PHAssetCollection` (verified on device). | ea:183 | NOT | IosAlbumManager.kt:28 | IOS_DEVICE_PHOTOKIT_EXT REC |
| C9 | Asset and album creation are unrestricted under `.limited`. Created assets join the selection **only at creation**, so an asset imported before a downgrade is invisible after it. | lpa:26, lpa:424, pd:557; CLAUDE.md ③ | NOT | ★ adapter/ios/app-only/.../download/PhotoKitAssetPresence.kt:40 (and :16) | IOS_DEVICE_APP REC under LIMITED (PhotoLibraryImporter / presence) |
| C10 | A `performChanges` commit survives the death of its process (SIGKILL 200 ms after the block). While it is in flight, a relaunch's presence lookup answers absent (8 of 9 runs at 25–43 MB). | pd:369, pd:401, pd:405 | NOT | ★ PhotoKitAssetPresence.kt:40 | IOS_SIM_APP (needs the rig to kill and relaunch mid-commit; hard) |
| C11 | With the move option, the library takes the file at ingest, before validation: invalid bytes fail with the file consumed. | pd:1043 | **Clause** `AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED` (IOS_SIM_APP live) | IosPhotoLibraryImporter.kt:142 | — |
| C12 | `PHPhotosErrorChangeNotSupported` consumes nothing (iOS 26.2 simulator, SE2 26.6). | pd:1048 | NOT | IosPhotoLibraryImporter.kt:142–157 | IOS_SIM_APP live, if the error can be provoked |
| C13 | The library mints a new identifier per create request, so a repeat import is a second asset. [S] | harness-world-model:560 | **Clause** `A_REPEAT_IMPORT_CREATES_A_SECOND_ASSET` | — | — |
| C14 | Bulk library changes arrive non-incremental: a 5-asset batch reported no itemized inserts. | lpa:191 | NOT | PhotoSelectionSnapshotSource.kt:30 | IOS_DEVICE_APP REC (limited-only observer) |
| C15 | Limited-access prompt: the suppression key is read from each process's **own** bundle. The extension evaluates the prompt at launch, before any app code runs (2026-09-21). | lpa:13, lpa:364; CLAUDE.md ① | NOT | iosApp/BackgroundUploadExtension/Info.plist:53 | none (a system alert is unobservable) |
| C16 | Prompt behaviour per release: an unchanged library and the app's own creations raised none (26.5.2, 26.6.2). A camera photo outside the selection leaked the prompt on 26.5/26.5.2 and held on 26.6.2 (n = 1). Off-flow `assetResourcesForAsset` bursts raised none (26.5.2). Without the key, the July probe saw an app-killing storm. | lpa:16, lpa:93, lpa:104–136, lpa:270, lpa:357; CLAUDE.md ① | NOT | ★ adapter/ios/app-only/.../permission/PhotoSelectionSnapshotSource.kt:33; PhotoLibraryPermission.kt:83 | none |
| C17 | Simulator photo grant: `simctl privacy grant photos` writes a TCC row PhotoKit ignores; `applesimutils` works; no partial grant exists. An unentitled `test.kexe` reads DENIED. | pc:297, pc:299 | **partial**: the `NO_GRANT_*` clauses run on IOS_SIM_KEXE | Host.kt:20–33 (the simctl/applesimutils half is only in pc:299 and `scripts/`) | host-matrix fact, not a clause. Keep it in Host.kt KDoc or docs/testing.md. |

## D. URLSession, BGTaskScheduler, and the app process

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| D1 | On a simulator, nsurlsessiond rejects every third-party background session ("does not have a bundle ID"). The client sees `NSCocoaErrorDomain 4097`, then "remote session is unavailable", then `-1`. No `didBecomeInvalidWithError` follows. A default session never sends `DidFinishEvents`. | ius:602–646 | NOT (bindings sidestep it by binding the default session on the simulator) | adapter/ios/app-only/.../urlsession/TransferSessions.kt:21–63 | IOS_SIM_APP live, as a host-characterisation clause (optional) |
| D2 | A force-quit delivered `-999` at the next launch; a transfer the OS drops silently has never been observed. | ius:54, ul:844 | NOT | IosUrlSessionUploadPlatform.kt:45 | IOS_DEVICE_APP REC (the rig kills and relaunches the app) |
| D3 | In-field hand-off-to-completion gaps of 27 min, 65 min and 4 h 49 m. | ius (field) | NOT | IosUrlSessionUploadPlatform.kt:255 | none |
| D4 | The URLSession tier uploads under `.limited` on the first attempt (bytes, manifest, notify). | ius:521 | NOT (`BackgroundTransferContract` has no LIMITED binding) | — (spec only) | IOS_DEVICE_APP REC under LIMITED |
| D5 | HTTP/3 must be disabled (`setAssumesHTTP3Capable(false)`) for uploads to the edge. [S] | ius:922 | NOT | UploadUrlRequest.kt:12 | none (needs the public edge) |
| D6 | On a simulator, BGTaskScheduler refuses every submit (`BGTaskSchedulerErrorDomain/1`). | pc, testing-architecture:365 | **Recording** `BackgroundScheduler@IOS_DEVICE_APP.rec` (device side) | IosBackgroundScheduler.kt:19 | — |
| D7 | The deprecated one-argument `openURL(_:)` answered false for the store link on iOS 26.6.2; `openURL(_:options:completionHandler:)` opened it. | min-app-version:275 | **Clause** `CLAIMED_URL_IS_ACCEPTED` (`LinkOpener@IOS_DEVICE_APP.rec`) | IosLinkOpener.kt:24 | — |
| D8 | An in-process request on a suspended app has its idle timer expire unobserved. | ias:868 | NOT | DarwinHttpClient.kt:14 | none |
| D9 | The App-Group container survives an app update but not a delete-and-reinstall. | ul:346, sync-ledger:423 | NOT | SnapSyncRoot.kt:248; FileBackedConfigStore.kt:57 | none (needs an install cycle) |
| D10 | MetricKit reports accrue only after first touch and are delivered once, held while no subscriber exists. Deliveries are not serial with other process work. One delivery of 12 payloads with call stacks serialized to 15.1 MB and blocked 18 s. Metric values contain spaces (`118417 kB`). | ias:894, dl:208, cr:310, dl:604 | NOT | MetricKitProcessMetricSource.kt:48, ★ :139; LogContext.kt:20; domain/model/.../ProcessMetrics.kt:175 | none practical (daily, one-shot delivery) |
| D11 | `Dispatchers.IO` is in the Kotlin/Native klib but `internal`. | module-architecture:623 | NOT (compile fact) | SnapSyncRoot.kt:1002; IosDiscovery.kt:70 | a compile check, not a contract |

## E. Keychain, App Attest, and entitlements

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| E1 | Unentitled `test.kexe`: every `SecItem*` call returns `-25291`, and the App-Group lookup returns nil. A simulator `.xctest` returns `-34018`. | pc:297, pc:298 | **partial**: the `INACCESSIBLE_*` clauses (SecureStore/AttestStore/ConfigStore) on IOS_SIM_KEXE assert the mapping, not the code | test/contracts/.../Host.kt:20–33 | — |
| E2 | `keychain-access-groups` makes an ad-hoc-signed simulator app un-launchable. Omitting it yields `-34018`, not errSecItemNotFound. | device-identity:53, pc:299 | NOT | adapter/ios/ext-safe/src/iosSimulatorArm64Main/.../keychain/DeviceIdStores.kt; rig DeviceContracts.kt:114 | IOS_SIM_APP live (a `-34018` read maps to Unavailable) |
| E3 | An item with no explicit access group lands in whatever group the signing entitlements select at write time; the field showed split device ids. | device-identity:21 | NOT (`RESTRICTED_*` covers the accessibility upgrade, not the group) | KeychainDeviceIdentity.kt:79–86 | IOS_DEVICE_APP REC (SecureStore legacy-group state) |
| E4 | `AfterFirstUnlock` items are readable while locked once the device has been unlocked since boot; the wrong class shipped as a lock-time crash. | ag:22, device-identity:111 | **partial**: `RESTRICTED_*` clauses (SecureStore rec) assert the upgrade in place; a locked read has no host | KeychainAttestStore.kt:14 | none (no locked host) |
| E5 | `DCAppAttestService.isSupported` is false in the upload extension and true in the app. | device-attestation:458 | **partial**: `A_SUPPORTED_SERVICE_SAYS_SO` (app rec); UNSUPPORTED is asserted only on IOS_SIM_KEXE | IosAttestKey.kt:28; UploadExtensionRoot.kt:153 | **IOS_DEVICE_PHOTOKIT_EXT REC**: bind AttestKey there for the `AN_UNSUPPORTED_*` clauses |
| E6 | An App Group exists only under an ad-hoc signature; an unsigned simulator build has none. | pc:299 | NOT | rig / `scripts/sim-sign` | host-matrix fact (docs/testing.md) |

## F. Link delivery and UI hosting (not port facts)

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| F1 | iOS 18.7.9 (iPhone XS) with a custom scene delegate while running: `willContinue…` fires, then neither continue nor didFail. `.onOpenURL` delivered the link there (build 687). On 26.6, `.onOpenURL` fired for 2 of 4 deliveries; the union of both hooks always delivered. With a custom scene delegate SwiftUI's continuation modifier never fires (8 of 8). | ias:108–138, ag:185–224 | NOT | iosApp/iosApp/iOSApp.swift:131–149, :212, :276–286; SnapSyncRoot.kt:689, :749 | none (needs an external tap). Keep in code. |
| F2 | The platform delivers the same link twice: cold on 18.7.9 (~130 ms apart); on 26.6 while running (8 ms) and cold (105 ms). | ias:150, event-link:605, join-event:428 | NOT | ★ app/ios/src/iosMain/.../SnapSyncRoot.kt:818 | none |
| F3 | The simulator's associated-domains entitlement makes the app un-launchable; `simctl openurl` delivers nothing. | ag:225 | NOT | (spec only) | none; add to docs/testing.md |
| F4 | `dvt launch` foregrounds without connecting a scene session. | ias:163 | NOT | iosApp/iosApp/ContentView.swift:31 | none |
| F5 | CMP 1.11.1: scene rebuilds follow a change in value; handing over an installed controller detaches the scene; Metal textures corrupt after background (CMP-5978). | ias:53–78 | NOT | MainViewController.kt:38, :104 | none |
| F6 | SE2 / iOS 26.5 / CMP 1.11.1: the IME inset does not reach a bottom-sheet popup (`imePadding()` = 0). | design-system:643 | NOT | ui/components/.../AppTextPromptSheet.kt:47 | none |

## G. SQLite (on device) and the bunny backend

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| G1 | The native driver refuses a database newer than the binary's schema. | sync-ledger:511 | NOT | ledger 8.sqm:22, 4.sqm:16, 10.sqm:38 | IOS_SIM_KEXE / JVM (a LedgerStoreContract clause: "a newer-schema file is refused") |
| G2 | SQLite: `DROP COLUMN` needs 3.35+ and is refused for a column a trigger names; inside a trigger `INSERT OR REPLACE` takes the outer statement's conflict resolution. | sync-ledger:428, :1388, :1414 | NOT | 2.sqm:7; Ledger.sq:58, :77 | IOS_SIM_KEXE (native SQLite) |
| G3 | On bunny, a bare `TEXT PRIMARY KEY` accepts NULL; `PRIMARY KEY NOT NULL` rejects it. | db:105 | NOT | api/src/db.ts:25 | new host: JVM/Deno against the **deployed** store (none exists; the backend contracts use the local `serve.ts --ephemeral`) |
| G4 | `PRAGMA foreign_keys` defaults to 1 on bunny (unlike stock SQLite) and persists across requests. | db:119 | NOT (deliberately not in the health probe) | ★ api/src/db-libsql.ts:21 | the deployed-store host (as G3), or a deploy-time assertion (`api/src/scripts/assert-schema.ts`) |
| G5 | Ten devices racing for three slots: read-then-write enrolled 10; one conditional INSERT enrolled exactly 3. | db:478, event-limits:198 | NOT against bunny (the local api/ contracts run on the filesystem store) | api/src/db.ts:149 | the deployed-store host |
| G6 | The conditional enroll affects 0 rows both when the event is full and when it does not exist. | db:503 | NOT | api/src/db.ts:152 | JVM (EventJoin contract over the local api/) — engine-portable |
| G7 | Read-your-writes held in every trial (workstation to test DB, not edge to production). | db:583, scheduled-cleanup:133 | NOT | api/src/scripts/sweep.ts:26 | the deployed-store host |
| G8 | The bound-parameter limit per statement on the deployed store. | db:322 | NOT | api/src/db.ts:353 | the deployed-store host |
| G9 | Storage keeps a directory tombstone after its last object is deleted; a listing returns `200 []` for both. | scheduled-cleanup:9 | NOT | api/src/storage.ts:165 | a live-zone host with a scratch prefix. Careful: the zone is shared (see CLAUDE.md). |
| G10 | `foreign_keys=off` versus `defer_foreign_keys`: a table rebuild cascades deletes under deferral. | db:644 | NOT | api/src/dev/replay.ts:95 | JVM (local replay) |
| G11 | `/code` and `/publish` succeed whether or not the script boots. The script-scoped deploy key gets 401 on the release endpoints. | backend-deployment:19, :874 | NOT (the boot probe detects the first at deploy time) | api/src/scripts/probe.ts:5, :26 | CI fact (deploy workflow) |
| G12 | App Attest on the server side: `attest.ts` states three device-measured facts. | device-attestation | NOT | api/src/attest.ts:4 | IOS_DEVICE_APP REC (AttestKey) already covers the ceremony; the rest needs a JVM verifier test |

## H. Sentry and Bugsink

| # | fact | source spec | covered by | documented at | could become a clause on |
|---|---|---|---|---|---|
| H1 | Bugsink caps events at 1 MiB, judged on the decoded body, and drops attachments. Context strings are stored byte-identical at 340 KB. | cr:462, dl:28; CLAUDE.md | **partial** `WIRE_WORST_CASE_DUMP_ARRIVES` (IOS_SIM_KEXE, real SDK) against a **modelled** 1 MiB in LoopbackIngest; the real Bugsink is never hit | SentryDiagnosticsReporter.kt:100; LoopbackIngest.kt:247; EventBounds.kt:14 | a live-ingest host (none exists) |
| H2 | sentry-cocoa deletes a cached envelope only on `200` and sends the oldest first, so an over-cap event blocks the queue across launches. | cr:466, dl:435; CLAUDE.md | **partial**: the contract test notes it (SentryDiagnosticsReporterContractTest.kt:44); no clause asserts the blocking | EventBounds.kt:8 | IOS_SIM_KEXE (loopback ingest answers 413, then a later event is asserted stuck) |
| H3 | A scope tag reaches `beforeSend`; the latest account rides later events; the per-install `user.id` is kept; automatic events are scrubbed. | cr:26, cr:385 | **Clauses** `CONFIGURED_DUMP_IS_DELIVERED_VERBATIM`, `WIRE_LATEST_ACCOUNT_RIDES_LATER_EVENTS`, `WIRE_INSTALL_ID_IS_KEPT`, `WIRE_AUTOMATIC_EVENTS_ARE_SCRUBBED` | SentryDiagnosticsReporter.kt:66, :169 | — |
| H4 | Bugsink groups by message text. | cr:178 | NOT | SentryDiagnosticsReporter.kt:198 | a live-ingest host |
| H5 | The KMP SDK always sets `release`, clearing the native value; `dist` overwrites at send time. [S] | cr:91, cr:137 | NOT | SentryDiagnosticsReporter.kt:59, :135 | IOS_SIM_KEXE (a WIRE clause on release and dist) |
| H6 | The test executable has no bundle identifier, so no `process` tag is derived there. | cr:390 | NOT | SentryDiagnosticsReporter.kt:69 | host fact |

## I. CI and tooling facts (out of scope for contracts; they belong in docs/deployment.md)

These are measured, but they are tooling facts, not runtime-platform facts, and no port contract could hold them. Each has an existing home, except where marked:
- `//` in an xcconfig truncates the DSN (ios-testflight-delivery:181): DeploymentValues.kt:17.
- `asc review doctor` passed a version the submit then refused for a missing `whatsNew` (ios-appstore-release:19): CLAUDE.md only.
- Debug versus Release archive time, 10.6 vs 13.0 min, and the framework link taking 71% (ios-ci:20): spec only.
- An empty runner keychain mints a new certificate every run (ios-testflight-delivery:71): spec only.
- A system notification landed in 1 of 2 screenshot runs (ios-appstore-metadata:321): CLAUDE.md.
- Artifacts lose executable bits and symlinks (ios-ci:22): spec only.

## Conflicts found while inventorying (for review)

1. **Does the extension get a termination notice?** ag:436 says the extension has a termination callback that announces the kill. pc:301 and iph:1171 say, from measurement, that a ~60 s kill comes with **no** notice, and that `notifyTermination` follows only normal returns. The measured statement wins; it is now in the Swift shell comment.
2. **Did bytes move while the grant was `.limited`?** upload-state-reconciliation:474 says four objects landed within ~30 s. iph:852 says whether bytes moved is not established.
3. **Does the extension process outlive one call?** iph:765 says the extension process outlives a single invocation; iph:706 and ius:420 say it dies each cycle.

## Suggested follow-up contract work, cheapest first

1. AlbumManager `ADD_OF_A_PRESENT_ASSET_IS_A_NO_OP` on IOS_SIM_APP, run live (C7).
2. AttestKey bound on IOS_DEVICE_PHOTOKIT_EXT and recorded (E5).
3. BackgroundTransfer: assert that a custom header lands, so Authorization is covered (B5), and that a succeeded job's `resource` is nil (B2). Both go into the EXT recording.
4. UploadExtensionRegistry read-back under NOT_DETERMINED and LIMITED, as new grant recordings (A5).
5. AlbumManager and PhotoLibraryImporter under LIMITED on IOS_DEVICE_APP, recorded (C6, C9).
6. LedgerStore: "a newer-schema database is refused" plus the trigger/DROP COLUMN semantics, on IOS_SIM_KEXE (G1, G2).
7. CandidateSource "screenshot exclusion returns non-screenshots" on IOS_SIM_APP, which pins the predicate form (C1).
8. DiagnosticsReporter "an over-cap envelope blocks later ones" on IOS_SIM_KEXE (H2).
9. A new host that answers against the deployed bunny store, for G3–G5 and G7–G8. It needs a credential design and the shared-zone caution.

The rest (alerts, link delivery, locked-device reads, 50008, process aborts, timings) has no host that can observe it. Those facts stay as ★ and existing code comments.
