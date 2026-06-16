plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    sourceSets {
        commonMain.dependencies {
            // `api` on purpose: the presigner implements the engine's UploadRequestProvider and
            // returns its UploadRequest, so those types surface in this module's public API.
            api(project(":domain:engine"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.kotlincrypto.macs.hmac.sha2)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
