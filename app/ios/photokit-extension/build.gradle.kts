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
            // The relocated, JVM-covered upload orchestration (UploadCycle + the UploadJobPlatform seam
            // + DiscoveryStore + UploadConfig). The iOS adapters below (IosUploadJobPlatform,
            // IosDiscoveryStore) implement its seams and UploadExtensionRoot composes its UploadCycle.
            implementation(project(":capability:upload"))
            // Shared iOS PhotoKit discovery + request/token support (IosDiscovery, IosDiscoveryStore),
            // also consumed by the app-driven URLSession tier. Keeps the change-token walk out of the
            // platform-free :capability:upload while sharing it across both adapters.
            implementation(project(":app:ios:photokit-discovery"))
            // The app-written download store, opened READ-ONLY here for the suppression projection
            // (capability `download-store`): discovery drops assets this device downloaded + imported
            // so they are never re-uploaded (the echo). The extension never writes this store.
            implementation(project(":domain:download-store"))
            // The shared library resource-enumeration seam (one upload-key/version derivation for both
            // the producer and the re-join seed); the producer delegates its enumeration to it.
            implementation(project(":domain:gallery"))
            // The real upload provider + the runtime config seam: the extension assembles the edge
            // URL from the Keychain event id (:capability:config), the compile-time host, and the
            // App-Group device id, building the request with EdgeUploadRequestProvider
            // (:capability:upload-url) — no signing, no credential.
            implementation(project(":capability:upload-url"))
            implementation(project(":capability:config"))
            // The stable per-install device id (shared Keychain): the `/files/<deviceId>/` byte-store
            // partition and the per-event device-manifest key (capability `device-identity`).
            implementation(project(":capability:device-id"))
            // The re-join reconciliation now runs in the extension (capability
            // `event-rejoin-reconciliation`): the ExtensionReconciler seeds already-stored photos as
            // COMPLETED before the producer runs, fetching the event's complete-asset listing via the
            // EventFilesSource / HttpEventFilesSource (Darwin client supplied by the rejoin iosMain).
            implementation(project(":capability:rejoin"))
            // Ktor core for the synchronous in-cycle device.json PUT (the Darwin client comes from
            // :capability:rejoin's iosMain); the byte uploads are the OS's job, not Ktor's.
            implementation(libs.ktor.client.core)
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
