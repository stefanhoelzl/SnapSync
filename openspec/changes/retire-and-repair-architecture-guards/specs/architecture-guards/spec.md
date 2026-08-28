## MODIFIED Requirements

### Requirement: The zone gates

The zone boundaries inside the core SHALL be enforced by the **module graph** wherever a module can
withhold them, and by a derived text gate only where it cannot.

`:domain` SHALL be split into per-zone modules — `:domain:model` ← `:domain:ports` ← `:domain:feature` ←
`:domain:flow` ← `:domain:compose` — each declaring only the zone dependencies its law permits, so a
reference across a forbidden edge does not resolve. Compilation therefore enforces: `model/` references
nothing project-internal outside `model/`; `ports/` references only `model/`; `flow/` references only
`model/` and `feature/`; and `:ui:presentation` references only `model/`, feature read-model types, and
the injected flow command bundle. These four properties SHALL NOT also be asserted by a text gate: a
module boundary is unresolvable rather than merely forbidden, and unlike a text scan it covers generated
source and typealias re-exports.

Zone modules SHALL depend on one another with `implementation()` rather than `api()`, so a zone cannot
leak transitively to a downstream consumer.

Two properties remain outside what the module graph can express at acceptable cost, and SHALL remain
derived text gates:

- **features are mutually blind** — a feature references only `model/` and `ports/`, never a sibling
  feature (pairwise, features enumerated from the directory listing). Nine features cannot be nine
  modules;
- **`flow/` declares no `CoroutineScope` and accepts no non-suspend effect lambda** (law *A trigger flow
  never outlives its own run* — both doors, because removing the scope alone leaves the lambda one open).

A text gate SHALL NOT pass when its scope is absent: a missing or renamed zone directory SHALL fail the
build, never report itself pending. A gate that reports "pending" when its subject has moved is a gate
that fails open.

The `:domain` tree SHALL have no `iosMain` source directory, and `:domain` and `:ui` zones SHALL import
only their per-zone allowlisted libraries.

#### Scenario: A forbidden zone reference does not compile
- **WHEN** a file in a zone module references a declaration from a zone its module does not depend on,
  by import, fully-qualified name, or typealias
- **THEN** the reference does not resolve and the build fails at compilation, in that module

#### Scenario: A feature reaches a sibling
- **WHEN** a file under `feature/<a>/` references `feature/<b>/`
- **THEN** the feature-blindness gate fails, naming the file and both features

#### Scenario: A flow reacquires a way to detach
- **WHEN** a `flow/` class gains a `CoroutineScope` parameter or a non-suspend effect lambda
- **THEN** the gate fails, naming the file, before any device build

#### Scenario: A zone directory is renamed
- **WHEN** a scanned zone directory no longer exists under the path a text gate scans
- **THEN** the gate fails naming the absent scope, and does not report itself pending

### Requirement: The extension-safety text gate

The build SHALL fail when extension-linked Kotlin references any `platform.*` framework outside an
**allowlist of permitted frameworks**. The gate SHALL be expressed as that allowlist rather than as a
denylist of forbidden frameworks, and the allowlist SHALL name each permitted framework and SHALL be
small enough to read.

Inversion is required because `NS_EXTENSION_UNAVAILABLE` cannot be enumerated: cinterop drops the
attribute entirely, so it is absent from the platform klibs and from every artifact available to the
build. A denylist therefore covers only the frameworks someone remembered, while an allowlist covers
every framework — including ones Apple has not shipped yet — and fails closed on novelty.

The gate SHALL match **imports and fully-qualified references**, never raw text: `platform` is also a
local variable name in this codebase, so a text match yields false positives on ordinary member access.

The scanned scope SHALL be **derived from the extension binary's project-dependency closure**, not from a
maintained list of roots, so a module newly linked into the extension is covered without a gate edit. The
gate SHALL fail if its derived scope is empty.

Expiry trigger: Kotlin/Native gaining extension-availability modelling, at which point the compiler
supersedes this gate.

