## 1. Spike — de-risk before coding (iOS 26.1 SDK + device)

- [x] 1.1 Confirm the 26.1 API surface. *(resolved by klib metadata dump + local compile: `PHBackgroundResourceUploadExtension : AppExtension` (Swift-only protocol absent from K/N → Swift shell required); 26.1 entry is `process()`+`notifyTermination()` (NOT the iOS-27 `processJobs()`); the entire job/discovery/change-request API IS bound and compiles. `@main` declaration is the Swift-side part of 6.1)*
- [ ] 1.2 **[device]** Bootstrap question: does the system invoke `process()` with an empty queue after enable? — **decided Model B** (trust it does; no hedge). Cannot be runtime-tested without a device; only an on-device run (7.3) can disprove it.
- [ ] 1.3 **[device]** Verify `https://dummy.invalid` is accepted under `BackgroundUploadURLBase` host validation; pick a fallback unrouted-but-valid host if rejected.
- [x] 1.4 Confirm `PHAssetResourceUploadJob` ↔ resource ↔ key association. *(resolved: `.resource`, `.assetLocalIdentifier`, `.state`, `fetchJobsWithAction`, `changeRequestForUploadJob` all bound and used; key built from `cloudIdentifierMappings` + `PHAssetResourceType`)*
- [x] 1.5 Swift↔Kotlin interop. *(resolved: `UploadExtensionRoot.process()` returns a plain `Boolean` via `runBlocking` — no Flow/suspend across the bridge, so **no SKIE needed**. Whether a Photos/background capability appears in Xcode is the on-device 3.x/7.x check)*

## 2. Manual setup checklist (Apple Developer portal / ASC / signing — must precede first signed build)

- [x] 2.1 Register App Group `group.app.snapsync` in the Developer portal (Identifiers → App Groups). *(done)*
- [x] 2.2 Enable the **App Groups** capability on the `app.snapsync` App ID and assign `group.app.snapsync`. *(done — verified: the signed `ios-build` archive provisioned the group and uploaded to TestFlight, run 27780918346)*
- [ ] 2.3 Create the extension App ID (e.g. `app.snapsync.BackgroundUpload`) with the **App Groups** capability, assign the same group.
- [ ] 2.4 Confirm cloud-managed signing (ASC Admin API key, Team `E9Z8BADH58`) provisions **both** bundle ids with App Groups; regenerate/refresh profiles if needed.
- [ ] 2.5 Confirm **no new App Store Connect app record** is needed (the extension ships inside the `app.snapsync` archive) and no new privacy/review questionnaire is triggered.
- [ ] 2.6 Decide the App-Group DB file-protection class (`NSFileProtectionCompleteUntilFirstUserAuthentication`) so a locked-device extension can write.

## 3. Xcode target + module scaffolding

