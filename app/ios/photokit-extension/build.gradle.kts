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
            // The real upload provider + the runtime config seam: the extension assembles the edge
            // URL from the Keychain event id (:capability:config), the compile-time host, and the
            // App-Group device id, building the request with EdgeUploadRequestProvider
            // (:capability:upload-url) — no signing, no credential.
            implementation(project(":capability:upload-url"))
            implementation(project(":capability:config"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
