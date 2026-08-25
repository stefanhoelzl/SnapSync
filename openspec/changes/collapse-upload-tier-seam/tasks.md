## 1. Preconditions

- [x] 1.1 Confirm the predecessor merged — done: `LaunchDirectives` and the tier-force flag deleted, forge on its own Xcode target, `CompositionMode` **deleted outright** (`resolveComposition(backgroundUploadSupported) -> UploadTier`), `isSimulator`/`useBackgroundSession` gone from the tree, and the registration write's `Boolean`/`NSError` landed behind a tested `registrationOutcome` classifier
- [x] 1.2 Rebuild every MODIFIED delta block from the **current** spec text — done: three requirements had drifted (`architecture-guards`' platform-identifier gate now reads baseline 1 / zero deferred / `BACKGROUND_EVENTS`, `module-architecture`'s target-fixed-fact clause and scenario, and `upload-lifecycle`'s state-based restatement of the tier-force requirement), all preserved rather than reverted
- [x] 1.3 Amend that argument — `resolveComposition` is absorbed, and `resolveUploadMechanism`'s KDoc now states what a reader should see instead: the override is **always `null` in a production build**, so the mechanism a shipped process runs is still a function of the device it runs on
- [x] 1.4 Read `PROBE-FINDINGS.md` from the `probe-uploadjob-readback` workspace and record the `isUploadJobExtensionEnabled()` verdict in `design.md`'s Open Questions (it gates no behaviour here — D6 — but it decides how the rig's `/device/state` labels its field; forward the verdict to the rig session)
- [x] 1.5 Resolve the push receiver's `GRANTED`-exactly guard — **required by name**: `limited-photo-access` enumerates "the upload half of the silent-push fan-out" among the three triggers that must skip `PHAsset` work under a partial grant, and fixes reads at two moments a push is not one of. Preserve exactly; the mechanism's `onSilentPush` declines under `LIMITED`

## 2. The resolver

