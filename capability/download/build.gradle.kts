plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// MIGRATION STEP 4: the Ktor client (HttpEventUnionSource) moved to `:adapter:generic`; the iOS
// adapters (IosDownloadTransport, IosPhotoLibraryImporter) to `:adapter:ios:app-only`. What
// remains is the platform-free download controller + queue + status projection.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
            api(project(":domain:download-store"))
            api(project(":domain:status")) // the DownloadStatusSource seam this provides the store-backed impl of
            implementation(project(":domain:logging"))
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
