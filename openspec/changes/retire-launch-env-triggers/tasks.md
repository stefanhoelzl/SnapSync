## 1. The channel's namespaces

- [x] 1.1 Re-carve `RigServer`'s routing: `/os/{entry}` replaces `/trigger/{name}`, `/device/state` and
      `/device/logs` replace `/state` and `/logs`, and `GET /triggers` is deleted with no successor
- [x] 1.2 Reword the unknown-member 404 to point at the `rig-channel` skill instead of the deleted inventory;
      the excluded-member 404 keeps returning its stated reason verbatim
- [x] 1.3 Add `/user/{command}` over `StatusContainerHost`'s public command members, invoked on the main lane
      like `/os`, with `leave`, `create`, `confirmJoin` and `cancelJoin` wired
- [x] 1.4 Add `RigCommand` as a distinct shape from `RigTrigger`: blocking, returning a result, and NOT run on
      `hooks.mainLane` (the gallery commands are blocking `performChangesAndWait` calls)
- [x] 1.5 Split `/health` down to liveness only — `rig=up`, `port`, `bootedAt` — moving `compositionMode`,
      `uploadTier` and `uploadBase` into `/device/state`
- [x] 1.6 Extend `RigControlChannelTest`: keep the `@PlatformEntry` derivation for `/os`, add a second derived
      population from `StatusContainerHost` for `/user`, and require a stated reason for every exclusion in both

## 2. Deleting what the channel already reaches

- [x] 2.1 Delete `SNAPSYNC_EXPORT_LOGS`, `exportExtensionLogToDocuments` and its boot-time call site
- [x] 2.2 Delete `SNAPSYNC_EVENT_LINK`, and rewrite `onLaunchActivity`'s exclusion note, which currently
      claims relaunching with that variable substitutes for cold universal-link delivery — it never did
- [x] 2.3 Delete `SNAPSYNC_LEAVE`; `/user/leave` reaches `host().onLeaveEvent()`
- [x] 2.4 Delete `SNAPSYNC_CREATE_EVENT`, `HeadlessCreate`, `CreateEventPayload`, `decodeCreateDirective`,
      `CreateDirective.kt` and their tests
- [x] 2.5 Delete `LaunchEnvMembership` and its tests — with reset and create as separate requests and leave and
      link as `/user` commands, it has no caller
- [x] 2.6 Delete `applyLaunchEnvMembership`, `applyLaunchEnvPhotoLibrary`, `launchEnvMembershipApplied`,
      `launchEnvPhotoLibraryApplied`, `photoLibraryTriggersDone`, and both `LaunchedEffect` call sites in
      `MainViewController`
- [x] 2.7 Remove the two now-dangling exclusions from the rig hook's `excludedTriggers` map

## 3. `/device` commands

- [x] 3.1 Move `DevPhotoSeeder` and `DevGalleryWiper` into `:test:rig`'s iOS source — **not** into
      `test/rig/src/hook`, which is scanned shell source and would preserve all six suppressions
- [x] 3.2 Rewrite `:test:rig`'s build-file note: the "holds no projection it could get wrong" condition is now
      false, and the file must state the posture it actually has rather than keep a claim that has lapsed
- [x] 3.3 Add `POST /device/gallery/seed?n=&kind=bulk|policy`, merging the two seed variables into one command
      with a parameter
- [x] 3.4 Add `POST /device/gallery/wipe?scope=all|assets|albums`, blocking until `performChangesAndWait`
      returns and answering with matched counts, `committed`, and `PHPhotosError.userCancelled` (3072) when the
      operator cancels
- [x] 3.5 Delete `WipeGallery.kt` and `WipeRequest`; validate `?scope=` inline and return `400` on an
      unrecognized value
- [x] 3.6 Add `POST /device/reset` over the existing `ResetDeviceState`
- [x] 3.7 Add `GET /device/gallery[?cutoff=][&resources=]` — raw subtype census with no cutoff; with a cutoff,
      the policy verdict per asset, every asset, unbounded; with `resources=true`, each asset's resources and
      the elapsed cost it paid
- [x] 3.8 Delete `runLaunchEnvPolicyProbe`, `SNAPSYNC_POLICY_PROBE`, and the pinned suppression that carried it

## 4. Device identity

- [x] 4.1 Make `PushRegistration` take the device id as a supplier, like the three call sites that already do
- [x] 4.2 Add the read-only App-Group fallback at the identity supplier, above `KeychainDeviceIdentity` — not
      as an absence for it to fill, since `errSecMissingEntitlement` is a read error and that class must keep
      never minting on one
- [x] 4.3 Add `POST /device/identity`, writing the fallback source durably so a value survives an OS-initiated
      cold relaunch
- [x] 4.4 Pin with a test that a failed identity resolution is retried on next access rather than memoized
- [x] 4.5 Verify the locked-device deferral is unchanged, and that a mis-signed build still fails loudly

## 5. Extension-registration reporting

- [x] 5.1 Route both `setUploadJobExtensionEnabled` call sites through one helper capturing the `Boolean` and
      the `NSError**`, reusing `PhotoKitJobMapping`'s existing `PHPhotosError` vocabulary
