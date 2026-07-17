plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // The app-driven (iOS 18–26.0) upload adapters: IosUrlSessionUploadPlatform (a background-URLSession
    // implementation of the :capability:upload BackgroundTransfer seam) and IosBackgroundScheduler (a
    // BGTaskScheduler-backed BackgroundScheduler). Runs in the MAIN APP process (no appex), so this is a
    // plain klib the app pulls into its SnapSyncKit framework — no framework block, no separate target.
    // Composes :capability:upload (the seam + pump/scheduler) and the shared PhotoKit discovery
    // (:app:ios:photokit-discovery) used identically by the ≥26.1 tier.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            implementation(project(":domain:logging"))
            implementation(project(":domain:engine"))
            implementation(project(":capability:upload"))
            implementation(project(":app:ios:photokit-discovery"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
    }
}
