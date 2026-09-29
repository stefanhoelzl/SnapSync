// `:test:launch-adapters` — THE LAUNCH-TIME ADAPTERS (`docs/testing.md`, "Launch-time adapters"): which systems a
// rig build of the iOS app runs as mocks, read once at every process start from the App Group, checked against the
// coherence rules, and turned into the ports each root composes over. Test equipment, and CONTAINED: `:test:rig` links
// it into the app and `:app:ios:extension` into its extension ONLY under `-Psnapsync.rig=true`, so a production build
// carries none of it (`MockContainmentTest`). Its own module rather than a package of `:adapter:generic:mock` because
// it is not a mock: it is the rig's loader of them — it reads files, decorates an entry port and holds the mocked
// device a process shares.
//
// Targets: jvm (where its tests run in `build`) + the two iOS targets its hooks compile for.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`docs/architecture.md`, "Zones inside the core"): the Android app links this module.
    id("snapsync.android")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64().binaries.all {
        // The mocks' in-memory SQLite runs SQLDelight's native driver, whose system library a test executable must
        // name itself (the trap `:adapter:generic:mock`'s build file documents).
        if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) linkerOpts("-lsqlite3")
    }
    sourceSets {
        commonMain.dependencies {
            // The mocked device and its state codec, and the ports bundle a root composes over.
            api(project(":adapter:generic:mock"))
            api(project(":domain:compose"))
            implementation(project(":domain:model"))
            implementation(project(":domain:ports"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