- [x] 5.2 Log a failing enable at `Error` severity so `crash-reporting` carries it; log `PHPhotosError` 3201 on
      the leading **disable** at debug, since that is the expected result on any clean device
- [x] 5.3 Add the three-valued `isUploadJobExtensionEnabled()` field to `/device/state`, reached only through
      the ≥26.1 tier, reporting not-applicable below it, and qualified by the photo grant

## 6. Forge containment

- [x] 6.1 Add the `:app:ios:forge` module with its own entry point and exported framework, depending on
      `:ui:screens` / `:ui:components` / `:ui:presentation` / `:domain` and **not** `:app:ios`
- [x] 6.2 Move `ForgeStatusHost.kt` behind `-Psnapsync.forge=true` so the preset table leaves production
      `:ui:presentation`
- [x] 6.3 Read the state selector in the forge module's own source — the `SNAPSYNC_RIG_PORT` precedent, inert
      because the file does not exist in a production build
- [ ] 6.4 Add the `SnapSyncForge` Xcode target: its own `Info.plist` with
      `CADisableMinimumFrameDurationOnPhone = true` (Compose MP hard-aborts at launch without it), its own
      `embedAndSignAppleFrameworkForXcode` phase, and a Swift shell that calls only its entry point
- [ ] 6.5 Verify the new target does not change what `-scheme iosApp` produces for the simulator SDK
- [x] 6.6 Delete `CompositionMode.Forge`, `ForgeShell`, `isForgeState`'s shell call site and the outer
      `when (mode)`; `resolveComposition` reduces to a function of `backgroundUploadSupported()`
- [x] 6.7 Delete `LaunchDirectives` and its tests; update `CompositionModeTest` for the reduced resolver

## 7. Retiring the tier-force flag

- [x] 7.1 Delete `SNAPSYNC_FORCE_URLSESSION_UPLOAD`. NOTE: as originally written this task said to delete the
      `URL_SESSION` arm with it — that was wrong. That arm is the real tier for iOS 18–26.0 and is also the
      producer a partial grant selects; only the flag's ability to *force* it goes
- [x] 7.2 Confirm `foregroundParams()`'s log line no longer names a directive that does not exist

## 8. Guards

- [x] 8.1 Replace `RunbookSkillsTest`'s trigger-index half with the zero-literal assertion — an exact empty
      inventory over the production main source sets, excluding the gated trees, failing on an empty scan
- [x] 8.2 Keep the runbook **pointer** half untouched; only the index half is replaced
- [x] 8.3 Update `KotlinShellGuardTest`'s scanned roots for `app/ios/forge/src` and shrink the pin table to two
      entries — `SnapSyncRoot` ×1 (background-`URLSession` routing) and `MainViewController` ×1 (`SceneMode`)
- [x] 8.4 Check `RigControlChannelTest`'s loopback walk over `test/rig/src` still holds now that the tree
      carries PhotoKit code
- [x] 8.5 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata`

## 9. Specs and runbooks

- [ ] 9.1 Fix the stale cross-reference at `diagnostic-logging` line 216, which points `SNAPSYNC_RESET_STATE`
      at `ios-app-shell`; it is now `device-state-reset`
- [ ] 9.2 Re-cut `ios-device`: it keeps lease, install, launch, screenshot, restart, `apps pull`, link
      verification and the per-build loop, and loses the entire launch-trigger index
- [ ] 9.3 Re-cut `rig-channel`: the three namespaces, every command, the gallery read, and the
      reset-before-leave note that is no longer enforced by an ordering
- [ ] 9.4 Update CLAUDE.md's runbook pointers and the two traps if the wording no longer matches

## 10. Verification on device

- [ ] 10.1 Take the device lease, build with `-Psnapsync.rig=true`, and exercise `/os`, `/user` and `/device`
      end to end — join, create, leave, reset, seed, gallery read
- [ ] 10.2 Exercise the wipe deliberately, confirming it blocks, raises two confirmations for `all`, and
      reports a tapped cancel as 3072
- [ ] 10.3 Confirm a build **without** `-Psnapsync.rig=true` is undriveable and contains no rig or forge source

## 11. Screenshots — its own gate, not a step that rides along

- [ ] 11.1 Point `screenshots.yml` at the `SnapSyncForge` target, keeping its three-launch, six-capture shape
- [ ] 11.2 Dispatch the workflow and download the raws
- [ ] 11.3 **Look at all six.** A system notification has landed in a capture before (1 of 2 runs); re-dispatch
      if one has. Only `create` should re-diff on an unchanged UI, in the 90×32 px wall-clock region
- [ ] 11.4 Commit the raws only after that review, and only then open the PR

## 12. Landing

- [ ] 12.1 Open the PR with the `internal` changelog label
- [ ] 12.2 Tell `os-producer-deregistration` it can proceed, and that `forced` must survive an OS-initiated
      cold relaunch or `rig-simulator-host` stays blocked after it lands
- [ ] 12.3 Tell `rig-simulator-host` that `POST /device/identity` exists, so it can drop its own rig-side plant
      if it wants to
