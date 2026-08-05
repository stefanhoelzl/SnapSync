## 1. The law

- [x] 1.1 Add the `Absence is never silent` requirement to `openspec/specs/module-architecture/spec.md`, citing the five conforming seams (`ConfigFileRead`, `ConfigRead`, `KeychainRead`/`readExisting`, `JoinLoad`, `SwitchDecision`) as the practice it names.
- [x] 1.2 Add the one-line digest entry to `CLAUDE.md`'s **The laws (digest)** section, matching the spec's wording (`LawsDigestTest` keeps the two in sync — expect it red until both sides agree).
- [x] 1.3 Run `:test:architecture --tests '*LawsDigestTest*'` and confirm it passes only once spec and digest match.

## 2. Name the outcomes at the door

- [x] 2.1 Replace `eventLinkFromUserActivity`'s `String?` return in `domain/model/UniversalLinkActivity.kt` with a sealed outcome distinguishing *forwarded* / *not a browsing-web activity* / *no webpage URL*, keeping the `when` inside `model/` so `:app:ios` stays straight-line under the detekt shell gate.
- [x] 2.2 Update `forwardEventLink` to return that outcome so the caller can name it in its exit line, without moving the dispatch out of `model/`.
- [x] 2.3 Extend `UniversalLinkActivityTest` in `commonTest` to cover all three outcomes (runs on JVM and `iosSimulatorArm64`).
- [x] 2.4 Audit the other entry-point filters for the same shape — starting with the silent-push `eventId` codec — and give each a named outcome or a stated consequence.

## 3. Instrument the app-process entry points

- [x] 3.1 Move the `log.invocation` wrap from `LiveShell`/`ForgeShell` up onto `SnapSyncRoot`'s entry points, so the marker and the wrapper sit on one declaration and the shells stop establishing the scope. Keep each shell's inner body lines.
- [x] 3.2 Instrument `SnapSyncRoot.onUserActivity` — the reported gap — recording `activityType` and whether a webpage URL was present **before** the filter, and naming the outcome from 2.1 on exit.
- [x] 3.3 Add `result =` to every entry point so the exit line names what it decided, generalizing `UploadExtensionRoot.process()`'s existing form.
- [x] 3.4 Instrument `MainViewControllerKt.MainViewController()` — the second Swift→Kotlin door — and confirm `applyLaunchEnvMembership` / `applyLaunchEnvSeed` keep their own contexts (they run in escaping `LaunchedEffect`s).
- [x] 3.5 Add the entry-point marker annotation in `domain` `model/`, beside `Logger.invocation`, and apply it to every derived entry point.
- [x] 3.6 Confirm no `if`/`when` was introduced into `:app:*`: `./gradlew detektAppShell`.

## 4. The Swift shells and the extension

- [x] 4.1 `BackgroundUploadExtension.notifyTermination()` — forward to Kotlin and record that the OS terminated the cycle, so a killed `process()` stops appearing as an enter with no exit and no reason.
- [x] 4.2 Add the corresponding entry point on `UploadExtensionRoot`, instrumented like `process()`.
- [x] 4.3 `AppDelegate.didFailToRegisterForRemoteNotificationsWithError` — replace the interpolated `NSLog` (which os_log redacts wholesale, so the line appears nowhere) with a forward to Kotlin.
- [x] 4.4 `UploadExtensionRoot.attestToken()` — log the status before returning `null`, so a `-34018` no longer looks identical to a locked-device `-25308`. Keep the collapse; its reasoning is sound for the cause it was written for.
- [x] 4.5 Verify the Swift halves compile on macOS CI — **DONE** via the ssh-mac loop (macOS 26.5.2 / Xcode 26.6): `ARCHIVE SUCCEEDED` for the Debug archive, so both warm hooks, `notifyTermination`, and `onPushTokenFailure` compile against the new Kotlin API.

## 5. The adapter callback surfaces

- [x] 5.1 Instrument the three platform-protocol conformances: `PhotoSelectionObserver.photoLibraryDidChange`, `IosDownloadTransport.Delegate`'s four `URLSession*` members, `IosUrlSessionUploadPlatform`'s two.
- [x] 5.2 Instrument the `PhotoLibraryPermission` notification observer body.
- [x] 5.3 Apply the severity policy: `Info` for once-per-platform-event entries, `Debug` for the per-item ones (`photoLibraryDidChange`, per-task transfer callbacks) so a large import cannot flush the breadcrumb window or roll the log.

## 6. The UI door

- [x] 6.1 Decorate the `UserCommands` bundle at its single construction site in `domain/compose/SnapSyncApp.kt`, using the already-present `ports.logScope`.
- [x] 6.2 Give taps a distinct context namespace (`tap.*`) so a reader can tell platform-initiated from user-initiated work without reading the source.
- [x] 6.3 Confirm `:ui:presentation` is unchanged (it may not reference `ports/`, so the decoration cannot live there) and the presentation gate still passes.

## 7. The nullable-seam sweep

