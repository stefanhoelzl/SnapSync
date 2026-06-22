plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    // iOS targets so the provider compiles for the native app/extension. It is pure string-building
    // (no signing, no crypto, no network), so no platform code is needed.
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            // `api` on purpose: the provider implements the engine's UploadRequestProvider and
            // returns its UploadRequest, so those types surface in this module's public API.
            api(project(":domain:engine"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
