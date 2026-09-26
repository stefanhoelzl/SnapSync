import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

// `:adapter:generic:app` (`docs/architecture.md`): platform-free technology implementations of the
// `:domain` ports — the Ktor HTTP clients, the clock and time zone, and the JVM `Databases` adapter.
// Named for the technology, placed by linkage: generic code links everywhere (JVM harness, app,
// extension); its one platform source set, `jvmMain`, holds the SQLite driver only the JVM links (the
// iOS one is `:adapter:ios:ext-safe`'s). The stores themselves are `:domain:services`'. The `generic`
// prefix is the platform axis (a pure path grouping, no build file — same as `adapter/ios/`); the `app`
// leaf is SHIPPABILITY — this module links into the shipped app AND extension binaries (both
// processes, unlike `:adapter:ios:app-only`, whose leaf encodes PROCESS linkage). Packages keep their
// pre-migration names deliberately (decision D2 of `extract-adapter-modules`): every gate and diagram
// scopes by directory, and the pure-move diff is the review artifact; package normalization rides the
// feature-move steps.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // Coverage measurement (`docs/architecture.md`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(libs.coroutines.core)
            // HttpClient appears in every Ktor adapter's public constructor — consumers construct
            // their own engine (Darwin on device, MockEngine in the world/harness), so the type is API.
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            // Kermit for the stores' own diagnostics (the backfill sweep's positive on-device
            // evidence — photo-sharing). :domain keeps kermit `implementation`, so it is not inherited.
            implementation(libs.kermit)
            // TimeZone appears in SystemTimeZone's override of the `TimeZoneSource` port (step 9).
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // The JVM `Databases` adapter (`JdbcDatabases`): the one platform source set here, because the SQLDelight
        // driver is per platform. The iOS one is `:adapter:ios:ext-safe`'s.
        val jvmMain by getting {
            dependencies {
                implementation(libs.sqldelight.driver.sqlite)
            }
        }
        // The contract bindings (`docs/architecture.md`). The contracts live in `:test:contracts`' commonMain.
        // `:domain:services` is here, test-only, because the storage services' SQLite behaviour is measured over
        // this module's real `JdbcDatabases` — a `:domain:*` build file names no module, so they cannot run there.
        val jvmTest by getting {
            dependencies {
                implementation(project(":test:contracts"))
                implementation(project(":domain:services"))
                implementation(libs.sqldelight.driver.sqlite)
                // The backend contracts' live bindings talk to the real `api/` over a socket, through the one
                // process lifecycle `:test:edge` holds for every JVM consumer of the real backend.
                implementation(project(":test:edge"))
            }
        }
        val iosSimulatorArm64Test by getting {
            dependencies {
                implementation(project(":test:contracts"))
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

// Full failure messages in CI: the Kotlin/Native simulator runner otherwise prints a terse
// "AssertionError at null:-1" with no expected/actual (config carried over with the re-homed
// NativeLedgerStoreTest from `:domain:engine`).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// ---- The live edge (`docs/architecture.md`; the backend contracts' `Live` bindings) --------------
//
// `jvmTest` launches the REAL backend (`api/src/dev/serve.ts --ephemeral`) through `LiveEdge`, so `deno` on
// PATH is a prerequisite of `./gradlew build` (`docs/testing.md`, "The canonical check and its
// Kotlin/Native half"). Two things here keep that honest:
//
//  - the `local` deployment is RESOLVED first (`:test:edge`'s task): `serve.ts` imports the generated
//    rendering, and a missing one is a module-not-found rather than a clause failure anyone could read;
//  - the backend's sources are INPUTS of the test task. Without them a change touching only `api/` leaves this
//    task up-to-date, and the contracts that exist to catch exactly that change would never run against it.
val apiDir = rootProject.layout.projectDirectory.dir("api")
tasks.named<Test>("jvmTest") {
    dependsOn(":test:edge:resolveLocalDeployment")
    inputs.dir(apiDir.dir("src")).withPropertyName("liveEdgeSources")
    inputs.dir(apiDir.dir("migrations")).withPropertyName("liveEdgeMigrations")
    inputs.dir(rootProject.layout.projectDirectory.dir("deployments")).withPropertyName("liveEdgeDeployments")
    systemProperty("snapsync.apiDir", apiDir.asFile.absolutePath)
    systemProperty("snapsync.liveEdgeStore", layout.buildDirectory.dir("live-edge").get().asFile.absolutePath)
}

// ---- Coverage bounds (`docs/architecture.md`) ---------------------------------------------
//
// A FLOOR on this module's coverage, seeded at what the tree measured when the gate landed, and
// permitted to move in one direction only: UP. The destination is full coverage, and these numbers
// are the distance still to travel.
//
// RAISING a bound is ordinary work - do it in the change that makes it true. LOWERING one requires a
// stated forcing proof in that change's description, naming what makes the loss of coverage
// unavoidable. Nothing checks this: it is a ratchet carried by this paragraph and by review, and it
// is deliberately NOT a proof. `docs/architecture.md` carries the same contract at the opposite
// polarity - a ceiling that may only fall.
//
// TWO RULES, because they fail on different things. The aggregate catches a broad slide that leaves
// every package above the floor; the PACKAGE FLOOR - "no package here is worse than this" - catches
// one package rotting behind well-tested neighbours, which is the shape an untested class has.
//
// ENGINE: Kover's default, not JaCoCo. The two disagree by up to 26% on a single package's
// denominator, so every number below is engine-specific and switching engines means re-seeding all
// of them in that same change.
//
// Bounds are whole percentages (`minValue` is an `Int`), so each concedes up to 1% of its scope.
//
// THE PACKAGE FLOOR GATES. It was seeded at 0 because four production classes carried no test at all, rose
// 0 -> 75 once they were covered, and 75 -> 90 when the ten backend clients became one `HttpBackend` (phase 11c,
// measured 98.1% for `app.snapsync.http`): its optional-field bodies are read as JSON objects, so the generated
// serializers' unreachable halves that held the old `app.snapsync.join` at 75 are gone. 90 is `app.snapsync.databases`.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":adapter:generic:app aggregate") {
                    // 89 -> 96 with `HttpBackend` (measured 96.6%).
                    bound {
                        minValue = 96
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    // 56 -> 63 when the SQLDelight stores moved to `:domain:services` (measured 64.0%); 63 -> 78 with
                    // `HttpBackend` (measured 78.1%).
                    bound {
                        minValue = 78
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                // No per-package BRANCH rule: branch denominators per package run as low as 6 in this
                // tree, where a single uncovered arm moves the number by 17 points.
                rule(":adapter:generic:app package floor") {
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
