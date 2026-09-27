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
// Targets: jvm + iosSimulatorArm64. Mocks never link into a device framework, so there is no `iosArm64` to pay for
// (11h's launch-time mock mix brings it).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Coverage measurement (`docs/architecture.md`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
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
            api(libs.coroutines.core)
            // The Clock double answers a zone (`TimeFactories.kt`).
            implementation(libs.kotlinx.datetime)
            // `inMemoryDatabases()`: real SQLite, in memory — the platform's driver per target, below.
            api(libs.sqldelight.runtime)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        iosSimulatorArm64Main.dependencies {
            implementation(libs.sqldelight.driver.native)
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
            // The photo-library contracts bind the grant-aware composition production calls, over the fake
            // just as over the platform read (`docs/architecture.md`).
            implementation(project(":domain:compose"))
            // The storage services' fake-driven tests: the services over the storage mocks (`docs/testing.md`).
            implementation(project(":domain:services"))
            // The process services hand the root a Kermit writer to install (`ProcessServices.logWriters`).
            implementation(libs.kermit)
        }
    }
}
