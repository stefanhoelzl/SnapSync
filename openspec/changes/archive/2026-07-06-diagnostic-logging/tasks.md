## 1. New `:domain:logging` module (D1)

- [x] 1.1 Create module `:domain:logging` (commonMain + iosMain), register in `settings.gradle.kts`, build.gradle.kts depending only on `libs.kermit` + coroutines
- [x] 1.2 Add `LogContext` in commonMain: a process-global holder (`current: String?`) with `enter(name): Boolean` (sets only if unset, returns whether it set) and `exit(didSet)` to restore (D2, outermost-wins)
- [x] 1.3 Add `logInvocation` suspend inline helper in commonMain: logs `→ name(params)` on entry, sets context via `LogContext.enter`, times the body with `TimeSource.Monotonic`, logs `← name = result (Nms)` on success / `✗ name threw error (Nms)` on throw, restores context; params/result supplied as short-string lambdas (D3)
- [x] 1.4 Move `FileLogWriter` into iosMain of `:domain:logging`, parameterized by process name + Documents path; prefix each line with `[LogContext.current]` when present; write each line as a single atomic append via `O_APPEND` (D2 read-side, D7 atomic append)
- [x] 1.5 Add 10 MB size-cap + roll-to-`.1` to `FileLogWriter` (rename over any existing `.1`, start fresh) (D7)
- [x] 1.6 Move `PublicNSLogWriter` into iosMain of `:domain:logging`; apply the same `[entryPoint]` prefix for consistency
- [x] 1.7 Delete the two duplicated `FileLogWriter`/`PublicNSLogWriter` copies in `:app:ios` and `:app:ios:photokit-extension`; remove the stale "no shared module" code comment

## 2. Wire the module into consumers (D1)

- [x] 2.1 Add `implementation(project(":domain:logging"))` to `:app:ios`, `:app:ios:photokit-extension`, `:app:ios:url-session-upload`, `:capability:upload`, `:capability:download`
- [x] 2.2 Confirm no dependency cycles and `./gradlew compileIosMainKotlinMetadata` still compiles the iOS source sets

## 3. Process lifecycle banners (D5)

- [x] 3.1 In `SnapSyncRoot` init/composition, install the consolidated writers and emit `=== app process start build=<ver> ===`; emit a teardown line on `onBackground`/clean shutdown
- [x] 3.2 In `UploadExtensionRoot` init, install the consolidated writers and emit `=== extension process start build=<ver> ===`
- [x] 3.3 Source the build version (from the app bundle / a generated constant) for the banners

## 4. Instrument platform invocations (D3, full seam list)

- [x] 4.1 Wrap `SnapSyncRoot` entry points with `logInvocation`: `onForeground`, `onBackground`, `onOpenUrl`, `onPushToken`, `onSilentPush`, `provisionEvent`, `enableBackgroundUpload`, `runDownloadBackstop`, `runUploadHeartbeat`, `applyLaunchEnvDeeplink`
- [x] 4.2 Wrap `UploadExtensionRoot.process()` with `logInvocation` (entry-point name `process`)
- [x] 4.3 Wrap `BackgroundUploadPump` triggers: `onForeground`, `onUploadCompleted`, `onSessionEvents`, `onBackgroundTask`
- [x] 4.4 Wrap `UrlSessionUploadController` entry points (`runCycle`, `start`, `onForeground`, `onBackgroundTask`, `onBackgroundSessionEvents`, `disable`, `leave`) and `IosBackgroundScheduler` (`scheduleNext`, `cancel`)
- [x] 4.5 Wrap `UploadJobPlatform` methods in `IosPhotoKitUploadPlatform` and `IosUrlSessionUploadPlatform` (`fetchRetryJobs`, `fetchAckJobs`, `retryJob`, `acknowledge`, `discoverResources`, `createJob`) with params (job count / key) + result
- [x] 4.6 Wrap `DownloadController` methods (`reconcile`, `onResourceStaged`, `importReady`, `onLeaveOrSwitch`) with params + result
- [x] 4.7 Migrate existing ad-hoc Info/Warn lines in these seams onto the uniform convention (remove now-redundant enter/exit lines)

## 5. HTTP request logging (D4)

- [x] 5.1 Add a custom Ktor client plugin (`createClientPlugin`/`HttpSend` interceptor) that times the request with `TimeSource.Monotonic`, reads request/response `Content-Length`, and logs one Info line `<METHOD> <url> → <status> (<ms>ms, req=<bytes>, resp=<bytes>)` via Kermit; log method+URL+failure on exception/timeout
- [x] 5.2 Install the plugin on the shared Darwin `HttpClient` factory (`DarwinHttpClient`) so all seven call sites are covered without per-call edits
- [x] 5.3 Verify the interceptor line inherits the ambient `[entryPoint]` prefix (emitted through Kermit)

## 6. SyncEngine enumeration summary (D6)

- [x] 6.1 In `UploadCycle` discover phase, tally the engine's `SyncDecision`s (Upload/Retry vs AlreadyUploaded) and log one `enumeration: <seen> seen, <new> new, <already> already-uploaded` line per cycle
- [x] 6.2 Confirm the per-asset `AlreadyUploaded` skip in `SyncEngine` stays silent (no per-asset line)

## 7. Verify (manual / on-device)

- [x] 7.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` pass
- [x] 7.2 Build + sideload the dev IPA (ssh-mac loop), exercise a fresh-event upload cycle and a silent-push download
- [x] 7.3 Pull both `debug.log` files and confirm: enter/exit lines with params/result/duration, `[entryPoint]` prefixes tracing downstream lines, HTTP one-liners, the enumeration summary, boot banners, and no redaction
- [x] 7.4 Confirm the 10 MB roll produces `debug.log.1` (verified on-device: pushed 11 MB debug.log → relaunch → debug.log.1=11.5 MB, debug.log fresh)

## 8. Docs

- [x] 8.1 Update `docs/design.md §7` (field diagnostics) and `CLAUDE.md` to document the two per-process logs, the `[entryPoint]` convention, the 10 MB roll, and that `debug.log` is the canonical un-redacted channel
- [x] 8.2 Run `openspec validate diagnostic-logging --strict`
