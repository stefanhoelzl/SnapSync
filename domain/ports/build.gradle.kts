import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // Coverage measurement (capability `coverage-bounds`). Applied here rather than in a
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

// The core'"'"'s `ports` zone (spec `module-architecture`, "The module set withholds; packages organize").
// The need-named I/O boundary. Speaks the domain vocabulary and nothing else.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            // The per-zone library allowlist (spec `module-architecture`, "Core purity is closed by
            // default"): coroutines (StateFlow/Flow port shapes), serialization + datetime (the
            // config/manifest vocabulary and cutoff codecs), kermit (the engine's diagnostics).
            api(libs.coroutines.core)
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

// Coverage (capability `coverage-bounds`). The report is filtered to this module's OWN classes, so a
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

// Coverage bounds (capability `coverage-bounds`). Each number below is a FLOOR that may only RISE:
// lowering one is a regression and needs a stated forcing proof in the PR. Nothing enforces that — it
// is a ratchet carried by this contract, exactly as `complexity-budgets` carries its ceilings at the
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
                    bound {
                        minValue = 65
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
            }
        }
    }
}
