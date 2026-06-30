plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    // iOS-only app shell. Each target exposes a static framework named "SnapSyncKit" that the
    // iosApp/ Xcode project links via the embedAndSignAppleFrameworkForXcode build phase.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SnapSyncKit"
            // Static framework is the Compose-iOS norm: avoids dynamic-linking issues with the
            // bundled Skiko/Compose native libs.
            isStatic = true
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":domain:ui"))
            implementation(project(":domain:presentation"))
            implementation(project(":domain:status"))
            implementation(project(":domain:engine"))
            implementation(project(":domain:permission"))
            implementation(project(":domain:gallery"))
            implementation(project(":capability:config"))
            // The stable per-install device id (shared Keychain) the app's status lists by
            // (`/files/<deviceId>/`) — the SAME item the extension reads (capability `device-identity`).
            implementation(project(":capability:device-id"))
            // The re-join reconciliation: the list fetch + JoinEvent gate, and the join status seam
            // (re-exported by :capability:rejoin) wired into the container.
            implementation(project(":capability:rejoin"))
            implementation(project(":capability:download"))
            // The create-event flow: the HTTP creator + CreateEvent use-case and the creation status
            // seam wired into the container.
            implementation(project(":capability:event-creation-ui"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
    }
}
