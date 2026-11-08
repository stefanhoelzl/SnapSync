// `:test:feature` (`docs/testing.md`, "Service-backed suites compose real services over port mocks"): the suites that
// build real services and layers over the ports' in-memory mocks — a compile path no `:domain:*` module's own test
// source set has. The features it was named for are gone (4.10); what stays here is what needs the mocks and a service.
//
// Test-only: no main sources, never linked into anything. Targets mirror `:adapter:generic:mock` (jvm +
// iosSimulatorArm64 + android) — the tests run on the JVM only, as the layers' own do (`docs/testing.md`, "Where
// each test runs").
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`snapsync.android`), mirroring the mocks'. Its `commonTest` is not run on the emulator.
    id("snapsync.android")
    // Instrumented: its tests also cover the zones whose entries in the root build file's `coverage { }` name it
    // (`:domain:services` among them). It holds no class of its own to measure.
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
        commonTest.dependencies {
            implementation(project(":domain:domain"))
            implementation(project(":domain:format"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:data"))
            // The status total's use case, which the cross-consumer ceiling fixture asks like every other consumer.
            implementation(project(":domain:application"))
            // The runtime a feature's outliving work runs under (`support/Runtime.kt`), and the entry points' tests.
            implementation(project(":domain:scheduling"))
            implementation(project(":domain:inbound"))
            implementation(project(":adapter:generic:mock"))
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
        }
    }
}
