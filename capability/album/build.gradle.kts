plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // The platform-agnostic event-album orchestration (capability `event-album`): the AlbumCoordinator
    // + the AlbumManager / AlbumMapStore seams, tested on JVM. The iOS PhotoKit impls (IosAlbumManager,
    // IosAlbumMapStore) live in iosMain and are composed into BOTH the app and the extension frameworks
    // (creation runs app-side; adds run in whichever process runs the upload/import). No engine/gallery
    // deps — the coordinator takes eventId/name/rawLocalIds as params; the saveToAlbum gate lives in
    // callers.
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
            // `KeychainRead` — the input to the one-shot Keychain → App-Group migration of the album
            // map. `api`, because it appears in `albumMapSource`'s public signature.
            api(project(":domain:keychain"))
            // `normalizeAssetId` — the album-membership seam returns asset ids in the SAME normalized
            // shape the ledger and upload keys use, so the upload cycle can match them directly
            // (capability `photo-selection-policy`). :domain:gallery owns that vocabulary.
            implementation(project(":domain:gallery"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        // The album map now lives in the shared App-Group NSUserDefaults suite (like the discovery
        // cursor), not the Keychain: LEDGER_APP_GROUP is that suite's name.
        iosMain.dependencies {
            implementation(project(":domain:engine"))
        }
    }
}
