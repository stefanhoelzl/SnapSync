## Why

Two cinterop types lie. `PHAssetResourceUploadJob.destination` and `.resource` are declared
**non-null** by the Kotlin/Native Photos klib and are **nil at runtime** — `destination` for some job
states, `resource` for every succeeded job (the system releases it after upload). Both facts were
learned the hard way on device, and the guards against them are two hand-written local widenings in
`IosPhotoKitUploadPlatform` that look exactly like a redundant `?` a tidy-up would delete:

- `05435ff9` — `EXC_BAD_ACCESS` crash-loop before any work: the drain dereferenced `resource`.
- `8c8dbe28` — system error 50008, stalled at "sync in progress": succeeded jobs were skipped and
  never acknowledged, because the key was read from `resource` rather than from `destination`.

Nothing enforces those widenings. The whole file is marked *"Not unit-tested (the upload-job
subsystem is device-only); verified on a real device"*, and its sibling `IosUrlSessionUploadPlatform`
carries the same note over the same class of decision. No test rig on any host changes that while the
decisions are welded to OS objects a host cannot construct.

Extracting each decision into a function whose parameters are **honestly nullable** converts an
unenforceable convention into a compile error: a test that passes `null` stops compiling if someone
later narrows the parameter to match what cinterop claims. That — not testability — is the point.

A second, unrelated silence sits beside it. `PHAssetResourceUploadJobState` declares exactly five
cases (`Registered`=1, `Pending`=2, `Failed`=3, `Succeeded`=4, `Cancelled`=5). Today's table names
four and lets `Pending` fall through an `else`, so a state we handle correctly and a state we have
never seen are indistinguishable at the one place that could tell them apart. The case set is
readable **on Linux** from the platform klib the compiler itself consumes, so it can be pinned in the
required build rather than discovered by a device months later.

## What Changes

- Extract the technology-vocabulary decisions out of `IosPhotoKitUploadPlatform`
  (`:adapter:ios:ext-safe`) into pure top-level functions beside it, tested in that module's
  `iosTest` naming the real SDK constants — the shape already established by
  `photoKitResourceRole` / `PhotoKitResourceRoleTest`:
  - the job-state table, with **`Pending` named explicitly** (behaviour-identical: `2` maps to
    `PENDING` either way, but the `else` stops meaning two things),
  - the create-result table over `PHPhotosErrorLimitExceeded`,
  - the `NSError` → `UploadError` mapping (flattening to `Unknown("domain:code")`, unchanged),
  - `classifyPhotoKitJob(destination: NSURLRequest?, state, error)` → emit-or-acknowledge-to-drain,
    the decision `8c8dbe28` got wrong,
  - `photoKitContentType(resource: PHAssetResource?)`, the absence collapse to
    `application/octet-stream` that `05435ff9` crashed on.
- Extract the two analogous decisions in `IosUrlSessionUploadPlatform` (`:adapter:ios:app-only`) —
  the task-completion classification and the stranded-key reconciliation — so the tiers do not
  diverge in testability. The stateful machinery (lock, in-flight map, staging, sweep) is untouched.
- Add a build-time guard pinning the `PHAssetResourceUploadJobState` case set read from the
  Kotlin/Native platform klib, so a case Apple adds fails the **Kotlin bump PR** on Linux instead of
  reaching a device untaught.
- Prove on the simulator what was assumed device-only: `fetchJobsWithAction` is callable and returns
  empty. `PhotoKitSmokeTest` moves from `:app:ios:extension` (where it violates the wiring-only rule)
  to `:adapter:ios:ext-safe`, and its "simulator-unavailable" claim narrows to what is still
  unmeasured — job **creation**, and whether the OS performs the upload.
- Delete dead code (`IosPhotoKitUploadPlatform.actionName`) and a stale KDoc reference to the
  long-gone `IosBackgroundTransfer`.

Not breaking. **No behaviour changes**: every extracted function is the existing expression moved,
the `Pending` arm maps as the `else` already did, and no `:domain` type or control flow moves.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `architecture-guards`: one new requirement for the platform-vocabulary pin. Every existing guard
  scans our own source or structure; this is the first whose input is the **toolchain's platform
  metadata**, and it lands on a gap that capability already confesses — that its lexical
  platform-identifier gate "SHALL NOT be assumed" to catch a decoder over another system's values.
  It is the inward mirror of "Runtime identity is pinned": that pins literals we hold which the OS
  also holds; this pins literals Apple holds which we encode.

`ios-photokit-upload` and `ios-url-session-upload` need **no** delta — their requirements describe
behaviour that does not change, `ios-photokit-upload` already permits the adapter to "branch on
technology vocabulary", and neither spec makes the device-only claim being corrected (that claim
lives only in KDoc). `module-architecture` needs none either: the placement chosen **honours** its
rule that a platform's magic values and error-domain tables stay out of `model/`/`ports/`/`feature/`,
rather than narrowing it.

## Impact

- `:adapter:ios:ext-safe` — new `iosMain` mapping file + `iosTest` tests; `PhotoKitSmokeTest` gains a
  seat here.
- `:adapter:ios:app-only` — new `iosMain` mapping file + `iosTest` tests.
- `:app:ios:extension` — loses its only test source file, restoring the wiring-only rule.
- `:test:architecture` — the new guard. Note it must locate the konan distribution the Kotlin plugin
  provisioned rather than hardcode a path; if that proves fragile the pre-agreed fallback is a
  Kotlin/Native test naming all five constants (catches a rename or removal, misses an addition).
- `:domain` — KDoc only.
- **Test reach**: the adapter tests run on `iosSimulatorArm64` only (neither adapter module declares
  a `jvm()` target), so they execute on macOS CI and **not** in `./gradlew build` on Linux. The new
  guard is the part of this change that does run on Linux.
