## Context

`IosPhotoKitUploadPlatform` (`:adapter:ios:ext-safe`) and `IosUrlSessionUploadPlatform`
(`:adapter:ios:app-only`) both carry decision logic welded to OS objects a host cannot construct, and
both say so in their KDoc: *"Not unit-tested (device-verified)"*. Three shipped bugs came out of that
region. Their real cause is narrower than "untested code", and naming it correctly is what decides the
shape of this change.

**The cause is a lie in the type system.** Reading the Kotlin/Native Photos platform klib — the
compiler's own input — gives the cinterop view of a returned job:

```
job.destination            : NSURLRequest       ◀── declared non-null, nil at runtime
    .URL                   : NSURL?                honest
    .lastPathComponent     : String?               honest
job.resource               : PHAssetResource    ◀── declared non-null, nil for SUCCEEDED
    .uniformTypeIdentifier : String?               honest
job.error                  : NSError?              honest
job.responseHeaderFields   : Map?                  honest
```

Exactly two properties lie, and everything downstream of them is honestly nullable — the compiler
supplies those `?.` itself. The guards are two hand-written local widenings
(`IosPhotoKitUploadPlatform.kt:84` and `:93`), written for precisely this reason. `8c8dbe28`'s commit
message says it outright: *"Capture ObjC-nonnull-but-nilable values as nullable locals so runtime null
checks aren't elided."* Both look like a redundant `?` that a tidy-up would remove, and nothing in the
build would object.

Reading the same klib also closes a question this change was written believing was unanswerable
without a Mac:

```
PHAssetResourceUploadJobStateRegistered = 1
PHAssetResourceUploadJobStatePending    = 2   ◀── the only value reaching today's `else`
PHAssetResourceUploadJobStateFailed     = 3
PHAssetResourceUploadJobStateSucceeded  = 4
PHAssetResourceUploadJobStateCancelled  = 5
```

The set is closed at five and today's table is **complete and correct** — but it names four and lets
`Pending` fall through the `else`, so "a state I map correctly" and "a state I have never seen" are
indistinguishable at the one place that could distinguish them.

Reproduce both readings on Linux:

```
K=~/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.0
$K/bin/klib dump-metadata $K/klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.Photos
```

## Goals / Non-Goals

**Goals:**

- Make the two nullability widenings **compile-enforced** rather than conventional.
- Put the decision `8c8dbe28` got wrong (which field is the ledger key's source, and what to do when
  it is absent) under a test.
- Keep the two upload tiers symmetric in testability, since one-tier-only fixes are this project's
  recurring failure mode.
- Make `Pending` an explicit case, so the fallback arm means one thing.
- Detect a widened Apple vocabulary at the pull request that widens it, on Linux.

**Non-Goals:**

- **No behaviour change.** Every extracted function is the existing expression moved; `Pending` maps
  as the `else` already did; no `:domain` type or control flow moves.
- Not restructuring the fetch/drain loops' effects (`performChangesAndWait`, acknowledge ordering) —
  they are device-verified and their correctness is OS interaction, not mapping.
- Not extracting `IosUrlSessionUploadPlatform`'s stateful machinery (lock, in-flight map, staging,
  sweep) — its correctness is concurrency and filesystem, where extraction buys little.
- Not typing `UploadError` more precisely (see D6).
- Not adding response diagnostics (see D8).

## Decisions

### D1 — The extracted functions live beside their adapters, tested in `iosTest`, not in `:domain` `commonTest`

`module-architecture`'s ports law is explicit: a platform's "magic values, ABI integers, identifier
grammars and error-domain tables SHALL NOT appear in `model/`, `ports/` or `feature/`, **even where the
platform-free zones are the cheaper place to unit-test them**." The convenient placement is
pre-rejected by the contract.

The repo has also already made this exact mistake and reversed it. `PhotoKitResourceRoleTest`'s KDoc:

> These assertions used to live in `:domain`'s `commonTest` as bare integers compared to bare integers
> (`resourceRole(1L)`, `resourceRole(9L)`) — a table over Apple's ABI that no JVM run could disagree
> with, and that no gate could see, because an ABI decoder written in primitives is indistinguishable
> from arithmetic.

So `photoKitResourceRole` / `PhotoKitResourceRoleTest` is the shape to copy: a pure top-level function
in the adapter's `iosMain`, with tests in the same module's `iosTest` that name the **real SDK
constants**.

*Alternative considered:* the `processingResultRawValue` shape — pure functions over raw `Long` in
`:domain` `ports/`, pinned in `commonTest`. Rejected: it needs a `module-architecture` amendment and
recreates the anti-pattern above. Its precedent does not transfer, because the thing that forced
`processingResultRawValue` inward was that
`PHBackgroundResourceUploadProcessingResult` is **Swift-only** and unconstructible from Kotlin at all.
No such force exists here.

