// `:adapter:ios:app-only` (`docs/architecture.md`): iOS adapters only the MAIN APP process
// links — placed by linkage. Two reference app-only OS surfaces outright (`BGTaskScheduler`);
// the others are app-process-bound by identity or need: `IosUrlSessionUploadPlatform` and
// `IosDownloadTransport` own background-`URLSession` ids the OS reattaches to the app process
// across relaunch (a second, extension-side claimant of an OS-held session identity must be
// structurally impossible), `IosPhotoLibraryImporter` is the download feature's import writer,
// and `PhotoLibraryPermission` requests authorization where the system sheet can present.
// Keeping them out of `:adapter:ios:ext-safe` keeps the extension binary lean and the
// extension-safety gate scoped to code the appex can actually contain.

import app.snapsync.buildlogic.rigEnabled

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The rig source set and the embedded contract recordings.
    id("snapsync.ios-rig-sources")
}

// ---- Port contracts (`docs/architecture.md`) ---------------------------------------------------
//
// `src/rig/kotlin` holds the simulator app's live bindings of the photo-library contracts: every PhotoKit adapter
// of both iOS adapter modules, run under the full grant only an app bundle can hold. They live here rather than
// beside each adapter because a rig directory compiles into its module's `iosTest` without the property, and one
// module's tests cannot see another's; this module sees both modules' adapters. Under `-Psnapsync.rig=true` the
// directory compiles into `iosMain`, together with `:test:contracts`, and a build without the property contains
// neither (`docs/architecture.md`, "A build-time-only module is contained by compilation"). Otherwise it compiles
// into `iosTest`, so the bindings are compile-checked on every build. `snapsync.ios-rig-sources` makes that switch,
// and embeds the committed recordings into this module's replay tests — the twin of `:adapter:ios:ext-safe`'s, which
// embeds them for the Keychain's replay. Each module needs its own: the generated map is `internal` to the test
// compilation it lands in.
contractRecordings {
    packageName = "app.snapsync.contract"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        iosMain.dependencies {
            if (rigEnabled) {
                implementation(project(":test:contracts"))
                implementation(project(":domain:compose"))
                implementation(project(":domain:services"))
            }
        }
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            // The shared PhotoKit discovery walk (IosDiscovery) the URLSession tier reuses.
            api(project(":adapter:ios:ext-safe"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            // The date formatting adapter's wall-clock values.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The contract bindings of this module's adapters (`docs/architecture.md`): the photo-library
            // contracts over the grant-aware composition production calls.
            implementation(project(":test:contracts"))
            implementation(project(":domain:compose"))
            // The transfer contracts record into the real ledger, which is a service over the `Databases` adapter.
            implementation(project(":domain:services"))
        }
    }
}
