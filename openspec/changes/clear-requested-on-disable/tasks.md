# Tasks — clear REQUESTED on disable

## 1. `clearRequested()` ledger op (`sync-ledger`)

- [x] 1.1 Add `clearRequested()` to the `LedgerBackend` interface, beside `clear()`/`resetTo()` (the
      app-side reset family — NOT a writer-only prune). KDoc: deletes all `REQUESTED` rows, keeps
      `COMPLETED`/`FAILED`, emits one `changes` signal.
- [x] 1.2 SQLDelight backend: a `DELETE FROM ... WHERE state = 'REQUESTED'` query; implement
      `clearRequested()` to run it and ding `changes` once (like `clear`).
- [x] 1.3 In-memory fake backend(s) used in tests: implement `clearRequested()` (drop REQUESTED rows,
      ding changes).

## 2. Contract tests (`sync-ledger`)

- [x] 2.1 In `LedgerBackendContract`: `clearRequested` removes `REQUESTED` rows, leaves `COMPLETED`
      and `FAILED` untouched, and emits exactly one `changes` signal. Runs on JVM **and**
      `iosSimulatorArm64` via the shared contract.

## 3. App wiring (`ios-background-upload`, `:app:ios` — wiring only)

- [x] 3.1 Hoist a shared `LedgerBackend` in `SnapSyncRoot` (the in-flight read already opens one);
      reuse it.
- [x] 3.2 Add a `disableExtension()` helper: `setUploadExtensionEnabled(false)` then
      `ledger.clearRequested()`. Route **both** disable sites through it — the `disable→enable`
      re-register (`enableBackgroundUpload`) and `LeaveEvent`'s `disableExtension` lambda.
- [x] 3.3 Confirm no `LedgerWriter` is constructed in `:app:ios` (clearRequested is on `LedgerBackend`).

## 4. Verify

- [x] 4.1 `./gradlew build` green (JVM + Compose tests; `compileIosMainKotlinMetadata`) and the
      native contract test compiles.
- [ ] 4.2 On device: reproduce the stuck state (disable mid-upload → `N pending · discovered 0`),
      then confirm that with the fix a disable clears `REQUESTED` and the next cycle re-creates the
      not-yet-stored jobs (bytes resume landing in `/files/device/<id>`), with no permanent orphan.
