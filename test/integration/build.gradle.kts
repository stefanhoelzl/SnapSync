plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
}
// NOT INSTRUMENTED (capability `coverage-bounds`, "Coverage is measured over unit tests only"):
// the seam-to-UI-state integration surface. It composes the real core over the whole graph,
// so counting it would let a thick integration suite stand in for a thin unit suite.
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


// Test-only seam ↔ UI-state integration module (testing rule 3), now spanning the REAL upload/download
// execution edge via `:test:world`. Assembles the real `engine → status → presentation` stack over the
// world and asserts `UiState` AND world outcomes (objects landed, ledger COMPLETED, foreign photos
// imported). commonTest only — it exists so the tests may cross the `engine → presentation` boundary
// production forbids. Runs on JVM + `iosSimulatorArm64`.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies {
            implementation(project(":domain"))
            implementation(project(":test:world"))
            implementation(project(":ui:presentation"))
            // The real Ktor clients some tests drive (HttpEventDirectory, KtorPushHttpClient) moved
            // to the adapter layer at migration step 4.
            implementation(project(":adapter:generic:app"))
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.orbit.test)
        }
    }
}
