plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Lean background-upload extension core: :domain + the extension-safe adapters, no
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

    // Sentry test-link (capability `crash-reporting`): this module's simulator TEST binary links
    // :adapter:ios:ext-safe and therefore Sentry symbols; reuse the Sentry-Dynamic framework that
    // module provisions (see its build script for why the DYNAMIC variant) — same -F for the link,
    // same -rpath for the simulator-process load.
    val sentrySimulatorSlice = project(":adapter:ios:ext-safe").layout.buildDirectory
        .dir("sentry-cocoa/${libs.versions.sentry.cocoa.get()}/Sentry-Dynamic.xcframework/ios-arm64_x86_64-simulator")
        .get().asFile.toString()
    iosSimulatorArm64().binaries.all {
        if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
            linkTaskProvider.configure { dependsOn(":adapter:ios:ext-safe:provisionSentryCocoa") }
            linkerOpts("-F$sentrySimulatorSlice", "-rpath", sentrySimulatorSlice)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            // The relocated, JVM-covered upload orchestration (UploadCycle + the BackgroundTransfer seam
            // + UploadConfig). The extension-safe iOS adapters implement its seams and
            // UploadExtensionRoot composes its UploadCycle.
            // The extension framework's contents are decided by linkage (migration step 4): the
            // extension-safe iOS adapters (:adapter:ios:ext-safe — the discovery walk + cursor store,
            // the ledger/download-store native drivers, the Keychain config/attest/device-id/album
            // stores, the Darwin client, the joined-event marker, the device-log writers) over the
            // platform-free technology impls (:adapter:generic:app — the SQLDelight stores + Ktor clients).
            implementation(project(":adapter:generic:app"))
            implementation(project(":adapter:ios:ext-safe"))
            // The event-notify sender (capability `upload-completion-notify`): a bodyless POST to
            // Ktor core for the synchronous in-cycle device.json PUT (the Darwin client comes from
            // :adapter:ios:ext-safe); the byte uploads are the OS's job, not Ktor's.
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
