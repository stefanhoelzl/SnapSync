# app/ios: the iOS composition roots

`:app:ios` (framework `SnapSyncKit`, the app process) and `:app:ios:extension` (framework `SnapSyncUploadKit`, the
upload extension). **Wiring only and untested**: all testable logic lives in tested modules, and nothing testable
is parked here. Structure and rationale, including why there are two frameworks and how both uploaders coexist:
`docs/architecture.md` §11. The Xcode host's rules are in `iosApp/AGENTS.md`, the adapters' in
`adapter/ios/AGENTS.md`.

None of this builds on Linux. `./gradlew compileIosMainKotlinMetadata` compiles `iosMain`/`commonMain` (+ cinterop)
for both modules and catches iOS-only Kotlin breakage without a Mac.

## Rules for editing the roots

- **The roots pass facts, never decisions.** Neither root is the direction gate: whether a membership uploads at all
  is decided inside `UploadCycle` from its required `Contribution`. The upload lifecycle (join, re-provision,
  reconfigure, permission change, launch, leave) is decided by the tested `UploadTransitions`. Do not add an
  `if (…)` branch here. The last time the lifecycle lived in this module, joining an event silently tore the upload
  arm down and started nothing, because nothing here is tested.
- **The app scope's `CoroutineExceptionHandler` is non-negotiable.** A `SupervisorJob` does nothing for a throwable
  no child handles, which on Kotlin/Native terminates with `SIGABRT` before first paint. The handler logs to
  `debug.log` and lets the app live.
- `SnapSyncRoot` owns a process-lifetime `SupervisorJob`, not `rememberCoroutineScope` (which dies with the view).
- **Ledger writers belong to code, not to a process.** Outside the app's uploader (`compose/AppUploader.kt`, through
  `uploadCycle`), `:app:ios` constructs no `LedgerWriter`.
- Keep every new entry point wrapped as the existing ones are, or its downstream log lines lose the
  `[<Trigger>]` prefix.
- **`-lsqlite3`** is required in `OTHER_LDFLAGS` of every target that links SQLDelight's native driver. A missing
  one fails at link time, not at call time.
- **In-memory SQLite on Native** shares a database across connections through shared-cache: give each instance a
  **unique db name**, or tests and instances leak into each other.
- To exercise one uploader alone on a ≥26.1 device, use the rig: `rig dev-controls pin --app true --extension false`
  (`rig-channel`). The OS's upload-job registration lives in the system and survives relaunch and reinstall.