- [x] 7.1 Work the `ports/` verdict table already recorded in `design.md`: add the seven missing one-line consequences (`DeviceLogSource.tail`, `AlbumMapStore.get`, `DeviceManifestStore.loadLastUploaded`, the three `AttestClient` members, `DownloadTransport.destinationFor`/`start`).
- [x] 7.2 Decide `JoinedEventMarker.read` — absence carries "reinstall" semantics, so establish whether its backing store can fail distinguishably, and either separate the states or state the bounded consequence.
- [x] 7.3 Audit the 6 nullable members of `model/` — including `UniversalLinkActivity` (fixed in group 2) and `PushPayload` — and the composition roots' nullable members, notably `UploadExtensionRoot.attestToken`.
- [x] 7.4 Record `AlbumManager.ensureCreated` as a deferred design question rather than changing behavior here.
- [x] 7.5 Extend the verdict table in `design.md` with `model/` and the roots so the reasoning survives archiving.

## 8. Warm event-link delivery — ATTEMPTED, FALSIFIED, REVERTED

- [x] 8.1 Add SwiftUI's `.onContinueUserActivity` alongside `scene(_:continue:)` — **done, then reverted**: measured on device 2026-08-04 as **0 firings in 8 warm deliveries**. A scene has one delegate, this app installs its own for the cold path, so SwiftUI's — which feeds that modifier — is never created. July's matrix measured it in the opposite configuration.
- [x] 8.2 Forward each hook under a distinct entry-point name — **kept**. `onLaunchActivity` (cold) and `onSceneContinueActivity` (warm) are what make the next dump decisive.
- [x] 8.3 Duplicate suppressor — **removed with 8.1**: with one warm hook there is no duplicate, and a suppressor citing a falsified premise is the unexamined machinery this change's own law forbids.
- [x] 8.4 Suppressor tests — removed with 8.3.
- [x] 8.5 Rewrite the `iOSApp.swift` comment block — **done**, now recording why the modifier *cannot* be added rather than why it was rejected.
- [x] 8.6 `EventLinkDeliveryTest` pins the delivery hooks — **done**, narrowed to the two that exist, with the measurement in its failure message.

## 9. The guards

- [x] 9.1 Add the derived entry-point guard to `:test:architecture`: derive the population by the three rules, assert the marker and that each body opens with the wrapper, fail loudly when the derivation yields nothing, and name the un-derivable residue in the failure message.
- [x] 9.2 Add the nullable-seam inventory guard over `ports/` + `model/` + the composition roots: population derived, verdicts authored, red on a new seam with no verdict and on a stale verdict whose seam is gone.
- [x] 9.3 Extend `SwiftShellGuardTest` with the body rule — every Swift shell function forwards to Kotlin — and confirm it goes red against 4.1 and 4.3 before they are fixed.
- [x] 9.4 Prove each new guard red against a deliberate sabotage before relying on it (the discipline `EventLinkDeliveryTest` records: its first draft passed with the delegate deleted).
- [x] 9.5 Declare the guarded sources as inputs of the guard test tasks so they re-run when their subject changes.

## 10. Verify

- [x] 10.1 `./gradlew build` — guards, `commonTest` outcome and suppression tests, and the shell gates.
- [x] 10.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets.
- [x] 10.3 `./gradlew architectureDiagrams` and commit any regeneration (`diagrams` is a required check).
- [x] 10.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 10.5a On device (SE2, iOS 26.5.2), headless half — **DONE**: dev IPA built, re-signed (no wildcard leaked), installed, launched. Confirmed the new entry points fire and name their results (`MainViewController`, `photoPermission.onDidBecomeActive = GRANTED`, the renamed `runDownloadBackstop.run` span), and that **every synchronous entry-driven line carries a prefix** (21/21). 17 of 38 lines carry none — all escaping `scope.launch` work (HTTP, attestation, push registration), which the design scopes OUT; the original wording of this task ("every line") overstated what `design.md` promises and is corrected here.
- [x] 10.5b On device, tap half — **DONE 2026-08-04 by the operator**, on the pre-strip build: 8 warm link taps produced 8 `onSceneContinueActivity` entries (`type=NSUserActivityTypeBrowsingWeb url=present` → `onOpenUrl`), two of them driving a full switch (`tap.leave` → `tap.commitJoin` → `DELETE` old + `PUT` new), and `tap.share` / `tap.leave` / `tap.commitJoin` / `tap.create` all appeared with their `tap.*` context. **Residual, stated honestly:** the final build (after the SwiftUI hook and suppressor were stripped) has not been on a device. The strip only REMOVES code from that path — `onSceneContinueActivity` → `deliverUserActivity` → `forwardEventLink(…, ::onOpenUrl)` is now shorter than the version measured — so the risk is low but not zero.
- [x] 10.6 Record honestly that the **iOS 18 outcome is unverifiable on available hardware** — the SE2 runs 26.5, where warm delivery already works — so the fix's success is read from the reporter's next dump, not from this loop.
