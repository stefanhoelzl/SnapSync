## 1. PhotoKit adapter — extract the decisions

- [x] 1.1 Create `adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/ios/upload/PhotoKitJobMapping.kt` with `photoKitJobState(state: PHAssetResourceUploadJobState): PlatformJobState`, moving `mapState`'s table verbatim **plus an explicit `PHAssetResourceUploadJobStatePending -> PENDING` arm** (behaviour-identical; the `else` stops meaning two things — design D4)
- [x] 1.2 Add `createResultFor(errorCode: Long?): CreateResult` to the same file, moving `createJob`'s `when` (null → `CREATED`, `PHPhotosErrorLimitExceeded` → `LIMIT_EXCEEDED`, else → `FAILED`); the caller keeps the two `log.w` lines, which need the error's `localizedDescription`
- [x] 1.3 Add `photoKitUploadError(error: NSError): UploadError` returning `UploadError.Unknown("$domain:$code")` — unchanged shape (design D6). *Deviation from the planned `uploadErrorFrom(domain, code)`: `NSError` is constructible on a simulator, so taking it whole keeps the destructuring in one place and loses no testability.*
- [x] 1.4 Add `photoKitContentType(resource: PHAssetResource?): String` — the `?: "application/octet-stream"` absence collapse. **The parameter is nullable on purpose**: cinterop declares `PHAssetResourceUploadJob.resource` non-null and it is nil for succeeded jobs (design D2). Say so in the KDoc.
- [x] 1.5 Add the `FetchedJob` result type (`Emit(key, state, error)` / `AcknowledgeToDrain`) and `classifyPhotoKitJob(destination: NSURLRequest?, state: PHAssetResourceUploadJobState, error: NSError?): FetchedJob`, moving the key recovery and the null-destination drain decision out of `fetch`. **`destination` is nullable on purpose** — same reason, same KDoc note.
- [x] 1.6 Rewrite `IosPhotoKitUploadPlatform.fetch` to call `classifyPhotoKitJob(job.destination, job.state, job.error)` and attach only what cannot leave the loop (`contentType` via `photoKitContentType(job.resource)`, `data = job.resource`, `handle = job`); keep the `log.w` + `acknowledgeJob` effect on `AcknowledgeToDrain`. Delete the now-redundant nullable locals.
- [x] 1.7 Delete the dead `actionName` function (nothing calls it) and fix `ports/BackgroundTransfer.kt`'s KDoc reference to the long-gone `IosBackgroundTransfer`
- [x] 1.8 Rewrite `IosPhotoKitUploadPlatform`'s class KDoc: the mapping tables and the per-job classifier are unit-tested; the fetch/drain **effects** and job creation remain device-verified

## 2. PhotoKit adapter — tests

- [x] 2.1 Create `adapter/ios/ext-safe/src/iosTest/kotlin/app/snapsync/ios/upload/PhotoKitJobMappingTest.kt` asserting all **five** SDK constants by name (`…StateRegistered`/`Pending`/`Failed`/`Succeeded`/`Cancelled`) map as expected — never bare integers (design D1)
- [x] 2.2 Test `createResultFor`: `null` → `CREATED`, `PHPhotosErrorLimitExceeded` → `LIMIT_EXCEEDED`, an arbitrary other code → `FAILED` (name the SDK constant, not `3307`)
- [x] 2.3 Test `classifyPhotoKitJob` with a **real** `NSURLRequest` built from `NSURL.URLWithString("https://h/api/v1/x/abc-primary.heic")` → `Emit(key = "abc-primary.heic", …)`, and with `destination = null` → `AcknowledgeToDrain`. **The `null` call is the guard** that stops the parameter being narrowed later (design D2) — say so in the test KDoc.
- [x] 2.4 Test `photoKitContentType(null) == "application/octet-stream"`, with a KDoc note that the non-null arm is unreachable off-device (`PHAssetResource` is unconstructible; an unauthorised simulator has no asset to fetch one from)
- [x] 2.5 Test `uploadErrorFrom` produces the exact `"domain:code"` string the logs and diagnostics depend on

## 3. URLSession adapter — symmetric extraction

