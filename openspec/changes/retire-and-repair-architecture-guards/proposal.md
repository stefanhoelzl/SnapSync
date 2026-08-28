## Why

All 44 guards in `:test:architecture` were challenged against one bar and, crucially, **mutation-tested
rather than read** — each was given the violation it claims to catch, run in isolation, and checked for a
red build. Reading alone proved unreliable: of the first six examined by inspection, three had a gap
between what they claimed and what they did, and only one was visible in the source.

The measurement found three guards not doing their job, one of which — `ConstructorBlockingTest`, the most
elaborate guard in the set — has **never caught anything**. It also found four guards whose scope is a
*remembered detector* rather than a derived one, and four places where structure (a module boundary, a
visibility modifier, the JVM compile) already does, or could do, what a text gate was written to do.

A guard that does not fire is worse than no guard: it is a green build that certifies nothing, and the
capability's own first requirement is that guards are executable and gate the build.

## What Changes

**Three measured defects**

- **`ConstructorBlockingTest` is inert.** Its `blockingForms` list contains `"contentsOfFile"` (lowercase
  `c`); the real API is `NSData.dataWithContentsOfFile` (capital `C`), and `String.contains` is
  case-sensitive. Emptying its grandfather list and forcing a rerun **passed**. Repo-wide,
  `contentsOfFile` and `contentsAtPath` have zero hits. **BREAKING** (removes a requirement).
- **`AbsenceIsNamedTest` has an unanchored look-behind.** It scans the 40 lines above a nullable seam for
  `Absence:` without checking the marker belongs to that declaration. Proven both ways in
  `ports/AlbumSeams.kt` (72 lines, marker at line 64): a probe appended at the end passed by inheriting a
  neighbour's verdict; the same probe after 45 blank lines failed. **BREAKING**.
- **`EventLinkDomainTest` has one tautological assertion.** `domain/build.gradle.kts:40` runs
  `scripts/resolve-deployment.py` during the build, regenerating `Deployment.xcconfig` immediately before
  the guard reads it, so the staleness assertion cannot fail. Its seven other assertions are live.

**Guards retired — 13 files, 1,663 LOC** (shared `ZoneGateSupport.kt` survives, shrunk: nine remaining guards use its helpers) (each removes or narrows a
requirement, because an ungated SHALL contradicts this capability):

- `RigControlChannelTest` (435) — the control channel is contained at **compile time**
  (`-Psnapsync.rig=true`) and ships in no build, so it cannot produce a field defect. Removes three
  requirements: trigger coverage, loopback-only bind, OS-receipt expiry pin. **BREAKING**
- `PlatformIdentifierTest` (280) — the JVM target already rejects Apple **type** references; only Apple
  knowledge encoded as string literals survives compilation. **BREAKING**
- `PlatformEntryLoggingTest` (198) — guards diagnosability, not behaviour. **BREAKING**
- `ConstructorBlockingTest` (156), `AbsenceIsNamedTest` (139), `StackedKDocTest` (133),
  `LawsDigestTest` (78), `FakeHonestyTest` (74), `MixedPortImplTest` (44), and the four zone gates
  (`ZonePresentationImports` 43, `ZoneFlow` 29, `ZoneModelPurity` 27, `ZonePorts` 27). **BREAKING**

**Structure replaces enforcement** — these are why several retirements are safe:

- **Split `:domain` into per-zone modules** (`:domain:model` ← `:domain:ports` ← `:domain:feature` ←
  `:domain:flow` ← `:domain:compose`). Only 18 `internal` declarations exist across the whole module (none
  in `ports/`, `flow/`, `compose/`), so the compiler can enforce the zone boundaries *totally* — including
  generated source and typealias re-exports the text gates cannot see. **BREAKING** to
  `module-architecture`: the new modules need a group and a justifying law.
- **Make `:adapter:generic:fake` classes `internal`, exported through port-typed factories.** `internal`
  is module-scoped, so `:test:world` cannot reach a lever at all. Retires `FakeHonestyTest` — which had
  itself missed a real lever (`val files: MutableSet<String>` in `InMemoryStagedBytes`, public mutable
  state its `var`-matching regex cannot see).
