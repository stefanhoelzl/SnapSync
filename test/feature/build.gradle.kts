// `:test:feature` (`docs/testing.md`, "Feature tests compose real services over port mocks"): the feature tests that
// need a service. Features see services only — the feature → ports edge is a compile boundary — so a test that builds
// a feature over a real service, and that service over the ports' in-memory mocks, cannot live in `:domain:feature`'s
// own test source set: that set compiles against exactly what feature does. Here it can: this module sees feature,
// services, ports and the mocks. A feature test that touches no port and no service stays in `:domain:feature`.
//
// Test-only: no main sources, never linked into anything. Targets mirror `:adapter:generic:fake` (jvm +
// iosSimulatorArm64) — the tests run on the JVM and on the simulator, as the feature module's own do.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Coverage measurement: its tests' coverage is credited to the zones they exercise from the root build file
    // (`:domain:feature` first). It declares no verify rule — it holds no class of its own to measure.
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies {
            implementation(project(":domain:model"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:services"))
            implementation(project(":domain:feature"))
            implementation(project(":adapter:generic:fake"))
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.kermit)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
        }
    }
}