#### Scenario: App-only API inside extension-linked code
- **WHEN** extension-linked source references a `platform.*` framework outside the allowlist
- **THEN** the gate fails before any device build, naming the file and the framework

#### Scenario: A framework Apple adds later
- **WHEN** extension-linked source references a platform framework nobody anticipated
- **THEN** the gate fails, because the framework is not on the allowlist, without any gate edit having
  been required to anticipate it

#### Scenario: A module joins the extension's link set
- **WHEN** the extension binary gains a dependency on a module the gate has never scanned
- **THEN** that module's source is in scope automatically, because the scope is derived from the
  dependency closure

### Requirement: The event-link domain agrees across the app and the backend

The event link's domain SHALL be **single-sourced from one resolved deployment** (capability
`deployment-configuration`) in every place it appears: the app's `applinks:` associated-domains
entitlement, the app's `LINK_ORIGIN` constant, the Apple App Site Association document the backend serves,
the compile-time device-facing upload host, and the browser-facing site's canonical URLs.

Agreement is **constructed rather than asserted**: each copy is generated from the one resolution, so a
copy cannot drift. The guarantee reaches copies no hand-written pin ever inspected — the compile-time
upload host and the site's canonical URLs were both unpinned before the resolver landed.

A test-only JVM guard SHALL remain, and SHALL assert the properties that generation does **not** already
make true:

- no artifact the app or backend reads declares the domain as a **hand-written host literal** rather than
  deriving it from the resolved deployment;
- the app entitlement references the build setting rather than hard-coding a host;
- the extension claims no associated domain, and the retired custom URL scheme stays retired;
- the guard fails **loudly rather than vacuously** — if a file it inspects has moved, been renamed, or no
  longer contains the marker it expects, it SHALL fail rather than silently scanning nothing.

The guard SHALL NOT assert that a generated artifact matches the deployment it derives from. That
assertion cannot fail: the build runs the deployment resolver before the guard reads its output, so the
artifact is regenerated from the same resolution moments earlier and staleness is eliminated before the
check. A check that cannot fail is not a guard, and stating it as one overstates what the build proves.

The guard exists because drift here is **silent**. A stale entitlement or a mismatched AASA does not
raise, log, or fail a build: iOS simply declines to match the link, and every event link opens a browser
instead of the app — indistinguishable, from the outside, from a user who has not installed SnapSync.

#### Scenario: A hand-written host literal reappears
- **WHEN** any inspected artifact declares the domain as a literal rather than deriving it from the
  resolved deployment
- **THEN** the guard fails, naming the artifact and the literal

#### Scenario: Every copy is constructed, not restated
- **WHEN** the entitlement's `applinks:` domain, the app's `LINK_ORIGIN`, the served AASA's domain, the
  compile-time upload host, and the site's canonical URLs are inspected
- **THEN** each is derived from the resolved deployment, and none is a hand-written host literal

#### Scenario: The guard is not vacuous
- **WHEN** a file the guard inspects is absent, renamed, or no longer contains the marker it expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: Agreeing artifacts pass
- **WHEN** no inspected artifact carries a host literal and every marker is present
- **THEN** the guard passes

### Requirement: The migration's laws are permanent gates

Every law the migration beacon measured SHALL be enforced permanently in `:test:architecture`
under `./gradlew build`. (The module-architecture migration is complete; its beacon — the
detached burn-down module and the non-required `verify` job — measured zero on every law at the
finale and was deleted, per its own contract.) The promoted gates:

- **Module-set equality**: the `settings.gradle.kts` include set SHALL equal the union of the
  groups `module-architecture` enumerates, **derived from that spec's text at test runtime** — the
  gate SHALL NOT hold its own copy of the set. Adding or deleting a module fails until the spec is
  consciously amended with the group the module joins and the argument for that group. The failure
  SHALL name all three groups and what each requires, because "must withhold a dependency" is the
  right instruction for only one of them. The gate SHALL keep a non-vacuity twin per group.
