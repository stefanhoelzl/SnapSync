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
            api(libs.coroutines.core)
            // The resource-enumeration seam returns engine `Resource`s (the shared upload-key/version
            // derivation), so engine types appear in this module's public API.
            api(project(":domain:engine"))
            // The per-asset manifest model (capability `asset-manifest`) is JSON-serialized here.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
