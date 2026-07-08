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
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
