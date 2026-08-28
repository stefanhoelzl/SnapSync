plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}
// NOT INSTRUMENTED (capability `coverage-bounds`, "Coverage is measured over unit tests only"):
// the tier `testing-architecture` calls "The world hosts feature tests over the real stack" -
// real features driven against a composed world, which is integration by any reading.
//
// `disabledForAll` - note the spelling, not `disableForAll` - means this module is not instrumented,
// its coverage data is omitted from every report, and its test tasks are not triggered by report
// generation.
kover {
    currentProject {
        instrumentation {
            disabledForAll = true
        }
    }
}


// Shared test-infra: a controllable in-memory "world" the REAL app graph runs against — since
// migration step 10 composed through the SAME `snapSyncApp`/`uploadCore` the device shells call,
// over `:adapter:generic:fake`'s honest doubles; the world adds the backend store, the mini-edge, and the
// operator levers/wrappers that rig them (capability `harness-world-model`). Consumed by BOTH
// `:app:desktop` (the full-stack harness) and `:test:integration`. Targets `jvm()` +
// `iosSimulatorArm64` ONLY — it never links into a shipped framework, so no `iosArm64`; its
// self-tests run on both per testing rule 1. `commonMain` also hosts the storage-seam CONTRACTS
// (`LedgerStoreContract`, `DownloadStoreContract`, re-homed from the deleted `:domain:engine` /
// `:domain:download-store` modules at step 10): a test source set cannot be depended on across
// modules, and this is the one test-infra `commonMain` every implementor's test source set can
// reach — which is why `kotlin("test")` is a commonMain dep here (test-only module, never on a
// production classpath; same acceptance as the `MockEngine` mini-edge below).
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            // `api` (not `implementation`): the world's whole purpose is to hand the REAL stack's types
            // to its consumers (`:app:desktop`, `:test:integration`) — they appear across the world's
            // public API (composition helpers, honest fakes, wrappers), so they must leak transitively.
            api(project(":adapter:generic:fake"))
            // The real Ktor clients the mini-edge serves (HttpDeviceFilesSource, HttpEventUnionSource,
            // HttpEventCreation, HttpEnrollment, HttpLeaveNotifier, HttpEventDirectory).
            api(project(":adapter:generic:app"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
            // The re-homed storage-seam contracts carry @Test scenarios (see the module note above).
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        // kotlin-test's @Test on JVM comes from a framework artifact; the Kotlin plugin attaches it
        // automatically to TEST compilations only, so a main-source-set contract names it explicitly.
        //
        // JUnit 4 specifically, because that is what the CONSUMERS of these contracts run on — this is
        // NOT the build-wide default, and the comment used to claim it was ("the framework every jvm
        // test task in this build runs on"). It isn't: `:test:architecture` and `:tools:diagrams` both
        // call `useJUnitPlatform()` (JUnit 5), and the Compose UI tests pull JUnit 4 separately via
        // `compose.desktop.uiTestJUnit4`. Match whichever framework the consuming task actually uses.
        jvmMain.dependencies {
            implementation(kotlin("test-junit"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
