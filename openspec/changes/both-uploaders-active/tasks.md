## 1. Half B — create until refused (own commit)

- [x] 1.1 `UploadCycle.enqueue`: after admission, walk the admitted rows in chunks of `resolveChunk = 4` — resolve the chunk via `library.resourcesFor`, delete unresolvable rows, create each resolved row's job through the engine, and return at the first `LIMIT_EXCEEDED` with `truncated = true`; a pass that exhausts the admitted rows is not truncated
- [x] 1.2 Delete `enqueueBatchSize` and its comment block from `UploadCycle`'s constructor
- [x] 1.3 Delete `BackgroundTransfer.remainingCapacity` and its implementations: `IosUrlSessionUploadPlatform`, `IosPhotoKitUploadPlatform`, `SimulatorUploadJobQueue`, `FakeBackgroundTransfer` (`:test:world`), `UploadCycleTest`'s `FakePlatform`
- [x] 1.4 `UploadCycleTest`: truncation is observed only through `LIMIT_EXCEEDED`; a refusal mid-chunk wastes at most `resolveChunk − 1` resolves; a backlog smaller than the platform's cap drains in one pass; remove the capacity-bound tests
- [x] 1.5 `./gradlew build` green; commit

## 2. The ledger loses its repairs

- [x] 2.1 Delete `strandedEachCycle` / `strandedAtStart` (`StrandedKeys.kt` + `StrandedKeysTest`), `reconcileStranded`, `strandedCandidates`, `signalRestart`, `restartSignalled` from `UploadCycle`; `recreateRetrySpent` keeps retry re-creation only
- [x] 2.2 Delete `BackgroundTransfer.liveKeys` / `lostKeys` / `discard` and every implementation (both iOS adapters, simulator queue, world fake, test fakes)
- [x] 2.3 Delete `LedgerStore.demoteRequested` and `requestedKeys`, `LedgerWriter.requestedKeys`, their `SqlDelightLedgerStore` / `InMemoryLedgerStore` (both copies) / `FakeLedgerStore` implementations, the two `Ledger.sq` queries, and the `LedgerStoreContract` cases for them
- [x] 2.4 Delete `DemoteRequested.kt` + `DemoteRequestedTest`; `OsDrivenRegistration.register()` becomes disable → enable with no ledger port; drop its `LedgerStore` constructor parameter; update `OsDrivenRegistrationTest` (the toggle order, no ledger touch)
- [x] 2.5 Rewrite the KDoc that cites the removed rules (`TransferRecord`, `BackgroundTransfer`, `IosUrlSessionUploadPlatform`, `UrlSessionUploadController`, `LedgerState`) — no comment may describe a repair that no longer exists

## 3. Admission and the registration fact

- [x] 3.1 Replace `resolveUploadMechanism` / `UploadMechanism` with `extensionRegistrable(osSupportsOsDrivenUpload, permission, pin)` in `model/`; delete `UploadMechanism` once no caller remains
- [x] 3.2 `UploadAdmission`: delete `NotResolved`; `appAdmission(permission, pin)` admits under `GRANTED` or `LIMITED` unless the rig pin says `app=off`, else `Withheld`; `extensionAdmission` unchanged
- [x] 3.3 `UploadCycle.settle`: the `NotResolved` branch and outcome go; `CycleOutcome`/`publish` lose the variant; the app's `Withheld` runs the narrow settle (`acknowledgePresented`)
- [x] 3.4 `SnapSyncApp`: `appUploadAdmission()` and the transitions read the new functions; update `CycleGateTest`, `UploadCycleTest`, `AdmissionWorldTest`
- [x] 3.5 `UploadCycleTest`: two sequential cycles over one ledger — the second creates no job for a key the first recorded `REQUESTED`; a completion arriving for a row another cycle already settled is a guarded no-op

## 4. Transitions

- [x] 4.1 `UploadTransitions`: inputs become `joined()`, `permission()`, `extensionRegistrable()`, the optional registration, the app engine; implement design D5's table (join forced where registrable; reconfigure never touches registration; launch/permission change register only on an OS read of `false` under `GRANTED`, never deregister; arm iff access usable; leave deregisters + disarms + cancels)
- [x] 4.2 `Stay` reaches no upload transition: `MembershipEntry` becomes leave → load → save → start uploads (`onJoin`), and `Provision`'s `Stay` branch only saves (one call per branch — the transcriber rejects a local `val`); regenerate the flow's transcription
- [x] 4.3 `AppUploadEngine`: `disarm()` = cancel the heartbeat only; add `cancelTransfers()` (the old `cancelAll`); only `onLeave` calls it; `arm()` no longer signals a restart
- [x] 4.4 `UploadTransitionsTest`: every cell of the table; `Stay` makes no registration call and no engine call; a download-only join registers; no permission change deregisters; only a leave cancels
- [x] 4.5 `MembershipEntry`/`LeaveEvent` wiring unchanged in order; update `ProvisionTest`, `MembershipEntryTest` for the new effect signature

## 5. Completion re-pump

