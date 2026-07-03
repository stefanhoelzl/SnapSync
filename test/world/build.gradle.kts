plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Shared test-infra: a controllable in-memory "world" the REAL platform-agnostic stack runs against
// (capability `harness-world-model`). Consumed by BOTH `:app:desktop` (the full-stack harness) and
// `:test:integration`. Targets `jvm()` + `iosSimulatorArm64` ONLY — it never links into a shipped
// framework, so no `iosArm64`; its self-tests run on both per testing rule 1. The fakes and
// composition helpers live in `commonMain` (reusable infra, following the `InMemory*`/`Mutable*`
// convention), including the Ktor `MockEngine` mini-edge — so `ktor.client.mock` is a `commonMain`
// dep here (acceptable for a test-only module that never enters a production classpath).
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            // `api` (not `implementation`): the world's whole purpose is to hand the REAL stack's types
            // to its consumers (`:app:desktop`, `:test:integration`) — they appear across the world's
            // public API (composition helpers, inspectable fakes), so they must leak transitively.
            api(project(":domain:engine"))
            api(project(":domain:gallery"))
            api(project(":domain:status"))
            api(project(":domain:permission"))
            api(project(":domain:download-store"))
            api(project(":capability:upload"))
            api(project(":capability:upload-url"))
            api(project(":capability:config"))
            api(project(":capability:rejoin"))
            api(project(":capability:download"))
            api(project(":capability:event-creation-ui"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