*Cost, accepted:* neither adapter module declares a `jvm()` target, so these tests run on
`iosSimulatorArm64` only — macOS CI, **not** `./gradlew build` on Linux. Testing rule 1 ("every unit
test runs on the iOS simulator too") is satisfied; its Linux half is not reachable for a table over
Apple's ABI, which is the point of D1 rather than a regression.

### D2 — The nullable parameter is the deliverable; the test is what pins it

The extracted signatures declare what is **true at runtime**, not what cinterop claims:

```kotlin
fun classifyPhotoKitJob(destination: NSURLRequest?, state: PHAssetResourceUploadJobState, error: NSError?): FetchedJob
fun photoKitContentType(resource: PHAssetResource?): String
```

Passing a non-null-typed value into a nullable parameter is a safe widening the compiler performs at
the call site, and inside the function the null check is on a genuinely nullable type, so it cannot be
elided. The widening happens **once, at a named boundary**, instead of in a local that reads as
redundant.

The enforcement comes from the tests:

```kotlin
assertEquals(FetchedJob.AcknowledgeToDrain, classifyPhotoKitJob(destination = null, …))
assertEquals("application/octet-stream", photoKitContentType(null))
```

If someone later narrows a parameter to match cinterop, **those lines stop compiling**. That converts
an unenforceable convention into a build failure at the exact place the elision hazard lives, and it is
the strongest guarantee in this change.

State the limit honestly: a test asserting `classifyPhotoKitJob(null, …) == AcknowledgeToDrain` would
**not** have caught `8c8dbe28` on its own — whoever wrote it would have encoded the same wrong belief
about which field to trust. The structural nullability does the work; the test pins it and stops it
being undone.

### D3 — Scope: the decisions, not the effects

Extracted from `IosPhotoKitUploadPlatform`:

| function | replaces | why it is a decision |
|---|---|---|
| `photoKitJobState(state)` | `mapState` | table over Apple's ABI |
| `createResultFor(errorCode: Long?)` | the `createJob` `when` | table over `PHPhotosErrorLimitExceeded` (= 3307) |
| `uploadErrorFrom(domain, code)` | `mapError` | string composition |
| `classifyPhotoKitJob(destination, state, error)` | the per-job body of `fetch` | emit vs acknowledge-to-drain — `8c8dbe28` |
| `photoKitContentType(resource)` | the `?:` in `fetch` | absence collapse — `05435ff9` |

Extracted from `IosUrlSessionUploadPlatform`: `classifyUrlSessionCompletion(taskDescription, statusCode, error)`
and `strandedKeys(pending, live, drained)`. The second is pure `String`-set arithmetic with no platform
type in it, and is the reconciliation that decides whether a lost transfer is ever retried.

The loops keep only what cannot leave: the unconstructible `handle` and `data`, and the effects
(`performChangesAndWait`, `acknowledge`, task creation). `PHAssetResourceUploadJob` has no public
initializer and only arrives from a fetch, so no test can drive the loop with synthetic jobs — but
`NSURLRequest`, `NSURL`, `NSError` and `NSHTTPURLResponse` are all constructible on a simulator, which
is exactly why the classifier's inputs were chosen to be those.

### D4 — `Pending` becomes explicit; no runtime `UNKNOWN` state is introduced

An earlier iteration of this design added `PlatformJobState.UNKNOWN` with an explicit `UploadCycle` arm
and an `Error`-severity line reaching Bugsink. Reading the klib retired it, for three reasons:

1. **It would have been a regression as first drafted.** `Pending` = 2 currently reaches `PENDING`
   *through* the `else`. Repointing `else` at `UNKNOWN` without naming `Pending` reclassifies every
   in-flight job — and in the terminal-job drain an `UNKNOWN` job would be adjudicated as a
   retry-spent failure, recording `FAILED` and re-creating a job for work still legitimately running.
2. **Once `Pending` is named, `UNKNOWN` is unreachable** on the declared vocabulary. It would fire only
   for a value appearing in no SDK header.
3. **The existing fallthrough is already safe for that value.** Trace it: not `SUCCEEDED`, not
   already-`COMPLETED` in the ledger → record `FAILED`, re-create if the resource survives, always
   acknowledge. Idempotent PUT, at-least-once, no stranded `REQUESTED` row. `UNKNOWN` would have bought
   visibility, not safety — and D5 buys better visibility earlier and cheaper.

So the change is: name `Pending`, keep the fallback arm, and let the `else` mean exactly one thing.
This also keeps the change behaviour-preserving, which removes the `ios-photokit-upload`,
`harness-world-model` and `:domain` deltas the earlier draft required.

*Alternative considered:* harvest exhaustiveness by rewriting `UploadCycle`'s drain from a
subject-less `when { }` into `when (job.state)`, so a new `PlatformJobState` case stops compiling. Real
but rejected here: the drain's middle arm is a **ledger read**, not a state test, so the rewrite
restructures the one loop that has shipped three bugs — and the guarantee only fires once a human has
already noticed the SDK moved, which is the thing D5 does directly.

### D5 — The tripwire is a build-time pin over the platform klib, not a runtime signal

The failure worth catching is "Apple declared a case and we did not teach it". That happens at a
toolchain bump, not on a device. Because the Apple platform klibs ship **prebuilt inside the
Kotlin/Native distribution**, the vocabulary our source sees is a function of the Kotlin/Native version
— so the pin fails on the `gradle/libs.versions.toml` bump PR, on Linux, in the required build, naming
the new constant.

Compare with the rejected runtime signal:

| | runtime `UNKNOWN` + Bugsink | build-time klib pin |
|---|---|---|
| fires when | a device produces the state | the declared set changes |
| fires where | Release builds only (no DSN in dev/sideload) | `./gradlew build`, Linux, required |
| latency | weeks–months after the SDK ships | the PR that causes it |
| tells you | `raw value = 6` | the constant's name and value |
| catches | undeclared OS values (rare) | declared new cases (the actual worry) |

**A note for whoever reads this next to `module-architecture`'s law that "a platform-capability claim is
settled by a compile, not by a symbol table".** These do not conflict; they answer different questions.
That law is about *capability* — "can I call this?" — where the symbol table over-promises, as
`Dispatchers.IO` demonstrated (visible in the klib, `internal`, the design withdrawn at first compile).
This pin is about *vocabulary* — "what does this declare?" — where the klib is not evidence about the
compile, it **is** the compile's input. And neither answers "what does the device do?", which only a
measurement settles. That third line is why the fallback arms stay load-bearing.

**Inventory choice.** The pin seeds with two entries, both decoders with a fallback arm:
`PHAssetResourceUploadJobState` (this change's table) and `PHAssetResourceType` (`photoKitResourceRole`,
in the same module). The second is included because its fallback **drops** a resource: an untaught
original resource type is a photo that never uploads, with no error anywhere — the silent-failure class
this project treats as the worst outcome. Excluding it would make the guard's rule arbitrary. It is
nonetheless separable if the reviewer prefers a narrower first cut.

**Not pinned:** constants referenced by name in Kotlin (`PHPhotosErrorLimitExceeded`,
`PHAssetResourceUploadJobAction*`). A rename already breaks the compile, and their values are never
decoded through a fallback.

### D6 — `UploadError` stays flattened to `Unknown("domain:code")`

`UploadError`'s own KDoc: *"v1 policy ignores the distinction (retry forever) — the taxonomy exists for
logging today and for a future attempt-budget policy."* Nothing branches on the variant, so typing
`http:$status` as `UploadError.Http(status)` buys no behaviour and widens the diff into a third concern.
The tests pin the exact strings, which is what a future typed mapping would have to change deliberately.

### D7 — The simulator claim is narrowed by a test, and the smoke test moves

`PhotoKitSmokeTest` calls the background-upload-job APIs part of the *"simulator-unavailable
subsystems"*. A manual measurement (simulator, iOS 26.x / Xcode 26.6 / macOS 26.5.2, 2026-08-09) found
`PHAssetResourceUploadJob.fetchJobsWithAction(…)` returns 0 without trapping or erroring. This change
converts that n=1 note into a standing CI assertion and narrows the prose to what remains unmeasured:
job **creation**, and whether the OS performs the upload.

It also moves from `:app:ios:extension` to `:adapter:ios:ext-safe`. `:app:*` is wiring-only and
untested by project rule, and the adapter it smoke-tests has lived in `ext-safe` since the migration
finale — the test is simply in the wrong module.

### D8 — `responseHeaderFields` is deliberately not built

The PhotoKit tier discards `job.responseHeaderFields`, so a failed upload reports
`PHPhotosErrorDomain:<code>` and nothing about what the edge replied. Tempting given `0c622dc3` was a
403 SigV4 diagnosis done by hand — but the klib shows the job carries **no `statusCode`**, so the field
yields headers only (a request id at best), and what PhotoKit actually populates there, or in
`error.userInfo`, is unmeasured. It is also a `diagnostic-logging` concern with its own argument, and it
is payload rather than a decision, so it cannot ride in the classifier.

Recorded as: measure first (one failed job on device, dump `responseHeaderFields` and `error.userInfo`),
then decide. Note the "Absence is never silent" reading does **not** compel it — the two collapsed
causes ("network died" / "edge rejected") have the same consequence here, retry forever, so only the
diagnosis suffers.

## Risks / Trade-offs

- **The change's own tests do not run on Linux** → accepted consequence of D1, stated in the proposal
  so a green local `./gradlew build` is not misread as having exercised them. The klib pin is the part
  that does run there. `compileIosMainKotlinMetadata` still catches compile breakage locally.
- **The klib guard must locate the konan distribution the Kotlin plugin provisioned**, not a hardcoded
  path, and it shells out to a CLI — a new shape for `:test:architecture`, whose guards are text scans
  today → order it **last** in the task list, with a pre-agreed fallback: a Kotlin/Native test naming
  all five constants, which catches a rename or removal by failing to compile and misses an addition.
  Half the guarantee, near-zero machinery; the change still lands.
- **`photoKitContentType` is only half-testable** — `PHAssetResource` is unconstructible off-device and
  an unauthorised simulator has no asset to fetch one from, so only the `null` arm is exercised →
  accepted: that arm is the one that shipped as `EXC_BAD_ACCESS`, and it is the arm that pins the
  signature.
- **The live `fetchJobsWithAction` test rests on an n=1 measurement** → **discharged.** It passed on a
  fresh `macos-26` runner (Xcode 26.6) for both `.acknowledge` and `.retry`, independently reproducing
  the 2026-08-09 hand measurement. n=2, and now a standing assertion.
- **Moving `PhotoKitSmokeTest` into `ext-safe`** puts it in a test binary with the Sentry dynamic
  framework link and `-lsqlite3` linker options → that module's test executable is already configured
  for both; no new provisioning.
- **Refactoring device-verified code with no device in the loop** → **discharged by a device pass**
  (SE2, iOS 26.6, 2026-08-09; see "Device verification" below).

## Device verification

The reshaped fetch/drain loop was exercised on a real device before merge — the loop had never had
non-device verification, which is the whole reason it was untested, and this change altered its shape.

Setup: dev IPA built and signed through the ssh-mac loop, installed over USB. `SNAPSYNC_SEED_POLICY=20`
supplied admissible assets (an event created today clamps its cutoff to *now*, so without the +1h seeds
nothing would qualify and a broken drain would be indistinguishable from a healthy one).
`SNAPSYNC_RESET_STATE=1` was **required**: the device had previously been pointed at a local tunnel
backend, and without the reset the stale ledger and retained cursor would have uploaded nothing,
silently — the crossing trap the root `CLAUDE.md` documents, encountered exactly as described.

Result — the oracle being watched was error 50008 (`appex failed to acknowledge jobs for processing
state`), which is what a mishandled key recovery produces:

| check | result |
|---|---|
| `50008` in device syslog, `ext-debug.log`, `debug.log` | **0 occurrences** |
| terminal jobs drained | `fetchAckJobs = 10` → `0` → `0` (not re-presented) |
| every presented job acknowledged | **10 of 10**, each by a key recovered from `destination` |
| selection policy | `admitted 10 of 20` — exactly what the seed predicts |
| bytes landed (authoritative, not the status screen) | `GET /files/devices/<id> → 200`; next cycle reported `10 seen, 0 new, 10 already-uploaded`; `device.json` PUT → 201 |
| extension crashes | 0 |

**Honest limit:** the `AcknowledgeToDrain` branch **never fired on device** — no job lacked a
destination — so that path remains covered by `PhotoKitJobMappingTest` alone. It cannot be provoked on
demand (the OS decides what it presents), which is precisely the argument for extracting it: a branch
reachable only by an OS state you cannot induce is a branch that will otherwise never be tested at all.

## Open Questions

- ~~Does the klib guard shell out to `bin/klib dump-metadata` and parse text, or read the klib archive
  directly?~~ **Resolved in implementation: shells out and parses text.** It runs in 0.6 s, resolves the
  distribution from `libs.versions.toml` + `KONAN_DATA_DIR`/`~/.konan` (never a hardcoded path), and
  fails loudly when the distribution, tool, klib, or a parsed constant is missing — "nothing declared"
  and "I could not look" being the two answers that must not collapse. The 5.6 fallback was not needed.
- ~~Should `PHAssetResourceType` be in the first cut of the pin (D5)?~~ **Yes — included.** Excluding it
  would have made the guard's own rule ("every enumeration decoded with a fallback arm") arbitrary, and
  its fallback is the more dangerous of the two: it *drops* the resource, so an untaught type is a photo
  that never uploads with no error anywhere.
- **Still open.** Is `PhotoKitSmokeTest`'s remaining device-only claim worth a second measurement — does
  `creationRequestForJobWithDestination` fail cleanly on a simulator, or trap? The device pass did not
  answer this (it exercised a real device, where creation works). Answering it would let the prose stop
  hedging.
