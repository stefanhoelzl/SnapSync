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
    // The `android` target (`docs/architecture.md`, "Zones inside the core"): the Android app links this module.
    id("snapsync.android")
    alias(libs.plugins.kotlin.serialization)
    // Coverage at ZERO (`docs/architecture.md`, "Coverage"; `snapsync.coverage-zero`): no instruction or branch may be
    // missed. Applied here rather than in a `subprojects {}` block so the instrumented set is readable per module.
    id("snapsync.coverage-zero")
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
    // `jvmTest` starts the real backend (`liveEdge.consumedBy`, below).
    id("snapsync.live-edge")
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
        named("jvmMain") {
            dependencies {
                implementation(libs.sqldelight.driver.sqlite)
            }
        }
        // The contract bindings (`docs/architecture.md`). The contracts live in `:test:contracts`' commonMain.
        // `:domain:services` is here, test-only, for the encrypted file format: its cipher (`FileCipher`) is held to
        // Tink's reference implementation, which only this JVM test source set links.
        named("jvmTest") {
            dependencies {
                implementation(project(":test:contracts"))
                implementation(project(":domain:services"))
                // The backend contracts' live bindings talk to the real `api/` over a socket, through the one
                // process lifecycle `:test:edge` holds for every JVM consumer of the real backend.
                implementation(project(":test:edge"))
                // The encrypted file format's reference implementation (`docs/architecture.md`).
                implementation(libs.tink)
                // The software key-attestation chain the Backend contract's mint is proved with
                // (`BackendAttestClauses`).
                implementation(libs.bouncycastle.pkix)
                // The wire fixture's client: the engine the live edge is reached with (`LiveEdge`).
                implementation(libs.ktor.client.cio)
            }
        }
        named("iosSimulatorArm64Test") {
            dependencies {
                implementation(project(":test:contracts"))
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

// ---- The live edge (`docs/architecture.md`; the backend contracts' `Live` bindings) --------------
//
// `jvmTest` launches the REAL backend (`api/src/dev/serve.ts --ephemeral`) through `LiveEdge`, so `deno` on
// PATH is a prerequisite of `./gradlew build` (`docs/testing.md`, "The canonical check and its
// Kotlin/Native half"). `snapsync.live-edge` keeps that honest: the `local` deployment is resolved first, and the
// backend's sources are inputs of the test task, so a change touching only `api/` re-runs the contracts that exist
// to catch it.
liveEdge.consumedBy(tasks.named<Test>("jvmTest"))

// The encrypted file format's reference vectors (`test/vectors/encrypted-file.json`), shared with `api/`'s tests: an
// input of the test task, so regenerating them re-runs the tests held to them.
tasks.named<Test>("jvmTest") {
    val vectors = rootProject.layout.projectDirectory.dir("test/vectors")
    inputs.dir(vectors).withPropertyName("encryptedFileVectors")
    systemProperty("snapsync.vectorsDir", vectors.asFile.absolutePath)
}
