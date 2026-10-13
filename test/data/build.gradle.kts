plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
}

// test-only (`docs/testing.md`, "The data layer's suites"): the data layer's repositories and the backend's protocol
// run over the ports' in-memory mocks — the real secure store, transport and integrity doubles — and are observed at
// the port. `:domain:data` cannot name a mock (no core module may), so its suites live here; their coverage is
// credited to it and to `:domain:application` (the root build file's `coverage { }`). jvm()+iosSimulatorArm64,
// commonTest only.

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies {
            implementation(project(":domain:data"))
            implementation(project(":domain:application"))
            implementation(project(":domain:domain"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:wire"))
            implementation(project(":domain:inbound"))
            implementation(project(":adapter:generic:mock"))
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // The structured log's severities, read back from what a suite records.
            implementation(libs.kermit)
        }
    }
}
