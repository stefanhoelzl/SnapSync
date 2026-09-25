import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // Coverage measurement (`docs/architecture.md`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

// Full failure messages in CI: the Kotlin/Native simulator runner otherwise prints a terse
// "AssertionError at null:-1" with no expected/actual.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// The core'"'"'s `ports` zone (`docs/architecture.md`, "The module set withholds; packages organize").
// The need-named I/O boundary. Speaks the domain vocabulary and nothing else.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            // The per-zone library allowlist (`docs/architecture.md`, "Core purity is closed by
            // default"): coroutines (StateFlow/Flow port shapes), serialization + datetime (the
            // config/manifest vocabulary and cutoff codecs), kermit (the engine's diagnostics).
            api(libs.coroutines.core)
            // The one SQLDelight surface a port carries: `Databases` opens with a schema and answers a driver.
            // Runtime interfaces only — no driver, no plugin, no generated code (those are `:domain:services`'
            // and the adapters').
            api(libs.sqldelight.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

// Coverage (`docs/architecture.md`). The report is filtered to this module's OWN classes, so a
// zone is measured on what it contains rather than on its neighbours' test suites. The crediting edge
// that lets `:adapter:generic:fake`'s tests count toward this module is declared in the ROOT build
// file, not here: `ModuleSetTest` asserts a `:domain:*` build file names no module at all, because
// that absence is the precondition for the platform-free compile error.
kover {
    reports {
        filters {
            includes {
                projects.add(":domain:ports")
            }
        }
    }
}

// Coverage bounds (`docs/architecture.md`). Each number below is a FLOOR that may only RISE:
// lowering one is a regression and needs a stated forcing proof in the PR. Nothing enforces that — it
// is a ratchet carried by this contract, exactly as `docs/architecture.md` carries its ceilings at the
// opposite polarity.
//
// Seeded from MEASUREMENT, never chosen: the number is what this module measured on the commit that
// set it. ENGINE: Kover's default, not JaCoCo — the two disagree by up to 26 points on a single
// package's denominator, so switching engines means re-seeding in that same change. Bounds are whole
// percentages (`minValue` is an `Int`), so each concedes up to one point of its scope.
//
// One package, so no floor rule: the aggregate IS the floor. This zone is the weakest of the four,
// and the zone split is what revealed it — inside the old single `:domain` aggregate it was
// averaged away. Much of the gap is the `Companion.None` inert port objects: a default that does
// nothing has little to execute.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":domain:ports aggregate") {
                    bound {
                        minValue = 83
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    // LOWERED 65 -> 57 by the storage-ports re-cut. Forcing proof: the zone's
                    // best-covered branchy code (`resolveOrMint`/`readExisting`, with its tests)
                    // MOVED to `:domain:services`, where it is covered and bounded at 89. Nothing
                    // here lost a test; the ratio fell because the covered code left. Raise it
                    // again as the remaining port-adjacent helpers re-home or gain tests.
                    bound {
                        minValue = 57
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
            }
        }
    }
}
