// `:adapter:ios:ext-safe` (`docs/architecture.md`): every iOS adapter the background-upload
// EXTENSION process links — placed by linkage, so the extension binary's contents are decided by
// this module boundary rather than by luck. The extension-safety text gate
// (`:test:architecture` ExtensionSafetyTest) forbids `platform.UIKit`/`platform.BackgroundTasks`
// anywhere under this module, because Kotlin/Native does not model `NS_EXTENSION_UNAVAILABLE`.
// This is also the Keychain containment module — the ONLY module that may touch `SecItem*`
// (`docs/architecture.md`; KeychainContainmentTest).

import app.snapsync.buildlogic.rigEnabled

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The rig source set and the embedded contract recordings.
    id("snapsync.ios-rig-sources")
}

// ---- Port contracts (`docs/architecture.md`) ---------------------------------------------------
//
// `src/rig/kotlin` holds the Keychain's recording/replaying seams and the entitled-device binding. Under
// `-Psnapsync.rig=true` it compiles into `iosMain` — the device app records through it — together with
// `:test:contracts`, and a build without the property contains neither (`docs/architecture.md`, "A
// build-time-only module is contained by compilation"). Otherwise it compiles into `iosTest`, where CI
// replays through it — so the recorder and the replayer are one file, and the device path is compile-checked
// on every build. `snapsync.ios-rig-sources` makes that switch, and embeds the committed recordings into the replay
// tests as Kotlin constants (so the simulator test executable needs no path into the repository).
contractRecordings {
    packageName = "app.snapsync.keychain.contract"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    iosSimulatorArm64().binaries.all {
        if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
            // `-lsqlite3`: the sqliter cinterop that backs `NativeSqliteDriver` declares this
            // itself, but only for a compilation that depends on the driver DIRECTLY — here it is
            // an `implementation` dep of commonMain, and the option does not reach the test
            // executable's own link. It goes unnoticed until a test actually opens a database
            // (`IosLedgerStoreTest`, `IosDownloadStoreTest`), at which point the link fails with a
            // wall of undefined `_sqlite3_*` symbols. The system library is present on every Apple
            // platform; this only tells the linker to use it.
            linkerOpts("-lsqlite3")
        }
    }
    sourceSets {
        iosMain.dependencies {
            // `IosCrypto`'s AES-GCM: CryptoKit, through cryptography-kotlin's Swift bridge.
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.cryptokit)
            if (rigEnabled) {
                implementation(project(":test:contracts"))
                implementation(project(":domain:services"))
            }
        }
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(project(":domain:feature"))
            // The Ktor core types darwinHttpClient() returns.
            api(project(":adapter:generic:app"))
            // (The interim :capability:album and :domain:gallery edges died at migration step 6:
            // the album seams now live in :domain ports/, albumMapSource in feature/album, and the
            // ResourceEnumerator composition in feature/upload — all reached via api(":domain").)
            implementation(libs.sqldelight.driver.native)
            implementation(libs.ktor.client.darwin)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The Keychain and App-Group store bindings of `SecureStoreContract` (capability
            // `docs/architecture.md`). Bound here because the seam and `AppGroupFileSecureStore` are `internal`.
            implementation(project(":test:contracts"))
            // The photo-library contracts bind the grant-aware composition production calls over the
            // PhotoKit adapters (`docs/architecture.md`, "A live binding binds the composition
            // production calls").
            implementation(project(":domain:compose"))
            // The storage services' SQLite behaviour on this target, measured over the real `IosDatabases`
            // (`docs/architecture.md`): a `:domain:*` build file names no module, so those tests live here.
            implementation(project(":domain:services"))
        }
    }
}
