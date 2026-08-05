## 1. Observability first

Ship the instrumentation before the mechanism, so the constants in group 4 can be checked against a
real dump rather than trusted. Three estimates in the investigation were an order of magnitude wrong
(design.md, *Risks*).

- [x] 1.1 `FileLogWriter`: emit millisecond-resolution timestamps; keep the single `O_APPEND` write and the ambient prefix unchanged
- [x] 1.2 `IosPhotoLibraryImporter`: log change-block entry (with the placeholder id) and the completion callback's success/error

A wall-clock-versus-monotonic suspension detector was specified here and **dropped during
implementation** — measured, Kotlin/Native's monotonic clock tracks the wall clock through suspension
(−10 ms over the 89 s freeze, ~−850 ms over the 104-minute one), so the delta reads zero exactly when it
is needed. See design.md D12; the `diagnostic-logging` delta no longer carries that requirement.

## 2. Bound the network

Independent of the receipt work and the cheapest standalone win: it converts multi-minute suspension
artifacts into fast honest failures.

- [x] 2.1 `darwinHttpClient`: install `HttpTimeout` with an explicit request timeout (design.md D8; measured basis: no fetch that ever answered exceeded 1.7 s)
- [x] 2.2 Confirm `reconcile`'s union-failure path still keeps last-good state on the new timeout exception type
- [x] 2.3 `commonTest`: a timing-out union fetch leaves download rows intact and the wake's remaining work proceeds

## 3. Flows can no longer detach

- [x] 3.1 `SilentPush`, `DownloadBackstop`, `Foreground`, `Provision`: drop the `CoroutineScope` parameter; make `run()` suspend; replace `scope.launch { X }` with `coroutineScope { launch { X } }` so fan-out is preserved and awaited
- [x] 3.2 Change `reloadConfig` and `refreshAttestation` to `suspend` in every flow signature
- [x] 3.3 `compose/`: build the now-suspend effect lambdas; `SnapSyncRoot.refreshAttestation` stops launching (this also removes the stale-token race where the union fetch overtakes its own refresh)
- [x] 3.4 `SnapSyncRoot`: the four entry points now await their flows
- [x] 3.5 `commonTest`: each flow's `run()` returns only after its children complete, and its fan-out still runs concurrently
- [x] 3.6 **Teach the flow transcriber the new form.** `tools/diagrams/Flows.kt`'s closed grammar
      sanctions `scope.launch { … }` as *the* concurrent fan-out form and knows nothing about
      `coroutineScope { launch { … } }`, so `architectureDiagrams` fails generation — discovered
      while running it, not planned. Renders as an awaited fan-out, not an async arrow.
- [x] 3.7 Delta for `architecture-diagrams`: the grammar clause names the awaited fan-out and no
      longer sanctions an escaping `scope.launch` in a flow

## 4. The receipt type

- [x] 4.1 `:domain` `model/`: the receipt type over an opaque handler, whose only release path takes the work as a `suspend` block and applies a caller-supplied deadline (design.md D4)
- [x] 4.2 On deadline expiry: release the handler, log the expiry, leave the work running — never cancel it
- [x] 4.3 `commonTest` (JVM + `iosSimulatorArm64`): released after the work; released on deadline with the work still running; released exactly once; released on a throw
- [x] 4.4 Per-entry-point deadline constants, with a note that they are provisional pending the first field dump

## 5. Wire the four receipts

- [x] 5.1 `SnapSyncRoot.onSilentPush`: construct the receipt from the OS handler and hold it across the fan-out
- [x] 5.2 `runDownloadBackstop` and `runUploadHeartbeat`: same, over the flow / pump call
- [x] 5.3 `UrlSessionUploadController`: hold the receipt across `pump.onSessionEvents()` instead of invoking `backgroundEventsCompletion` before it
- [x] 5.4 `QueuedPhotoDownloadJobs`: make `onStaged` suspend, own the launch, track outstanding imports, and join them in `onBackgroundEventsFinished` before releasing (design.md D5)
- [x] 5.5 `compose/`: `SnapSyncApp.kt:328` becomes a plain call — no `scope.launch` in an adapter callback
- [x] 5.6 `iosApp/iOSApp.swift`: register an `expirationHandler` for `app.snapsync.download.backstop`, routed into Kotlin as the receipt's OS expiry signal
- [x] 5.7 Verify Swift still forwards an opaque handler and decides nothing (`SwiftShellGuardTest`)

## 6. Bound the import

- [x] 6.1 `IosPhotoLibraryImporter`: `withTimeout` around the `suspendCancellableCoroutine` awaiting `performChanges` (design.md D6 carries the forcing proof that this frees a continuation, not a thread)
- [x] 6.2 `DownloadController.importReadyLocked`: on a timeout, log it and stop the drain for this wake rather than continuing to the next asset (D7)
- [x] 6.3 Confirm the abandoned asset stays not-imported so the durable retry path picks it up
- [x] 6.4 `commonTest` over the fake importer: a never-completing import releases the mutex, stops the drain, and is retried at the next wake
- [x] 6.5 Verify a late completion resuming a cancelled continuation is a clean no-op — `LateResumeAfterTimeoutTest`,
      green on JVM. It is a `commonTest`, so the Kotlin/Native answer arrives from CI's macOS
      `iosSimulatorArm64Test`; the simulator task is a no-op on Linux and proves nothing here.