- [x] 3.1 Add the `:app:ios:photokit-extension` KMP module (iosArm64 + iosSimulatorArm64) depending only on `:domain:engine`, producing its own static framework; wire it into `settings.gradle.kts`. *(compile-verified on Linux: configures + `SnapSyncUploadKit` framework tasks generated)*
- [ ] 3.2 **[Xcode/macOS]** Add the extension Xcode target — must be done in Xcode (hand-editing `project.pbxproj` is unverifiable here and risks the green build). The source/plist/entitlements are **drafted** (see below); in Xcode: (a) add a new target via the PhotoKit background-upload / Generic Extension template, bundle id `app.snapsync.BackgroundUpload`, deployment target **26.1**; (b) set its principal source to `iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift`, Info.plist to the drafted one, `CODE_SIGN_ENTITLEMENTS` to the drafted `.entitlements`; (c) add a "Compile Kotlin Framework" run-script phase that runs `./gradlew :app:ios:photokit-extension:embedAndSignAppleFrameworkForXcode` and link the resulting **`SnapSyncUploadKit`** framework; (d) embed the extension in the app target (Copy into PlugIns/Extensions). The **app** target stays at **iOS 18** (the app-side `setUploadJobExtensionEnabled` is runtime-guarded by `SnapSyncRoot.backgroundUploadSupported()` to 26.1+).
- [~] 3.3 Extension Info.plist — **drafted** at `iosApp/BackgroundUploadExtension/Info.plist` (`NSExtensionPointIdentifier = com.apple.photos.background-upload`, principal class, `BackgroundUploadURLBase = https://dummy.invalid`). **[Xcode/macOS]** verify against the 26.1 SDK (classic `NSExtension` dict vs ExtensionKit `EXAppExtensionAttributes`) and wire into the target.
- [~] 3.4 App Group entitlements. **App target: done** — `iosApp/iosApp/iosApp.entitlements` carries `application-groups` (+ main's `keychain-access-groups`), wired via `Config.xcconfig`'s `CODE_SIGN_ENTITLEMENTS`; **verified** by the green signed `ios-build` (provisioned `group.app.snapsync`, fixing the launch crash). **Extension entitlements drafted** at `iosApp/BackgroundUploadExtension/BackgroundUploadExtension.entitlements` (App Group + keychain). **[Xcode/macOS]** wire onto the extension target (3.2). `iosLedgerBackend()` keeps failing fast on a nil container (no fallback) — a missing App Group means no shared ledger.

## 4. App-Group ledger backend (`:domain:engine` iosMain)

- [x] 4.1 Change `iosLedgerBackend()` to resolve the `group.app.snapsync` container path (single naming site) and open SQLite in WAL mode. *(compile-verified; WAL is the native driver default; `containerURLForSecurityApplicationGroupIdentifier` path)*
- [x] 4.2 Implement the cross-process Darwin wiring: post a darwin-notify on every `put`; merge an observer of it into the backend's `changes` flow. *(compile-verified; uses `notify_post`/`notify_register_dispatch` — capturing dispatch handler — instead of `CFNotificationCenter`+staticCFunction. Cross-process runtime behavior is device-only, unverified)*
- [ ] 4.3 Extend/keep the `LedgerBackendContract` (iosTest) to cover App-Group path + cross-process ding behavior where testable.

## 5. Discovery + engine core (`:app:ios:photokit-extension`, Kotlin)

- [x] 5.1 Implement the discovery cursor (`DiscoveryStore`). *(compile-verified. v1 simplification: change token held in-process only, not persisted across extension death — cold start re-enumerates, ledger makes it harmless. No persisted deferred set / residueIds — dropped from this slice; per-record token persistence is the follow-up)*
- [x] 5.2 Implement discovery: first-run full `PHAsset.fetchAssetsWithOptions` + baseline `currentChangeToken`; steady-state `fetchPersistentChangesSinceToken` → `enumerateChangesWithBlock` → inserted/updated ids; expiry (null result) → full re-enumeration. *(compile-verified. Token advances per CYCLE, not per record — noted deviation; ledger dedups)*
- [x] 5.3 Resolve `PHCloudIdentifier` via `cloudIdentifierMappingsForLocalIdentifiers` (once per cycle); fan assets out to `PHAssetResource`s; build `Resource(filename = "<cloudId>-<kind>.<ext>", version = modificationDate epoch, data = PHAssetResource)`; **skip** `identifierNotFound` (no deferred set — routine re-enumeration retries; `localIdentifier` is only a transient discovery handle, never a key). *(compile-verified)*
- [x] 5.4 Add `DummyUploadRequestProvider` (mints + logs `https://dummy.invalid/<encoded key>`) in this module. *(compile-verified; deterministic percent-encoded path, kermit log line per emit)*
- [x] 5.5 Drive the shared `SyncEngine` with `ResourceChanged`; on `Work` → `creationRequestForJobWithDestination(...)` in `performChangesAndWait` (engine's `decide()` does `recordRequested`); on `AlreadyUploaded` → skip. *(compile-verified)*
- [x] 5.6 Drain disposition: `fetchJobsWithAction(Acknowledge/Retry)` and `acknowledge` all via `changeRequestForUploadJob` (drains rather than retries dummy-failed jobs); `process()` returns terminal bool. *(compile-verified)*
- [x] 5.7 Assemble the extension composition root (`UploadExtensionRoot`: App-Group `LedgerWriter` + `SyncEngine` + dummy provider + watcher; `runBlocking` cycle). *(compile-verified)*

## 6. Swift shell + app-side enablement

- [~] 6.1 Thin `@main` Swift principal class — **drafted** at `iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift`: conforms to `PHBackgroundResourceUploadExtension`, forwards `process()` → `UploadExtensionRoot.shared.process()` (plain `Bool` → `.completed`/`.failure`), no-op `notifyTermination()`. *(Kotlin side returns a `Boolean` — no Flow/suspend bridge, no SKIE.)* **[Xcode/macOS] pending:** never compiled here (no Swift toolchain); verify the exact 26.1 protocol shape (`process()` sync vs async, `@main`/`AppExtension` vs `NSExtensionPrincipalClass`, result-case names) against the SDK when wiring the target (3.2).
- [x] 6.2 In `:app:ios`, call `setUploadJobExtensionEnabled(true)` when photo access becomes `GRANTED` (idempotent-safe), in `SnapSyncRoot`; the app still holds only `LedgerReader`/`LedgerWatcher`. *(compile-verified)*
- [ ] ~~6.3 app-side ignition seed~~ — **dropped**: chose Model B (no ignition; trust the system to call `process()` after enable). Revisit only if an on-device test shows `process()` never fires.

## 7. CI + on-device verification

- [x] 7.1 **[CI/macOS]** Wire the merge gate to compile the extension module: `ios-build` runs `:app:ios:photokit-extension:compileKotlinIosArm64` (the shipping device target, in the device-build job). *(removed once the Xcode extension target embeds `SnapSyncUploadKit` in the archive, 3.2–3.4)*
- [ ] 7.2 **[CI/macOS]** Ensure the main-only device archive signs+bundles the extension via cloud-managed signing.
- [ ] 7.3 **[device]** On a physical iOS 26.1 device: grant full access, confirm `process()` runs, dummy URLs are logged, and the app's `pending` count climbs (cross-process ledger + Darwin ding); jobs drain. *(also closes spike 1.2 and 1.3)*

## 8. Testability (ports & adapters — keep PhotoKit glue thin, test the logic on the sim)

- [x] 8.1 Split the extension module: `commonMain` holds the testable core (`UploadCycle` orchestration, `UploadJobPlatform` port, pure `UploadKeys`); `iosMain`'s `IosUploadJobPlatform` holds only the raw PhotoKit calls (enumeration, cloud-id mapping, job create/fetch/ack). *(compile-verified; replaces the monolithic `PhotoLibraryWatcher`)*
- [x] 8.2 `commonTest` (runs on the sim via `iosSimulatorArm64Test`): `UploadCycleTest` (drain→decide→create + dedup/re-upload against a fake platform + real engine + `InMemoryLedgerBackend`) and `UploadKeysTest` (`<cloudId>-<kind>.<ext>` layout). *(compile-verified; executed by CI `ios-test`)*
- [x] 8.3 `iosTest` `PhotoKitSmokeTest`: confirms the general PhotoKit enumeration surface (`authorizationStatus`, `fetchAssets`) is callable on the simulator without trapping. *(compile-verified; executed by CI `ios-test`)*
- [ ] 8.4 **[device]** The adapter's cloud-id mapping + upload-job create/fetch/ack are device-only (iCloud / background-upload subsystems unavailable on the sim) — verified by the on-device run 7.3, not unit tests.
