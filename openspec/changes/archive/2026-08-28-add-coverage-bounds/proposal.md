## Why

Nothing in this repository measures test coverage. `./gradlew build` compiles every target, runs
every JVM test and every architecture guard, and holds every Kotlin source under a complexity
ceiling — but a class added with no test at all passes all of it silently.

That is not hypothetical. A first measurement of the tree found **four classes in
`:adapter:generic:app` with no test of their own** — `HttpAttestClient` (500 instructions, 0%
covered, and the client behind capability `device-attestation`), `HttpEnrollment`,
`HttpDeviceFilesSource` and `SystemTime` — while their package-mates `HttpLeaveNotifier` and
`HttpEventDirectory` each have one. Three of the five `flow/` trigger flows have no unit test, and
two of them — `Background` and `DownloadBackstop` — are executed by **no test in the repository at
all**, on any tier. None of this was visible to any gate.

The intent is not a vanity number. It is a **debt register with a destination**: bound every scope at
what it already measures so the gate lands green, then ratchet those bounds upward until the tree is
fully covered. The bound that matters is the one naming the worst package in a module, because that
is the pointer at the next thing to fix.

## What Changes

- Add `kotlinx-kover` to the build and attach coverage verification to `check`, so `./gradlew build`
  — the canonical check — fails when a scope falls below its bound.
- Bound **`INSTRUCTION` and `BRANCH`** on each measured module, and additionally bound the **worst
  package** in each module (`groupBy = PACKAGE`), which is the debt pointer.
- Seed every bound at the value the tree measures today, so the gate lands green with no code
  changes, and state the contract that a bound may thereafter only rise.
- **Measure unit tests only.** `:test:integration`, `:test:world`, `:test:architecture` and
  `:tools:diagrams` are not instrumented and contribute no coverage, so a broad harness suite cannot
  stand in for a thin unit suite.
- Distinguish **instrumented** (a module whose tests contribute coverage) from **bounded** (a module
  whose classes are measured). `:adapter:generic:fake` is instrumented but not bounded: its tests are
  `:domain`'s feature tests, its fakes are test equipment.
- Exclude `:domain`'s `compose/` zone from the bounded set, citing `module-architecture`'s
  **"One shared composition"**. A composition root is reachable only by composing it, so no unit test
  can cover it by construction; it is out of the measurable set rather than debt to be paid.
- State three limitations in the specification rather than leaving them to be discovered: Kover
  measures **JVM targets only**, so `:adapter:ios:ext-safe` and `:adapter:ios:app-only` (5,833
  production LOC, 182 tests) are invisible to every bound; the Compose compiler depresses `BRANCH`
  structurally; and Kover's integer `minValue` concedes up to 1% of a scope permanently.

## Capabilities

### New Capabilities

- `coverage-bounds`: a floor on test coverage for every measured scope, seeded at what the tree
  measures, permitted to move in one direction only — up — with the destination being full coverage.
  Owns which test tiers contribute, which modules are instrumented, which are bounded, how a bound is
  seeded and raised, and what the measurement is blind to.

### Modified Capabilities

- `architecture-diagrams`: the module dependency graph gains a stated rule about what it counts.
  Coverage crediting edges are declared on Kover's `kover` configuration, which the graph generator
  read as architectural dependencies — rendering `:domain → :adapter:generic:fake` and
  `:ui:components → :ui:screens`, every one pointing the opposite way to the real dependency. A new
  requirement excludes report-aggregation configurations and says where such an edge may be declared.

- `gallery-status`: the enumeration-failure requirement gains two statements the code now relies on —
  that the containment lives in the source rather than at a call site, and that **cancellation is not
  such a failure**, so it propagates and is not logged at `Error` severity. Without the second, the
  requirement's "SHALL NOT propagate" reads as covering a cancelled walk, which would mean swallowing
  it and posting a crash-report event for an ordinary teardown.

`testing-architecture` is **not** modified. It states where tests live and what they may reach; this
change states what is measured and against what floor. It cites that spec's tier names — **"Fake-driven feature
tests live in the fake module"**, **"The world hosts feature tests over the real stack"**, **"The
seam-to-UI-state integration surface"**, **"The app shells are wiring-only and untested"** — and
restates none of them. No requirement in it changes.

## Impact

**Build** — `gradle/libs.versions.toml` (the Kover plugin), the root `build.gradle.kts`, and the
`build.gradle.kts` of each instrumented or excluded module. `./gradlew build` gains a gating
verification task per bounded module.

**Diagram generators** — the module-graph model extraction in the root build script, and the
byte-identical renderer twin in `:tools:diagrams`, which must move together or the freshness test
fails by design. `architecture/modules.md` regenerates with a corrected header; no edge changes.

**Production code** — `OwnDeviceGalleryStatusSource` takes over containment of its own enumeration
failure and rethrows cancellation; `AppCore.refreshStatusSources` narrows its remaining `runCatching`
to the policy derivation. The coverage gate itself changes no Kotlin: its bounds are seeded at measured
values so it lands green.

**Modules bounded**: `:domain` (minus `compose/`), `:adapter:generic:app`, `:ui:presentation`,
`:ui:screens`, `:ui:components`.
**Instrumented but not bounded**: `:adapter:generic:fake`.
**Neither**: `:test:integration`, `:test:world`, `:test:architecture`, `:tools:diagrams`,
`:app:desktop`, `:test:harness-driver`, and every module without a JVM target.

**Debt this makes visible on day one**, recorded here so the seeded numbers are not mistaken for
health: `:adapter:generic:app` seeds with a package floor of **0** because `HttpAttestClient` has no
test; `:domain`'s package floor is **57**, naming `flow/`, where three of five trigger flows are
uncovered.

**Adjacent defect, not fixed here**: `openspec/specs/testing-architecture/spec.md` cites `ci-build`
twice (lines 24 and 221), a capability deleted by
`changes/archive/2026-08-27-make-api-tests-required`. Both citations dangle today. Correcting them is
a one-line edit that belongs to whoever next opens that spec.
