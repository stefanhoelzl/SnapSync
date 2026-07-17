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
            api(project(":domain"))
            // The relocated, JVM-covered upload orchestration (UploadCycle + the BackgroundTransfer seam
            // + UploadConfig). The extension-safe iOS adapters implement its seams and
            // UploadExtensionRoot composes its UploadCycle.
            implementation(project(":capability:upload"))
            // The extension framework's contents are decided by linkage (migration step 4): the
            // extension-safe iOS adapters (:adapter:ios:ext-safe — the discovery walk + cursor store,
            // the ledger/download-store native drivers, the Keychain config/attest/device-id/album
            // stores, the Darwin client, the joined-event marker, the device-log writers) over the
            // platform-free technology impls (:adapter:generic — the SQLDelight stores + Ktor clients).
            implementation(project(":adapter:generic"))
            implementation(project(":adapter:ios:ext-safe"))
            // The shared library resource-enumeration seam (one upload-key/version derivation for both
            // the producer and the re-join seed); the producer delegates its enumeration to it.
            implementation(project(":domain:gallery"))
            implementation(project(":capability:album"))
            // The re-join reconciliation now runs in the extension (capability
            // `event-rejoin-reconciliation`): the ExtensionReconciler seeds already-stored photos as
            // COMPLETED before the producer runs, fetching the event's complete-asset listing via the
            // EventFilesSource / HttpEventFilesSource (Darwin client supplied by the rejoin iosMain).
            implementation(project(":capability:membership"))
            // The event-notify sender (capability `upload-completion-notify`): a bodyless POST to
            // /event/<id>/notify fired after a drained cycle that completed uploads, so co-contributors
            // are woken to download. Reuses the same Darwin client as the manifest PUT.
            implementation(project(":capability:push"))
            // Ktor core for the synchronous in-cycle device.json PUT (the Darwin client comes from
            // :capability:membership's iosMain); the byte uploads are the OS's job, not Ktor's.
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
