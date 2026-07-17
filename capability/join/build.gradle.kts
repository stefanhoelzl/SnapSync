plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
            // Seams the join use-case orchestrates: the persisted config and the device-manifest
            // type+uploader (an empty manifest is the register-only enrollment).
            implementation(project(":domain:gallery"))
            // The HTTP client is injected (Darwin on iOS, MockEngine in tests) — only the core client
            // + JSON are needed here, mirroring :capability:event-creation-ui.
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
