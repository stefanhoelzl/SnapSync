## Why

The headless iOS upload extension's only observability is its device log, and today that log is an incomplete, terse record: several key seams are unlogged (background-task triggers, deeplink/push app entry points, every HTTP request), lines carry no indication of *what triggered* the work or how long it took, and full-library enumeration leaves no trace of what it considered. When an upload silently does nothing, there is no way to see what ran, what it was handed, or where the time went. This change makes each process's device log a complete, self-explaining record of what the app and extension actually did — without changing any product behavior.

Note on scope: the app and extension run in separate processes with separate sandboxes, and only a process's *own* `Documents/` is reachable via `pymobiledevice3 apps pull` (the shared App Group container is not — verified). So the two logs stay separate, per-process files (as today); this change improves their content, not their number.

## What Changes

- **Log every platform invocation** with a uniform enter/exit convention that records the entry-point name, its parameters, its result, and its duration — applied across the full seam list (upload-platform methods, the extension `process()` cycle, background-pump triggers, schedulers, all app entry points, the download controller, the app-driven upload controller).
- **Ambient invocation context**: every line carries a `[<entryPoint>]` prefix (e.g. `[onSilentPush]`, `[process]`, `[onBackgroundTask]`) set by the outermost entry point, so every downstream engine/HTTP/download line traces back to what triggered it. (The process — app vs extension — is already identified by which file the line is in, so no process token is included.)
- **Process lifecycle banners**: each process writes a boot banner (naming the process and build version) on start and a teardown line, marking where each run begins and ends within its file.
- **Log all HTTP requests** via a lightweight custom Ktor client interceptor on the shared client — one line per request: method, URL, status, duration, request size, response size.
- **SyncEngine enumeration accounting**: keep the existing work/started/completed/failed lines and add a per-cycle summary (`seen / new / already-uploaded`) so full-library enumeration is accountable without emitting a line per skipped asset.
- **Bound each log file**: roll `debug.log` to `debug.log.1` once it exceeds 10 MB (keep one previous file), so pulls stay fast and history is bounded without manual clearing.
- **Atomic-append writes**: each line is written as a single append so a line is never torn.
- Confirmed non-goal: **no redaction is added or removed** — the file logger already writes verbatim (verified by pulling the live device log); this change preserves that and introduces no masking.

## Capabilities

### New Capabilities
- `diagnostic-logging`: the device diagnostic-log contract — per-process, un-redacted, size-bounded (10 MB roll) log files; the uniform invocation enter/exit logging convention and its ambient `[entryPoint]` context prefix; process boot/teardown banners; HTTP request logging; and the SyncEngine per-cycle enumeration summary.

### Modified Capabilities
<!-- None. The instrumented seams (app-shell entry points, sync-engine, upload tiers, photo-download) keep their existing product behavior unchanged; only observability is added, which the new diagnostic-logging capability owns. -->

## Impact

- **Code — logging core**: both `FileLogWriter` copies (app + extension) gain atomic-append + 10 MB size-cap/roll, each still writing to its own process `Documents/debug.log`; a new small shared module houses the ambient invocation-context holder + the enter/exit helper, and is a candidate home for the currently-duplicated `FileLogWriter`/`PublicNSLogWriter` (resolved in design).
- **Code — HTTP**: the shared Darwin `HttpClient` factory gains a custom logging interceptor plugin; all seven Ktor call sites are covered without per-call edits.
- **Code — seams instrumented**: `SnapSyncRoot` and `UploadExtensionRoot` (banners + context + entry logging), `BackgroundUploadPump`, `IosBackgroundScheduler`/`UrlSessionUploadController`, `IosPhotoKitUploadPlatform`/`IosUrlSessionUploadPlatform` (`UploadJobPlatform` methods), `DownloadController`, and `SyncEngine`/`UploadCycle` (enumeration summary). Existing ad-hoc log lines in these seams are migrated onto the uniform convention.
- **Dependencies**: none added — Kermit (existing) remains the facade; the interceptor and context holder are hand-rolled (no KMP rolling-file library exists).
- **Behavior / product**: none. This is dev/test observability only; no user-facing behavior, sync semantics, or upload outcomes change.
- **Verification**: manual/on-device — pull each `debug.log` over USB and inspect (no new required unit tests, per the test-equipment framing). The two files remain separately pullable via `apps pull app.snapsync Documents/debug.log` and `apps pull app.snapsync.BackgroundUpload Documents/debug.log`.
