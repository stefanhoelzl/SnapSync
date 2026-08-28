import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
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

// The core'"'"'s `feature` zone (spec `module-architecture`, "The module set withholds; packages organize").
// The rules. Features are mutually blind; they coordinate via one-writer durable state behind shared ports.
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
            implementation(project(":domain:ports"))
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
                projects.add(":domain:feature")
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
// Nine packages, so the floor matters: it catches one feature rotting behind well-tested neighbours,
// which is the shape an untested class has. It names `feature/push`, whose gap is the generated
// `<init>` of two decode-only DTOs — no test reaches it, so this floor is close to its ceiling.
//
// LOWERED 92 -> 90 by `device-speaks-v2`. A floor going the wrong way owes a forcing proof; here it is,
// and it is the sentence above coming true. That change DELETED `EventNotifier` from `feature/push` —
// the completion notify has no route on the versioned device API, because publishing the manifest IS
// the announcement. `EventNotifier` was well covered, so removing it shrank that package's denominator
// around a FIXED, unreachable gap, and the ratio fell to 90.68 with nothing having rotted: `LINE` is
// 23/23 and `METHOD` 12/12 in that package, and every remaining miss is INSTRUCTION-level inside
// kotlinx's synthetic deserialization constructors for two `private`, encode-only DTOs.
//
// No test can repay it. Those DTOs are `private` to `PushRegistration.kt` and are only ever ENCODED, so
// the generated decode path is unreachable without widening production visibility for a test — which
// would be a worse trade than this number. The honest alternatives were both worse: excluding the two
// classes hides a real gap behind a mechanism this module does not otherwise use, and rewriting the
// encoding to `buildJsonObject` is a change to `push-registration`'s wire path made by a change about
// the device API.
//
// ⚠️ The cost is real and belongs on the record: this weakens the guard for all nine packages, not just
// the one that moved. It should rise again the moment `feature/push` gains reachable covered code.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":domain:feature aggregate") {
                    bound {
                        minValue = 98
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    bound {
                        minValue = 91
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                // No per-package BRANCH rule: branch denominators per package run as low as 6 in this
                // tree, where a single uncovered arm moves the number by 17 points.
                rule(":domain:feature package floor") {
                    groupBy = GroupingEntityType.PACKAGE
                    bound {
                        minValue = 90
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                }
            }
        }
    }
}
