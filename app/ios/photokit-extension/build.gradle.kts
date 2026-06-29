plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Lean background-upload extension core: :domain:engine + the upload-url/config capabilities, no
    // Compose/UI, so the extension binary stays small. Each target exposes a static framework
    // "SnapSyncUploadKit" that
    // the Xcode app-extension target links — separate from the app's "SnapSyncKit", so the two
    // process binaries never both statically pull :domain:engine into one image.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SnapSyncUploadKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:engine"))
            // The shared library resource-enumeration seam (one upload-key/version derivation for both
            // the producer and the re-join seed); the producer delegates its enumeration to it.
            implementation(project(":domain:gallery"))
            // The real upload provider + the runtime config seam: the extension assembles the edge
            // URL from the Keychain event id (:capability:config), the compile-time host, and the
            // App-Group device id, building the request with EdgeUploadRequestProvider
            // (:capability:upload-url) — no signing, no credential.
            implementation(project(":capability:upload-url"))
            implementation(project(":capability:config"))
            // The re-join reconciliation now runs in the extension (capability
            // `event-rejoin-reconciliation`): the ExtensionReconciler seeds already-stored photos as
            // COMPLETED before the producer runs, fetching the event's complete-asset listing via the
            // EventFilesSource / HttpEventFilesSource (Darwin client supplied by the rejoin iosMain).
            implementation(project(":capability:rejoin"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
