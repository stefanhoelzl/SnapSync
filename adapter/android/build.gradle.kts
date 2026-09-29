// `:adapter:android` (`docs/architecture.md`): every Android adapter of the `:domain` ports — ONE module, not the
// iOS ext-safe / app-only / ui split. iOS splits because its upload extension is a second process with linkage limits
// of its own; on Android the workers, the push service and the activity all run in the app's one process, so there is
// no second binary for a linkage line to protect.
//
// Today it holds what the screen and its foreground life need — the two systems an Android build does not mock yet —
// and the platform log. Every other system's adapter arrives with the phase that needs it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`docs/architecture.md`, "Zones inside the core") — this module's only one.
    id("snapsync.android")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    sourceSets {
        androidMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            implementation(project(":domain:presentation"))
            implementation(project(":ui:screens"))
            implementation(project(":ui:components"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            // The process's foreground life (`ProcessLifecycleOwner`).
            implementation(libs.androidx.lifecycle.process)
        }
    }
}