- **Delete the laws digest from `CLAUDE.md`** instead of guarding the duplicate; the laws move to
  `openspec/config.yaml`'s `context:` block. One authority, nothing to synchronise. Retires
  `LawsDigestTest`.

**Guards strengthened — 10 files**

- `RuntimeIdentityTest` — derive the pin inventory from this spec instead of holding a second copy.
- `ExtensionSafetyTest` — invert a 2-framework denylist into an ~8-framework allowlist (covering all ~200
  instead of 2, failing closed on novelty), matched on imports rather than raw text, with roots derived
  from the extension's dependency closure.
- `SwiftShellGuardTest` — its 4-keyword vocabulary misses a Swift ternary (measured), plus `as?`, `try?`,
  `while`.
- `MainLaneContainmentTest` — its Swift coverage does not exist (measured); add it.
- `KotlinShellGuardTest` — derive the shell roots from the root build file rather than duplicating them.
- `DeletionLedgerTest` — retire three "interface ceremony" rows encoding a judgement this repo already
  reversed.
- `EventLinkDomainTest`, `KeychainContainmentTest`, `PhotoKitAbiContainmentTest`,
  `ZoneFeatureBlindnessTest` — narrowed, re-scoped, or fail-open removed.

**21 guards keep their contract unchanged**, all sweep-verified.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `architecture-guards`: ~12 requirements removed (the retired guards) and ~6 altered (derived
  inventories, the inverted extension allowlist, the dropped tautological assertion). The zone-gate
  requirements collapse into the module split.
- `module-architecture`: the module enumeration gains the per-zone `:domain:*` modules, which need a group
  and a justifying law — the withholding law currently says a module justified by no law is "a package
  with a derived text gate instead". `ModuleSetTest` derives from this enumeration, so the build fails
  until it is updated.
- `diagnostic-logging`: "Uniform platform-invocation logging" loses its gate when `PlatformEntryLoggingTest`
  is retired, and must be removed or re-grounded rather than left ungated.
- `testing-architecture`: "Fake-driven feature tests live in the fake module" cites the retired
  fake-honesty gate as the reason `:adapter:generic:fake`'s `commonTest` sits outside the honesty
  surface. With the fakes `internal` behind port-typed factories, that reason becomes a property of the
  module boundary — the test source set is inside the module and so sees the implementations, while no
  other consumer can name them at all.

## Impact

**Code** — `test/architecture/` (13 files deleted, 10 modified); `domain/` split into five Gradle modules
plus `settings.gradle.kts` and every dependent module's build file; `adapter/generic/fake/` visibility and
factory surface; `CLAUDE.md` (laws digest removed, Runbooks section retained); `openspec/config.yaml`.

**Build** — `:test:architecture:test` must keep `CLAUDE.md` as a declared input (`RunbookSkillsTest` still
reads it). `detektTierOf` gains the new zone modules — this satisfies `complexity-budgets`' existing
"Coverage is derived, never remembered" requirement rather than changing it, so that capability needs no
delta spec.

**Tooling (mechanical, outside this change — the specs name no tooling)** — the six Konsist-based guards
migrate to Konture 0.8.4, and the Swift guards to semgrep. Konsist 0.17.3 last released December 2024 and
pulls `kotlin-compiler-embeddable:2.0.21` while this project is on Kotlin 2.4.0.

**Exposures this change accepts, stated deliberately**

- Constructor blocking is unguarded; the watchdog-kill risk is unmanaged rather than badly managed.
- The SNAPSYNC-3 class has no mechanical coverage now that both `AbsenceIsNamedTest` and
  `PlatformEntryLoggingTest` are gone — an entry point that decides and returns silently will again be
  undiagnosable from a dump.
- Apple knowledge encoded as string literals in `:domain` is a review concern.
- `:domain`'s `jvm()` target is now **load-bearing** for platform purity, and nothing guards it.
- KDoc stacking is unguarded; Kotlin binds only the last block and warns about nothing.
