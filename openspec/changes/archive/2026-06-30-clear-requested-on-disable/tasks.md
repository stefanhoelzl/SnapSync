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
- [x] 3.2 Add a `disableExtension()` helper: `setUploadExtensionEnabled(false)`, then reset the
      discovery cursor (clear the App-Group change-token, forcing a full re-enumeration), then
      `ledger.clearRequested()`. Both clears are required — `clearRequested` only makes keys absent; a
      settled cursor would never re-surface them. Route **both** disable sites through it (the
      `disable→enable` re-register and `LeaveEvent`'s `disableExtension` lambda).
- [x] 3.3 Confirm no `LedgerWriter` is constructed in `:app:ios` (clearRequested is on `LedgerBackend`).

## 4. Verify

- [x] 4.1 `./gradlew build` green (JVM + Compose tests; `compileIosMainKotlinMetadata`) and the
      native contract test compiles.
- [x] 4.2 On device: with the discovery cursor settled (prior cycle `discovered 0`), a cold launch of
      the fixed build reset the cursor so the next cycle did a FULL re-enumeration (`discovered 14`, vs
      the old build's incremental `discovered 0`) — proving cursor-reset-on-disable. The cross-process
      `clearRequested()` write completed without crashing (cycle `COMPLETED`), and the already-stored
      `COMPLETED` rows survived (`0 pending`, no duplicate upload — dedup preserved). The clear→re-create
      of an orphaned `REQUESTED` is covered by `LedgerBackendContract` and follows from these proven parts
      (the device had drained to `COMPLETED`, so no live orphan remained to reproduce).
