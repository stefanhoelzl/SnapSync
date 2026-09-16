## 1. Ledger: `demoteRequested` replaces `clearRequested`

- [x] 1.1 In `LedgerStore` (`:domain` `ports/`), replace `clearRequested()` with `demoteRequested()`; KDoc it as the
      reset-family bulk `REQUESTED → FAILED` callable without a writer (`sync-ledger`, "Requested-state reset")
- [x] 1.2 Replace the `deleteRequested` statement in `Ledger.sq` with `demoteRequested`
      (`UPDATE ledgerRow SET state = 'FAILED' WHERE state = 'REQUESTED'`) and implement it in
      `SqlDelightLedgerStore`, dinging once on success
- [x] 1.3 Implement `demoteRequested()` in `:adapter:generic:fake` `InMemoryLedgerStore` and in the
      `feature/upload` `commonTest` stores (`InMemoryLedgerStore`, `FakeLedgerStore`)
- [x] 1.4 In `:test:world` `LedgerStoreContract`, replace the two `clearRequested` cases with: only `REQUESTED` rows
      become `FAILED` with every other field unchanged (`DISCOVERED`/`COMPLETED`/`FAILED` untouched); one ding;
      a demoted row is returned by `rowsNeedingJob()`

## 2. PhotoKit mechanism: repair in `start()`, `stop()` is the disable

- [x] 2.1 Rename `ClearRequested.kt` / `clearRequestedOffMain` (and `ClearRequestedTest`) to the demote, keeping the
      off-main dispatcher, the bounded retry and the awaited contract; update its KDoc and log lines
- [x] 2.2 `OsDrivenUploadMechanism.start()`: `setEnabled(false)` → awaited off-main `demoteRequested()` →
      `setEnabled(true)`; no discovery-cursor reset
- [x] 2.3 `OsDrivenUploadMechanism.stop()`: `setEnabled(false)` only; delete `deregister()` and drop the
      `DiscoveryStore` dependency if nothing else uses it
- [x] 2.4 `OsDrivenUploadMechanismTest`: assert disable → demote → enable order, demote completes before enable, cursor
      untouched on start and stop, stop changes no ledger row; remove the `deregister()` case
- [x] 2.5 Bind the relinquish to `stop()`: `SnapSyncRoot`'s `relinquishOsRegistration = { photoKitProducer.stop() }`
      (or drop the `AppPorts` field in favour of the mechanism's `stop()` if the table can reach it); update
      `RelinquishThenRun`, `UploadMechanismTable`, `UploadArm` KDoc tables and `CompositionSeamTest` /
      `ProducerExclusivityTest` wording that describes deregistration-only

## 3. Transport seam: `lostKeys()` and `discard()`

- [x] 3.1 Add `lostKeys(): Set<String>?` and `discard(keys: Set<String>)` to `BackgroundTransfer`, KDoc'd as a read
      and an instruction the cycle issues ("The transport reports the transfers it still holds")
- [x] 3.2 `IosPhotoKitUploadPlatform` and the simulator `UploadJobQueue`: `lostKeys() = null`, `discard = Unit`, each
      with its stated reason beside `liveKeys()`
- [x] 3.3 `:test:world` `UploadFakes`: `lostKeys() = null`, `discard = Unit`
- [x] 3.4 `IosUrlSessionUploadPlatform`: `lostKeys()` = staged file names in `upload-staging` minus live task keys
      (`null` when the staging directory or its listing is unavailable); `discard(keys)` deletes those keys' staged
      files; delete `sweepStaging()`; rewrite the `cancelAll` KDoc so it no longer asserts whether a cancelled task
      delivers a completion or names "the stranded pass on the next cycle"
- [x] 3.5 Update the `UploadCycleTest` transport double (and any other `BackgroundTransfer` double) for the two members

## 4. Cycle: two rules, restart flag, discard

- [x] 4.1 `StrandedKeys.kt`: express both rules as pure set functions (per-cycle: `REQUESTED ∩ lost`; restart:
      `REQUESTED − live`) and extend `StrandedKeysTest`
- [x] 4.2 `UploadCycle`: add `signalRestart()`; `reconcileStranded()` consumes the flag once and applies the restart
      rule over `liveKeys()`, otherwise the per-cycle rule over `lostKeys()`; a `null` answer skips that rule and
      leaves the flag pending; then `discard(lostKeys())` after all writes
- [x] 4.3 `UploadCycleTest`: per-cycle rule ignores `REQUESTED` rows not lost; restart demotes every non-live
      `REQUESTED` row exactly once, then the per-cycle rule resumes; a cycle that skips (unreadable / not joined)
      leaves the restart pending; `discard` receives the lost set after the writes; null transports reconcile
      nothing
- [x] 4.4 `UrlSessionUploadController.start()`: replace the `sweepStaging()` call with `cycle.signalRestart()`
      before `pump.onStart()`; update its KDoc and the `stop()` KDoc's `clearRequested` note

## 5. Docs, diagrams, checks

- [x] 5.1 Sweep remaining prose for `clearRequested`, `sweepStaging`, `deregister()` and "deregistration only":
      `SnapSyncRoot` comment, `app/ios/CLAUDE.md` (lifecycle table, "clear the discovery cursor" paragraph),
      `IosUrlSessionUploadPlatform` class KDoc
- [x] 5.2 `./gradlew architectureDiagrams` and commit any regenerated `architecture/` output
- [x] 5.3 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green
- [x] 5.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green after the change validates

## 6. Device check (optional, non-gating)

- [x] 6.1 Via the rig channel with the device lease: pin the app-driven mechanism, start uploads, kill the app
      mid-transfer, relaunch; confirm in `debug.log` one restart-rule batch of `reconcile:` lines and that the staged
      files are gone after that cycle
- [x] 6.2 Optionally record whether a `cancelAll` delivers `NSURLErrorCancelled` completions (the design's open
      question) and note the answer in the design record
