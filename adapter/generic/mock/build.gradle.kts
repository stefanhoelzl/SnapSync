// `:adapter:generic:mock` (`docs/architecture.md`, `docs/testing.md` "Mocks"): ONE mock per `:domain` port — what
// the JVM root (`:app:jvm`), the world, the feature tests and every integration test stand on. An adapter named for
// its technology (in-memory, platform-free — hence the `generic` platform-axis prefix), placed by linkage: it links
// only into test equipment, never a shipped binary — which is what the `mock` SHIPPABILITY leaf records (vs sibling
// `:adapter:generic:app`, which ships in both processes).
//
// Each mock has three faces: its DURABLE state (what the real system keeps, which a relaunch does not touch), the
// PORT face a process is handed (an `internal` class, typed as the port), and a separate OPERATOR face (the levers
// and reads). Honesty is mechanical, not an adjective: the classes behind the port faces are all `internal` and
// every face is port-typed, so an app can reach nothing a port does not declare — the compiler says so.
//
// Targets: jvm + iosSimulatorArm64 + iosArm64 + android. The android target exists for the Android rig build alone,
// as iosArm64 does for the phone's; the same containment covers both. The device target exists for the launch-time
// adapters only (`docs/testing.md`, "Launch-time adapters"): a RIG build of the app on a phone links this module so
// a launch can hand some ports their mocks. A production build never links it — `:app:ios` and `:app:ios:extension`
// name it only under `-Psnapsync.rig=true`, and `MockContainmentTest` fails the build the day either names it outside
// that switch — so "mocks never link into a shipped framework" stays true of every binary that ships.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`docs/architecture.md`, "Zones inside the core"): the Android app links this module.
    id("snapsync.android")
    // The persisted mock state (`:test:launch-adapters`): each mocked system's durable state as JSON in the App Group.
    alias(libs.plugins.kotlin.serialization)
    // Coverage measurement (`docs/architecture.md`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64().binaries.all {
        // `-lsqlite3`: `inMemoryDatabases()` runs SQLDelight's native driver, whose sqliter cinterop declares the
        // system library only for a compilation that depends on the driver directly — not for this test executable's
        // own link (the trap `:adapter:ios:ext-safe`'s build file documents). The library is on every Apple platform.
        if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) linkerOpts("-lsqlite3")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(project(":domain:feature"))
            api(project(":domain:flow"))
            // A rig build's launch adapters hand a root back its `DevicePorts`, some swapped for mocks
            // (`:test:launch-adapters`).
            api(project(":domain:compose"))
            api(libs.coroutines.core)
            // The Clock double answers a zone (`TimeFactories.kt`).
            implementation(libs.kotlinx.datetime)
            // `inMemoryDatabases()`: real SQLite, in memory — the platform's driver per target, below.
            api(libs.sqldelight.runtime)
            // The launch-time adapters' persisted state (`MockState.kt`).
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        // The stay-behind tests that drive `:domain` subjects through these fakes (re-homed from the
        // deleted `:domain:gallery` / `:domain:download-store` / `:capability:attest` modules at
        // migration step 10; testing rule 1 — commonTest runs on JVM and the iOS simulator).
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // The fakes' own contract bindings (`docs/architecture.md`): only this module's test
            // source set can construct an `internal` fake in a chosen state.
            implementation(project(":test:contracts"))
            // The storage services' fake-driven tests: the services over the storage mocks (`docs/testing.md`).
            implementation(project(":domain:services"))
            // The process services hand the root a Kermit writer to install (`ProcessServices.logWriters`).
            implementation(libs.kermit)
        }
    }
}