## 7. Guards

- [x] 7.1 `:test:architecture` zone gate: no `CoroutineScope` declared in `flow/`
- [x] 7.2 `:test:architecture` zone gate: no non-suspend effect lambda in a `flow/` constructor signature
- [x] 7.3 Both gates fail closed on novelty and name the offending file
- [x] 7.4 CLAUDE.md's laws digest carries the new law — done **with** the `module-architecture` sync, not
      before it. `LawsDigestTest` compares the digest against the MAIN spec, so the two are one edit:
      the requirement was inserted into `openspec/specs/module-architecture/spec.md` (after *Rules in
      features, order in flows*) and the matching one-liner into the digest at the same position. The
      guard's own failure text prescribes exactly this: "Fix BOTH sides in one commit."
      Negative-checked: renaming one side reddens the build.
      ⚠️ At archive time the `module-architecture` delta is therefore ALREADY applied — the sync step must
      treat it as a no-op rather than inserting the requirement twice.

## 8. Integration

- [x] 8.1 `:test:integration` over `:test:world`: a simulated background wake delivering several staged resources does not release its receipt until every import is durably recorded
- [x] 8.2 A hung import releases the receipt on deadline and leaves the asset importable — composed from
      the REAL `OsReceipt` + `DownloadController` + a hanging importer in `DownloadControllerTest`,
      rather than `:test:integration`: the receipt's shell wiring lives in `:app:ios`, which is
      untested by rule and is verified on device in 9.3/9.4 instead.
- [x] 8.3 `./gradlew build` green; `./gradlew compileIosMainKotlinMetadata` green
- [x] 8.4 `./gradlew architectureDiagrams` and commit the result (the `diagrams` check is required)

## 9. Device verification

Background wakes are OS-scheduled and mostly cannot be forced; each item below is the only route to its
receipt (design.md, *Migration Plan*).

- [x] 9.1 Build and sideload a dev IPA via the ssh-mac loop
- [x] 9.2 Silent push — driven WITHOUT backend credentials: the device's own upload fires
      `upload-completion-notify`, and `api/src/app.ts:1089` notifies **all members, no exclusion**, so the
      uploader pushes to itself. Result on device (app-driven tier, production backend):
      `← onSilentPush (1150ms)` covering the reconcile (1134 ms, union GET included) + the upload pump,
      against 6/8/12/23 ms for every pre-change push on the same device.
- [x] 9.3 `BGTask` backstop — **no lldb needed**: with the device charging and idle the OS fired a real
      `BGProcessingTask` at 22:03:19. The drain is now INSIDE the task's span
      (`→ runDownloadBackstop` → `→ importReady` → `← importReady (5ms)` → `← runDownloadBackstop (56ms)`).
      Decisive comparison: across **163** backstop spans in the pre-change baseline, `importReady` sat
      inside **0** of them — every wake completed its OS task before the drain had started.
      The new `expirationHandler` did not fire (nothing ran long enough) and stays unverified.

- [x] 9.4 Killed-app background download — driven WITHOUT a second device: the local rig served two
      genuine FOREIGN assets (device `AAAAAAAA-…-FFFFFFFFFFFF`). A 29 MB one was enqueued, the app was
      SIGKILLed mid-transfer (22:33:29), and **the OS relaunched it** (22:33:34.971) and delivered
      `handleBackgroundUrlSession(identifier=app.snapsync.download.bg)`; the staged import then ran and
      was durably recorded in that same wake (`imported foreign asset …L0_002`, `← onResourceStaged (668ms)`).
      The `← handleBackgroundUrlSession (9ms)` line is the ADOPT call and is correct — by design it stores
      the handler and realizes the session; the release happens later in `onBackgroundEventsFinished`
      after `awaitOutstandingImports()`.
      ⚠️ **Ordering caveat:** the release itself emits no log line on the success path (OsReceipt logs only
      on a deadline), so the device proves the wake and the import, while the *release-after-import*
      ordering rests on the negative-checked unit test
      `the_os_handler_is_released_only_after_the_imports_its_events_caused`. A release log line would close
      this observability gap.


## 9b. Archive-gate findings

- [x] 9b.1 Gate 1 (placeholder Purpose): clean across the whole `openspec/specs/` tree.
- [x] 9b.2 Gate 3 (dead types): the diff removes no type declaration.
- [x] 9b.3 Gate 2 (delta completeness): every touched module accounted for. It caught `test/world`,
      which had no capability — `harness-world-model` now carries a delta. `adapter/generic/fake` is
      test-only (fakes + tests, no contract change) and needs none.
- [x] 9b.4 Each MODIFIED delta rebuilt from the current main spec and diffed. The first attempt at the
      `harness-world-model` delta silently dropped **6 scenarios** from one requirement and **1** from the
      other — exactly the failure CLAUDE.md warns about. Rebuilt verbatim; the diff now removes only the
      two prose paragraphs intended.

## 10. Ship

- [ ] 10.1 `openspec validate hold-os-receipts-until-work-completes --strict` green
- [ ] 10.2 Note in the PR that many `debug.log` durations become honest and larger — not a regression
- [ ] 10.3 `/ship` with the `bug` changelog label