- [x] 3.1 Create `adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/ios/urlsession/UrlSessionOutcome.kt` with `classifyUrlSessionCompletion(taskDescription: String?, statusCode: Long, error: NSError?)`, moving `SessionDelegate.onTaskComplete`'s success rule (`error == null && status in 200..299`), its error shape (`"domain:code"` / `"http:$status"`) and its no-description case out of the delegate
- [x] 3.2 Add `strandedKeys(pending: Set<String>, live: Set<String>, drained: Set<String>): List<String>` — pure `String`-set arithmetic, no platform type — and call it from `fetchAckJobs`, keeping the per-key `log.i` at the call site
- [x] 3.3 Rewrite both call sites to delegate; leave the lock, in-flight map, staging and sweep untouched (design non-goal)
- [x] 3.4 Update `IosUrlSessionUploadPlatform`'s KDoc "Not unit-tested (device-verified)" sentence to name what is now tested and what is not
- [x] 3.5 Create `adapter/ios/app-only/src/iosTest/kotlin/app/snapsync/ios/urlsession/UrlSessionOutcomeTest.kt`: 2xx with no error → success; a non-2xx status → `Unknown("http:<status>")`; a real `NSError` → `Unknown("domain:code")`; `taskDescription = null` → the no-description outcome (again the signature guard); and `strandedKeys` over pending/live/drained including the empty and fully-overlapping cases

## 4. Simulator reach — narrow the device-only claim

- [x] 4.1 Move `PhotoKitSmokeTest` from `app/ios/extension/src/iosTest/` to `adapter/ios/ext-safe/src/iosTest/kotlin/app/snapsync/ios/upload/`, restoring `:app:*` to wiring-only-and-untested
- [x] 4.2 Add a test calling `PHAssetResourceUploadJob.fetchJobsWithAction(PHAssetResourceUploadJobActionAcknowledge, options = null)` and asserting `count == 0uL` — turning the 2026-08-09 n=1 measurement into a standing CI fact (design D7)
- [x] 4.3 Narrow the smoke test's KDoc: the fetch half is simulator-callable; job **creation** and whether the OS performs the upload remain unmeasured and device-only
- [x] 4.4 Delete `app/ios/extension/src/iosTest/` if now empty, and confirm no Gradle config referenced that source set. *Its `commonTest` deps and Sentry test-link stay: Kotlin/Native links an empty `test.kexe` even with no test sources, so removing them would break the link. Commented in the build script so it is not read as dead config.*

## 5. The platform-vocabulary pin

- [x] 5.1 Add the guard to `:test:architecture`: resolve the Kotlin/Native distribution the build provisioned (never a hardcoded `~/.konan/...` path), run `bin/klib dump-metadata` over `klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.Photos`, and parse the declared constants
- [x] 5.2 Pin `PHAssetResourceUploadJobState` = {`Registered` 1, `Pending` 2, `Failed` 3, `Succeeded` 4, `Cancelled` 5}, failing **in both directions** (added, removed, renamed, re-valued) with a message naming the enumeration and the offending constant
- [x] 5.3 Pin `PHAssetResourceType` (decoded by `photoKitResourceRole`, whose fallback silently **drops** a resource) — separable from 5.2 if the reviewer prefers a narrower first cut (design D5, open question)
- [x] 5.4 Carry the forcing proof in the failure message per `architecture-guards` ("Gates that pin exceptions SHALL carry the forcing proof"): cinterop renders `NS_ENUM` as a typealias over `NSInteger` plus loose constants, so a `when` over one can never be compiler-exhaustive — this pin supplies what the language cannot
- [x] 5.5 Verify the guard runs on Linux in `./gradlew build` with no Xcode present, and that it fails loudly (never silently passes) when the distribution or klib cannot be found — absence is not "nothing changed"
- [x] 5.6 ~~**Fallback if 5.1 proves fragile:**~~ **Not needed** — 5.1 landed: `klib dump-metadata` runs in 0.6 s, the distribution resolves from `libs.versions.toml` + `KONAN_DATA_DIR`/`~/.konan`, and the guard was verified to fail on all three delta kinds (added / removed / re-valued) across both pinned enumerations. Original plan retained for the record: drop the metadata guard and instead add a Kotlin/Native test naming all five constants — catches a rename or removal at compile, misses an addition. Record the downgrade in `design.md` rather than leaving the spec claiming a guarantee that does not exist.