- [x] 2.1 Add the mechanism-kind type and the pure resolver — both in `:domain model/` (deviation from the task's `feature/upload`: `model/` is where `resolveComposition`/`UploadTier` live and where `PlatformIdentifierTest` pins their members, so absorbing means replacing them in place)
- [x] 2.2 Write the exhaustive cell test in `commonTest` (runs on JVM and `iosSimulatorArm64`), asserting no cell yields the OS-driven kind on an OS that lacks it
- [x] 2.3 Absorb `resolveComposition` and delete `UploadTier` (`CompositionMode` is already gone; `isSimulator`/`useBackgroundSession` no longer exist, so nothing needs rehoming)

## 2b. The resolution override

- [x] 2b.1 **Measured on device (SE2, iOS 26.6, 2026-08-25): the App-Group container SURVIVES an app update.** That measurement is what ruled OUT the persistent design — a plant could be handed to a build that never established it — and drove the override to a settable thunk the shipped binary cannot write (design D10)
- [x] 2b.2 Implemented — the override reaches the resolver through `SnapSyncRoot.uploadMechanismOverrideSource`, a thunk defaulting to `{ null }` that only the control channel's boot hook replaces. No file, no codec, no staleness rule, no residual: a shipped binary contains no writer
- [ ] 2b.3a While the device is leased with an override-carrying build: pin **PHOTOKIT under a LIMITED grant** and report what `setUploadJobExtensionEnabled(true)` returns — the raw `Boolean`, and the `NSError` domain/code if non-nil — to `limited-grant-registration-noise`. `ios-photokit-upload` asserts registration "succeeds and lies" there, but that claim rests on `start()`'s *unconditional* log line with the return discarded, so "succeeds" was never observed. If the enable is refused like the disable is, the extension was simply never registered — which explains the measured zero invocations equally well. The override is the only way to attempt it
- [~] 2b.3 **Deleted with the durability requirement.** It existed to verify that an override survives an OS-initiated cold relaunch; the override is deliberately non-durable, so there is nothing to verify. Restoring durability is two lines inside the channel and would restore this task with it

- [~] 2b.4 **MOOT** — the shared staleness predicate is deleted along with the persistent design, and `add-simulator-rig-host` deleted its plant too (identity now swaps the `SecureStore` binding by compilation target). No second consumer, no rule, nothing to adopt

- [x] 2b.5 Own the channel verb end to end (ruled by `add-simulator-rig-host`'s user: #4 does not take it). `UploadMechanismPin` + `POST /device/upload-mechanism?value=…` in `:test:rig`, and ONE bare assignment in the boot hook. The verb reports the pin **and** what the app resolves with it, because a pin naming a mechanism this OS cannot run is clamped and a pin is ignored without usable access — so the two can disagree and only one is what the app will do. Verified: a build without `-Psnapsync.rig=true` compiles and names the pin nowhere

## 3. The producer seam

- [x] 3.1 Split the trigger surface into its own interface beside `UploadProducer`, keeping the lifecycle seam at exactly `start()`/`stop()`; declare `onForeground`/`onSilentPush`/`onBackgroundTask`/`onSelectionChanged` with **no defaults**, each a `suspend fun` taking no completion handler
- [x] 3.2 Add the `Idle` producer: declines every trigger, no-ops both lifecycle verbs
- [x] 3.3 Add `RelinquishThenRun`, used for **both** cells (design D5b — implementation found the app-driven mechanism's OS-held footprint needs the same treatment): deregistration-only one way, ordinary `stop()` the other
- [x] 3.4 Have `PhotoKitUploadProducer` and `UrlSessionUploadController` each state an explicit answer for all four triggers, with its reason at the definition site
- [x] 3.5 Scope `PhotoKitUploadProducer`'s existing `clearRequested()` + cursor reset to the disable→enable **re-register** only, leaving the leave path unchanged

## 4. The arm

- [x] 4.1 Delete `ComposedProducers` and `UploadArm.selectedProducer()`; give `UploadArm` one producer reference plus the resolver and factory
- [x] 4.2 Implement re-resolution on each transition with stop-then-start when the kind changes, and no swap when it does not
- [x] 4.3 Update `UploadArmTest` for the transition table over the new shape

## 5. Composition and shell

- [x] 5.1 Build the kind→producer factory in `:domain compose/`, caching the app-driven instance (its background `URLSession` is a process-lifetime singleton whose invalidation is terminal)
- [x] 5.2 Remove the tier switch and **all six** `LiveShell` thunks — `LiveShell` now takes none. `detektAppShell` passes with **no new** pin: the one residual `if` is a property initializer choosing whether to *construct* the OS-driven mechanism at all, which must stay (its selector does not exist below 26.1)
- [x] 5.3 Hoist `OsReceipt` construction to each upload-driving entry point, reusing the existing `ReceiptDeadlines` constants unchanged (`SILENT_PUSH`, `BACKGROUND_EVENTS`, `BACKGROUND_TASK` — note `URL_SESSION_EVENTS` was renamed and no guard pins their values any more) — move the named constants, never the literals
- [x] 5.4 Relocate the read-discipline gate from the silent-push fan-out into the mechanism, preserving current behaviour exactly (per 1.4)
- [x] 5.5 Update `CompositionSeamTest`'s pinned `AppPorts` field names

## 6. Guards

- [x] 6.1 Retarget `ProducerExclusivityTest`: assert the held producer always matches the resolved kind, every kind change is stop-then-start, and no transition leaves a producer started
- [x] 6.2 Move the platform-identifier gate's one **accepted** pin from `CompositionMode`'s tier members to the mechanism kind's members in `PlatformIdentifierTest`
- [x] 6.3 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` (the Linux-runnable iOS proxy); run `./gradlew architectureDiagrams` and commit — stale `architecture/` blocks the PR

## 7. Verification

- [x] 7.1 Verified on the SE2 (iOS 26.6) — via the **`GRANTED`→`LIMITED` cell** rather than an override, which is the same resolution cell and needs no endpoint. The log shows the hand-over in both directions (`url-session.stop` before `photokit.start`; `photokit.deregister` — the NARROW verb, no repair — before `url-session.start`), and `osExtension` moved `false`→`true` on registration
- [x] 7.2 Confirmed, and it **found a platform constraint** (design D11): deregistration under `.limited` is refused with `PHPhotosErrorAccessUserDenied` (3311), so the record survives — proven by the write's return on the next full grant, since the read is grant-dependent. No double-write results, because the OS does not invoke the extension under a partial grant. Ledger stayed 0/0 throughout
- [~] 7.3 **Partially verified.** Under `.limited` the app-driven mechanism started and drove a full cycle to `COMPLETED` (`url-session.start` → `pump.onStart` → `runCycle`), so the path is not regressed. The *in-flight-rows-survive* half was **not** exercised: the verification event used an empty capture window on purpose (nothing written to the shared zone), so the ledger was 0/0 and there were no `REQUESTED` rows to survive. Needs a run with real pending work
- [x] 7.4 `iosSimulatorArm64Test` on macOS 26.5.2 / Xcode 26.6: 1206 tests, 133 suites, 0 failures

## 8. Close out

- [ ] 8.1 Apply exactly one changelog label — `internal` unless 1.3/1.4 surfaced a user-facing behaviour change — and ship via `/ship`
- [ ] 8.2 Tell `triggers-into-channel` and `rig-simulator-host` that the resolve override seam has landed — the first to close the full-grant walk-path window, the second because its change is blocked on it
