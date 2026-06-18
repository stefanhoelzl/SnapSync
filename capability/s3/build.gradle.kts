plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    // iOS targets so S3Config and the pure presigner compile for the native app/extension (and so
    // :capability:config's iosMain can persist an S3Config to the Keychain). The presigner is pure
    // string-building + kotlincrypto (which supports Kotlin/Native), so no platform code is needed.
    iosArm64()
    iosSimulatorArm64()
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