## 6. Verify

- [x] 6.1 `./gradlew compileIosMainKotlinMetadata` — the Linux proxy for both adapters' `iosMain`
- [x] 6.2 `./gradlew build` — the new guard, the zone gates, and `architectureDiagrams` staleness
- [x] 6.3 `./gradlew architectureDiagrams` — ran clean, **no `architecture/` churn** to commit
- [x] 6.4 Confirm on macOS CI (`ios-test`) that the new `iosSimulatorArm64Test` suites in both adapter modules pass — including 4.2, whose failure would falsify the simulator measurement rather than the code. *Run via the ssh-mac loop instead of CI (macOS 26.5.2 / Xcode 26.6, 2026-08-09): 20 new tests green, whole iOS suite 1198 tests / 0 skipped / 0 failures. `fetching_upload_jobs_returns_an_empty_result_without_trapping` PASSED on a fresh runner — the 2026-08-09 measurement is now independently reproduced.*
- [x] 6.5 Confirm the diff changes **no** behaviour: every extracted body is the prior expression, the `Pending` arm matches the prior `else` result, and no `:domain` type or control flow moved
- [x] 6.6 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`

## 7. Documentation

- [x] 7.1 Add the klib technique to the root `CLAUDE.md`: `klib dump-metadata` against the provisioned Kotlin/Native distribution answers enum case sets, constant values, property nullability and selector encodings **on Linux, with no Mac**
- [x] 7.2 Place it beside the existing law "a platform-capability claim is settled by a compile, not by a symbol table" and state the distinction explicitly, or the two read as contradictory: *can I call this?* → the symbol table over-promises, settle it with a compile; *what does this declare?* → the klib is the compiler's input, authoritative by construction; *what does the device do?* → neither, only a measurement
- [x] 7.3 Note that the platform klibs ship prebuilt inside the Kotlin/Native distribution, so the declared vocabulary tracks the **Kotlin/Native version**, not the locally installed Xcode

## 8. Follow-up found while verifying

- [x] 8.1 The ssh-mac run falsified a claim made in 4.4: with no test sources Kotlin/Native reports `compileTestKotlin…`/`linkDebugTest…` as **NO-SOURCE** and skips `iosSimulatorArm64Test` — it does **not** link an empty `test.kexe`. So `:app:ios:extension`'s Sentry test-link and `commonTest` deps were dead, not inert. Removed both; re-verified on the same runner that `:app:ios:extension:assemble` and all 1198 tests still pass.
- [x] 8.2 **Device pass on the reshaped drain** (SE2, iOS 26.6, 2026-08-09) — full account in `design.md` § *Device verification*. Dev IPA via ssh-mac → USB install → `SNAPSYNC_SEED_POLICY=20` + `SNAPSYNC_RESET_STATE=1` + a fresh event. **0 occurrences of error 50008** anywhere; `fetchAckJobs = 10 → 0 → 0` (drained, not re-presented); **10/10 jobs acknowledged by a key recovered from `destination`**; policy `admitted 10 of 20`; bytes confirmed on the backend (`GET /files/devices/<id> → 200`, next cycle `10 seen, 0 new, 10 already-uploaded`, `device.json` → 201); 0 crashes. Limit recorded honestly: the `AcknowledgeToDrain` branch never fired on device and stays unit-tested only.
- [x] 8.3 Found during 8.2: every documented `apps pull` invocation omitted the required third argument (`local_file`), so each exits 2 with *"Missing argument 'local_file'"* and writes nothing — it does not default to cwd. **This change ships no fix for it.** Two rebases showed why: `7df45c67` had already moved the device runbook out of `CLAUDE.md` into `.claude/skills/ios-device/SKILL.md`, and then a PR merging ahead of this one fixed the same defect there independently, in near-identical words. The duplicate was dropped in favour of what landed first. Recorded because the finding is what matters, not who carried it.