- **Deletion ledger**: the migration's retired dead weight SHALL stay dead — the zxing and
  kotlincrypto catalog entries, the `capability/` tree, the device-manifest accumulator, the
  Arrow/ArrowLevel duplicate enum, and any second `*Enrollment` uploader. Resurrection is not
  forbidden forever; it is forbidden **silently** — bringing an item back means deleting its guard row
  in the same commit, with the argument in the PR. The guard SHALL assemble its patterns so its own
  source never matches them (the beacon's self-match lesson).

  The ledger SHALL NOT carry rows that retire a declaration for being **single-implementation
  interface ceremony**. That judgement was overturned when `LeaveNotifier` was brought back: a
  **port** is not an interface justified by a second implementation, it is the declared boundary
  where the core stops and an external system begins, and with the interface gone the composition
  carried the crossing as an opaque closure instead — invisible to every gate that reads types. Rows
  resting on the overturned judgement (`LedgerReader`, `LoggingPushReceiver`, `EventMetadataSource`)
  are retired from the ledger, because a ledger row that would block a correct change is worse than
  no row.
- **Shells** and **zones** are gated by their own standing requirements (the shell gates; the zone
  gates), now all armed and gating.

The mixed port/impl file rule is retired as a standing gate: with `ports/` a module that withholds Ktor
and SQLDelight, a port interface declared beside a technology import does not compile, and an interface
declared inside an adapter beside its own implementation is ordinary Kotlin rather than a defect.

The flow-transcriber generation failure (capability `architecture-diagrams`) SHALL likewise be a
hard gate: an untranscribable flow fails `architectureDiagrams` and the freshness test under the
canonical build.

#### Scenario: A module is added without amending the target list
- **WHEN** a new `include(...)` lands in `settings.gradle.kts` with no matching edit to the
  module-set gate's target list
- **THEN** the gate fails, naming the drift and the withholding bar a new module must clear

#### Scenario: Retired dead weight grows back
- **WHEN** a retired declaration (or catalog entry, or the `capability/` tree) reappears anywhere
  in the scanned roots
- **THEN** the deletion-ledger gate fails, naming the resurrected item and its rationale

#### Scenario: A retired name comes back as a port
- **WHEN** a declaration the ledger retired is reintroduced because the judgement that retired it is
  overturned
- **THEN** its ledger row is deleted in the same commit and the reversal is argued in the change's
  decision record, so the resurrection is loud rather than silent

#### Scenario: A port interface is written beside its technology implementation
- **WHEN** an interface is declared in `:domain:ports` alongside a Ktor or SQLDelight import
- **THEN** the import does not resolve, because the module withholds those dependencies

### Requirement: The platform-vocabulary pin


For every Apple enumeration an adapter decodes with a **fallback arm**, `:test:architecture` SHALL
pin the complete set of constants that enumeration declares, with their exact values, and SHALL fail
the build on any delta — a constant added, removed, renamed, or re-valued.

The source of truth SHALL be the **Kotlin/Native platform klib** the build resolves, not a vendor
header, a documentation page, or a device observation. That klib is the compiler's own input, so it
states exactly what our source sees; reading it needs no Mac and no Xcode, and the pin therefore runs
on Linux inside `./gradlew build` rather than on macOS CI. Because the platform klibs ship prebuilt
inside the Kotlin/Native distribution, the declared set changes when the **Kotlin/Native version**
changes — so the pin fails on the version-bump pull request that introduces the new vocabulary, which
is the earliest moment the change is visible to anyone.

This is the inward mirror of "Runtime identity is pinned": that requirement pins literals **we** hold
which the OS also holds, so we cannot strand devices in the field; this one pins literals **Apple**
holds which we encode, so Apple cannot widen a vocabulary we decode without saying so. It is also the
first guard whose input is the toolchain's platform metadata rather than this repository's own source,
and it is aimed squarely at a blindness no lexical scan can cover: a decoder over another system's
values carries no import and no distinctive token, so scanning source cannot see one and SHALL NOT be
assumed to catch it. (That blindness was previously stated by "The platform-identifier gate", retired in
this change once the JVM target was measured to reject Apple type references outright.)

A fallback arm is unavoidable in the decoders themselves — cinterop renders `NS_ENUM` as a type alias
over `NSInteger` plus loose constants, never a Kotlin `enum class`, so a `when` over one can never be
compiler-exhaustive. The pin is what supplies the exhaustiveness the language cannot.

The pinned inventory (this list is the contract of record; adding, removing, or re-valuing an entry
is a spec change to this requirement, deliberately):

- **`PHAssetResourceUploadJobState`** — `Registered` = 1, `Pending` = 2, `Failed` = 3,
  `Succeeded` = 4, `Cancelled` = 5. Decoded by the PhotoKit upload adapter's job-state table
  (capability `ios-photokit-upload`). An untaught state reaching the terminal-job drain is adjudicated
  as a retry-spent failure, which is safe but wrong.
- **`PHAssetResourceType`** — decoded by `photoKitResourceRole` (capability `gallery-status`), whose
  fallback **drops** the resource. An untaught original resource type is therefore a photo that never
  uploads, with no error anywhere — the silent-failure class this project treats as the worst outcome.

**What it does not cover, stated so a green run is not over-read:** the pin describes what the SDK
*declares*, not what the OS *returns*. A device may hand back a value no header carries, and the klib
reflects the SDK the Kotlin/Native distribution was built against rather than the iOS version on the
device. A green pin is therefore not a promise that a decoder's fallback arm is unreachable, and the
fallback arms SHALL remain load-bearing and SHALL keep handling an unrecognised value safely. Only a
device measurement settles what the runtime actually produces.

Decision record: `changes/archive/2026-08-09-extract-upload-platform-mappings`.

#### Scenario: A toolchain bump widens a pinned enumeration

- **WHEN** a Kotlin/Native version bump ships a platform klib in which a pinned enumeration declares a
  constant the inventory does not carry
- **THEN** `./gradlew build` fails on that pull request, naming the enumeration and the new constant
  with its value, so the decoder is taught before the bump merges

#### Scenario: A pinned constant changes value or disappears

- **WHEN** a pinned constant is removed, renamed, or bound to a different value in the resolved
  platform klib
- **THEN** the build fails naming the affected entry, rather than leaving a decoder silently mapping a
  value that no longer means what it meant

#### Scenario: The pin runs without a Mac

- **WHEN** the guard executes on Linux, where no Xcode and no Apple SDK is present
- **THEN** it resolves the platform klib from the Kotlin/Native distribution the build already
  provisions and completes normally, so the pin gates the required build rather than macOS CI alone

#### Scenario: An undeclared runtime value is out of scope

- **WHEN** a device returns a value for a pinned enumeration that appears in no SDK declaration
- **THEN** the pin is silent by construction, and the decoder's fallback arm handles the value safely —
  the guard's green result is never read as evidence that such a value cannot occur

## REMOVED Requirements

### Requirement: Adapter constructors perform no blocking work

**Reason**: The guard enforcing this was measured to be **inert**. Its forbidden-form list carried
`"contentsOfFile"` while the API in use is `NSData.dataWithContentsOfFile`, and `String.contains` is
case-sensitive, so it never matched. Emptying its grandfather list and forcing a rerun passed; repo-wide,
`contentsOfFile` and `contentsAtPath` have zero occurrences. The requirement has therefore been ungated
since it was written, and this capability may not carry a SHALL that nothing enforces.

**Migration**: The invariant is real and now rests on review: construction happens on whichever thread
assembles the graph, so a blocking call in a constructor races the first render. Re-introducing a working
guard is a separate change, which must carry a mutation test proving it fires before it is relied upon.

### Requirement: Nullable port seams carry a stated consequence

**Reason**: The guard scanned the 40 lines above a nullable seam for an `Absence:` marker without checking
the marker belonged to that declaration. Measured in `ports/AlbumSeams.kt` (72 lines, marker at line 64): a
new nullable seam appended to the file passed by inheriting a neighbouring declaration's verdict, and only
failed once moved more than 40 lines away. Since zone files are small, most new seams passed unguarded.
Repairing the anchoring required keeping a second guard alive solely as its dependency, and even repaired
it enforces only that prose exists, never that the reason is correct or complete.

**Migration**: Distinguishing "nothing" from "could not tell" remains a law of `module-architecture`
("Absence is never silent") and a review concern. Where the distinction is load-bearing, express it in the
type — `ConfigRead`, `SecureStoreRead` and `JoinLoad` all do — rather than in a comment.

### Requirement: A KDoc block is never silently dropped

**Reason**: Its only remaining value was supporting the nullable-seam guard's repaired anchoring, and that
guard is retired. Judged alone it prevents documentation loss, which ships correct behaviour and is
recovered by an edit.

**Migration**: None. Kotlin binds only the last KDoc block before a declaration and warns about nothing;
this is now unguarded and stated as an accepted exposure in the change's design record.

### Requirement: The platform-identifier gate

**Reason**: The compiler already enforces the half that matters. Measured:
`platform.Foundation.NSError` in `commonMain` fails `compileKotlinJvm` with `Unresolved reference
'platform'`, because `:domain` carries a JVM target. Only Apple knowledge encoded as **string literals**
survives compilation — verified, `domain == "NSCocoaErrorDomain" && code == 4L` compiles clean — and the
gate's `NS|PH|UI|AV` type-prefix forms were redundant with the compiler throughout.

**Migration**: The residual shape — Apple knowledge as literals in `:domain` — is a review concern.
`PhotoKitAbiContainmentTest` continues to cover the PhotoKit constant case specifically. Note that
`:domain`'s `jvm()` target is now load-bearing for platform purity.

### Requirement: Platform entry points are derived and logged before deciding

**Reason**: The guard enforces diagnosability rather than behaviour. An unlogged entry point ships correct
behaviour; what is lost is the ability to tell, from a device log, whether the platform never called or the
entry point declined silently.

**Migration**: The logging obligation itself remains in `diagnostic-logging`, maintained by review rather
than by a gate. The consequence is stated as an accepted exposure: a defect of the shape that motivated
this requirement will again be undiagnosable from a dump.

### Requirement: The fake-honesty gate

**Reason**: Replaced by structure. With `:adapter:generic:fake`'s classes declared `internal` and exported
through factories returning the port type, `:test:world` cannot reach a lever across the module boundary at
all — honesty becomes unrepresentable rather than forbidden. The text gate had also missed a real lever:
`val files: MutableSet<String>` in `InMemoryStagedBytes` is public mutable state its `var`-matching regex
could not see.

**Migration**: Make every fake class `internal` and export a port-typed factory; fakes needing an injected
state cell take it as a factory parameter.

### Requirement: The zone gates exist before their zones, pending and self-arming

**Reason**: The migration that motivated the self-arming posture is complete and no zone is pending. The
mechanism is now pure downside: a gate whose scope directory is absent prints `PENDING` and returns green,
so renaming a zone directory silently disarms it — a gate that fails open, which is the one failure mode
these tests may not have.

**Migration**: The replacement rule is stated in "The zone gates": a missing or renamed scope SHALL fail
the build, never report itself pending.

### Requirement: The control channel's trigger coverage is derived, never hand-enumerated

**Reason**: The control channel is contained at **compile time** (`-Psnapsync.rig=true`); a production
build contains none of it. It therefore cannot produce a field defect, and guarding it contradicts the
containment law that makes it safe. Stale trigger coverage is a tooling gap an operator notices the moment
a trigger they want is missing.

**Migration**: None. `:test:rig` remains contained by compilation, and its hook remains shell source for the
shell gates.

### Requirement: A dev/test control channel binds the loopback address only

**Reason**: As above — the channel ships in no production build, so a widened bind exposes a development
device rather than a user. The loopback constant remains the correct implementation; it is no longer gated.

**Migration**: None. Keep the loopback bind; a widening is a review concern on dev tooling.

### Requirement: The OS-receipt expiry line is pinned

**Reason**: As above — the receipt is a development-channel surface contained at compile time. A missing
expiry line misleads an operator during on-device verification rather than a user.

**Migration**: None.
