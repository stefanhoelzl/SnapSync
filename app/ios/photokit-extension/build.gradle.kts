plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Lean background-upload extension core. Depends only on :domain:engine (no Compose/UI), so the
    // extension binary stays small. Each target exposes a static framework "SnapSyncUploadKit" that
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
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
