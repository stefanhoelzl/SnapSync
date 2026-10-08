// `:test:no-grant` (`docs/testing.md`, "Hosts"): the photo-library port contracts with no grant at all, on the
// emulator. Its own module because a grant is its APK's: a device-test APK is installed with every runtime permission
// it declares granted, and revoking one ends the process — so `:adapter:android`'s APK holds the full grant, this one
// declares none, and `:test:partial-grant`'s only the selection permission. Device tests only.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`docs/architecture.md`, "Zones inside the core") — this module's only one.
    id("snapsync.android")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        // On the main compilation, which the device tests inherit: the module has no code of its own.
        androidMain.dependencies {
            implementation(project(":adapter:android"))
            implementation(project(":test:contracts"))
            implementation(project(":domain:model"))
            implementation(project(":domain:ports"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            // The run's bindings call the platform's own test levers (the shell's grant).
            implementation(libs.androidx.test.runner)
        }
    }
}
