## 1. Gap 2 — move platform constants and translations into their adapters

- [x] 1.1 Move `isConfigFileAbsence` from `model/ConfigFile.kt` into `:adapter:ios:ext-safe` beside
      `FileBackedConfigStore`, its only production caller; keep `ConfigFileRead` in `ports/`
      unchanged (it is already the neutral seam)
- [x] 1.2 Move `ConfigFileTest`'s absence assertions to `iosTest` and strengthen them to reference
      the real Cocoa/POSIX error constants rather than integer literals
- [x] 1.3 Move `BROWSING_WEB_ACTIVITY_TYPE` and the browsing-web filter out of
      `model/UniversalLinkActivity.kt` into `:adapter:ios:app-only`; leave the three-outcome
      `EventLinkDelivery` shape in `model/`, taking an already-normalized input
- [x] 1.4 Move `UniversalLinkActivityTest`'s constant assertion to `iosTest`, asserting against the
      real `NSUserActivityTypeBrowsingWeb` symbol (it currently compares a constant to a copy of
      itself and cannot fail)
- [x] 1.5 Move `resourceRole(Long)` out of `model/UploadKeys.kt` into `PhotoKitCandidateSource`;
      replace `RawResource.type: Long` with `role: ResourceRole?` resolved adapter-side
- [x] 1.6 Delete `RawResource.contentTypeUti`; have `resourcesFrom` read `mimeContentType`, so
      `Resource.contentType` carries the resolved MIME (spec `gallery-status`)
- [x] 1.7 Update `RawAssetMapping`'s KDoc claim that "no PhotoKit value crosses" — it becomes true
      of the whole function once 1.5 and 1.6 land
- [x] 1.8 Verify `./gradlew build` and `compileIosMainKotlinMetadata` are green, and that the
      moved tests run on the simulator in `ios-test`

## 2. Gap 1 — give the five platform-touching seams port types

- [x] 2.1 Replace `AppPorts.now: () -> Long` with the existing `Clock` port; delete the shell's
      inline `NSDate()` lambda and wire `SystemClock`; simplify the two `SnapSyncApp` sites that
      currently rebuild an `Instant` from the lambda's millis
- [x] 2.2 Fold `DeviceAttestation`'s own `now: () -> Long` onto the same `Clock`, so one clock is
      live in the composition rather than three seams for one need
- [x] 2.3 Add a staging-root member to `StagedBytes` and delete
      `AppPorts.downloadStagingRoot: () -> String`
- [x] 2.4 Add a picker-presentation member to `PhotoAccessRequester` (which already presents system
      UI via `openSettings()`); make `PresentLimitedLibraryPicker` part of its implementation and
      delete `AppPorts.presentPhotoPicker`
- [x] 2.5 Declare a need-named leave-notification port; make `HttpLeaveNotifier` implement it and
      delete `AppPorts.notifyLeave`
- [x] 2.6 Declare a need-named share-presentation port; make `IosShareSheet` implement it and delete
      `AppPorts.share`
- [x] 2.7 Update `:test:world` and `:adapter:generic:fake` for the new/extended ports, keeping fake
      honesty (port contract plus an initial-state constructor; rigging stays in `:test:world`)
- [x] 2.8 Verify the desktop harnesses and `:test:integration` still compose — they call the same
      `snapSyncApp`/`uploadCore`, so a missed seam surfaces there first

## 3. Arm the gates

- [x] 3.1 Add the composition seam gate to `:test:architecture`: pin the function-typed field
      inventory of `AppPorts` and `UploadPorts`, exact in both directions, each entry carrying its
      reason; fail vacuously if zero fields are scanned
- [x] 3.2 Add the platform-identifier gate: scan `model/`, `ports/` and `feature/` with comments
      stripped, over a pinned baseline split into `accepted` (`CompositionMode`'s tier members) and
      `deferred` debt carrying expiry triggers (the `Keychain` family, `OsReceipt`'s
      `URL_SESSION_EVENTS`) — five sites, not zero (design D2)
- [x] 3.3 Write each gate's blind spot into its own KDoc, matching `MainLaneContainmentTest`'s
      convention — the identifier gate cannot see a bare-integer ABI decoder; the seam gate says
      nothing about what the OS hands the shell
- [x] 3.4 Confirm both gates fail when deliberately violated (add a token, add a function-typed
      field, leave a pin behind) and pass over the pinned baseline afterwards

## 4. Record and close

- [ ] 4.1 Update `openspec/specs/module-architecture`, `architecture-guards`, `gallery-status`,
      `leave-event` and `ios-app-shell` from the delta specs (the last two were added late: the
      leave requirement described the notify as a lambda "backed by `HttpLeaveNotifier`", and the
      shell requirement enumerated `presentShareSheet` among the root's platform effect lambdas —
      both untrue once the seams became ports)
- [ ] 4.2 Update the laws digest in the root `CLAUDE.md` so the in-context copy matches the amended
      port law (a `:test:architecture` guard keeps the two in sync)
- [ ] 4.3 Remove the four dangling KDoc links from `:domain` to adapter classes that do not exist or
      cannot be seen from `commonMain` (`IosBackgroundTransfer`, `IosDiscoveryStore`,
      `KtorPushHttpClient`, `RawAssetSource`), and the stale `[since]` reference in
      `BackgroundTransfer`
- [ ] 4.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `./gradlew build`
- [ ] 4.5 Open the PR with the `internal` changelog label — no customer-visible behavior changes
