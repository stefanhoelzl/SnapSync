plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Shared iOS PhotoKit discovery + upload-request support, consumed by BOTH upload tiers: the
    // ≥26.1 PhotoKit adapter (:app:ios:photokit-extension) and the 18–26.0 app-driven URLSession
    // adapter (:app:ios:url-session-upload). PhotoKit must stay OUT of the platform-free
    // :capability:upload, so this iosMain-only library is where the shared change-token walk
    // (IosDiscovery), the PUT request builder, the change-token archiver, and the App-Group cursor
    // store (IosDiscoveryStore) live. No jvm(), no framework — a klib dependency the two adapter
    // modules pull into their frameworks.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:engine"))
            implementation(project(":domain:gallery"))
            implementation(project(":capability:upload"))
            implementation(libs.kermit)
        }
    }
}