- [x] 5.1 `BackgroundUploadPump.onUploadCompleted` takes the app's current admission (an injected read) and drives a cycle only on `Admit`; `UrlSessionUploadController`'s `onTerminal` stays wiring-only
- [x] 5.2 Pump test: a completion under `Withheld` records nothing new and runs no cycle; under `Admit` it tops up

## 6. Rig, diagnostics, guards

- [x] 6.1 `:test:rig`: `/device/upload-mechanism` → `/device/uploaders?app=on|off&extension=on|off`; the pin feeds `appAdmission` and `extensionRegistrable`, then calls `onOverrideChanged()`; `/os/photokit-ext` drops the "refused unless photokit" gate; update the `rig-channel` skill text
- [x] 6.2 `SnapSyncRoot` diagnostics / `foregroundParams` / `Boot.uploadTier`: report `extensionRegistrable`, the OS-read registration, and the app's admission instead of a resolved tier
- [x] 6.3 `ProducerExclusivityTest`: re-point per design D11 (never registered below 26.1; no registration write under a non-`GRANTED` grant; no deregistration except leave or `extension=off`; no cancel except leave; `Stay` makes no registration call)
- [x] 6.4 `CompositionSeamTest`: update the pinned reasons for the override source and `UploadPorts.admission`
- [x] 6.5 `./gradlew build` (detekt tiers included — no ceiling raised) and `./gradlew compileIosMainKotlinMetadata`; `./gradlew architectureDiagrams` and commit the output

## 7. Docs

- [x] 7.1 `CLAUDE.md`: the stack note ("Two upload tiers, resolved per transition…") and the limited-access paragraph's "resolution yields the app-driven mechanism" become the new admission/registration facts; the `:app:ios` module line loses "which mechanism RUNS is re-resolved"
- [x] 7.2 `UploadCore.kt` KDoc: drop the stale "the extension root never sees a partial grant"

## 8. Device verification (SE2, `rig-channel`, before merge)

- [x] 8.1 iOS ≥26.1, full grant: a new photo ends as one `COMPLETED` row whichever process uploaded it; the object exists once
- [x] 8.2 Re-scan the joined event: registration record and in-flight extension jobs survive (no disable in the log) — on device the link gate absorbs a repeated link before any provision runs; the `Stay` branch itself is pinned by `ProvisionTest`
- [x] 8.3 `GRANTED → LIMITED → GRANTED`: nothing is cancelled, no registration write is attempted under `LIMITED`, every row ends settled
- [x] 8.4 Probe the open question: queue extension jobs under a full grant, downgrade to `.limited`, record whether the OS completes and presents them; write the result into `ios-photokit-upload` "The registration cannot be changed under a partial grant"
- [x] 8.5 Download-only membership: extension stays registered, its launches return `SKIPPED`, no heartbeat is re-armed, manifest is empty

## 9. Spec hygiene at sync/archive (Purpose sections cannot be edited through a delta)

- [ ] 9.1 Rewrite the Purpose sentences this change makes false: `upload-lifecycle` ("exactly one process comes to write it", the demote ritual, "arm or disarm its heartbeat and restart signal", the "Exactly one writer is gated, not structural" paragraph, the transition list missing re-provision); `sync-ledger` ("single per platform", the "Single record-writer is the load-bearing invariant" paragraph); `ios-photokit-upload` ("sole `LedgerWriter`", "Uploads on iOS 18–26.0 are the app-driven tier instead"); `ios-url-session-upload` ("for iOS 18–26.0", "selected per OS version … single ledger record-writer"); `device-manifest` ("the upload extension is its sole writer"); `limited-photo-access` ("never registered from `.limited` and the OS never invokes it there; hence…", the "development mechanism override")
- [ ] 9.2 After sync, grep `openspec/specs/` for citations of the removed/renamed requirement headers ("resolved, never selected", "Exactly one mechanism writes", "never both started", "Stranded reconciliation", "Requested-state reset", "demotes orphaned REQUESTED", "Per-version tier selection", "reports the capacity it will accept", "transfers it still holds", "Extension owns the single ledger writer", "resolves the app-driven mechanism by resolution", "Sole writer, synchronous", "App-driven upload host below", "record-writer below 26.1", "to the resolved mechanism") in requirements this change did not touch, and fix each
- [ ] 9.3 `sync-status`'s three "app-driven tier" mentions (Ledger-backed source, Foreground-gated status-counts poll, Foreground status refresh) and `upload-lifecycle` "Every selection and side-effect port…"'s "contribution" port: reword if false after apply
- [ ] 9.4 Code comments citing removed headers (`SnapSyncApp.kt`, `UploadConfig.kt`, `UploadTransitions.kt`, `UploadCycleTest.kt`, `UploadMechanism.kt`, `UploadMechanismTest.kt`) — updated or deleted with their code
- [ ] 9.5 The three archive gates in `openspec/config.yaml` (placeholder Purpose, delta completeness per touched module, dead types — `demoteRequestedOffMain`, `UploadMechanism`, `StrandedKeys` functions)
