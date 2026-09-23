plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// PORT CONTRACTS (capability `port-contracts`): the contract mechanism and every port contract, as clause
// VALUES — one explicit list per port that every binding runs, on CI through a thin `@Test` and in-app on
// a device through the rig. A list rather than `@Test` methods because Kotlin/Native has no reflection:
// an in-app runner cannot discover test methods, and `kotlin.test` cannot skip one dynamically.
//
// CONTAINED, not support (`module-architecture`, "The module set withholds"): it links into the iOS app
// — and into `:adapter:ios:ext-safe`'s rig-gated source set — ONLY under `-Psnapsync.rig=true`. Its
// withholding argument: it is the only module whose MAIN source set depends on `kotlin-test`, so no
// production module's main code can assert.
//
// `iosArm64` because the device app links it; `jvm` + `iosSimulatorArm64` because every binding's test
// source set does. NOT INSTRUMENTED for coverage (`coverage-bounds`): this is test equipment.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            // `LedgerStoreContract` exercises the ledger through the one writer feature (`LedgerWriter`).
            implementation(project(":domain:feature"))
            api(kotlin("test"))
            api(libs.coroutines.test)
            implementation(libs.coroutines.core)
            // The backend contracts' setup (`Edge.kt`) enters states through the edge's public HTTP surface:
            // `HttpClient` is in `EdgeSetup`'s constructor, so it is API; the JSON is only read and built.
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            // `DiagnosticsReporterContract`'s stimulus for automatic capture: a log line through the same global
            // Kermit logger production code writes to, which is the seam the reporting adapter's `start` hooks.
            implementation(libs.kermit)
        }
        // kotlin-test's @Test on JVM comes from a framework artifact the Kotlin plugin attaches to TEST
        // compilations only; the bindings' JVM test tasks run JUnit 4.
        jvmMain.dependencies {
            implementation(kotlin("test-junit"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
