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
            implementation(project(":domain:logging"))
            implementation(project(":domain:engine"))
            implementation(project(":domain:permission"))
            implementation(project(":domain:gallery"))
            implementation(project(":capability:config"))
            // The app-driven (iOS 18–26.0) upload tier: the shared UploadCycle/pump/scheduler seam
            // (:capability:upload), the edge request provider (:capability:upload-url), the shared
            // PhotoKit discovery (:app:ios:photokit-discovery), and the URLSession adapters
            // (:app:ios:url-session-upload). Composed in the main app process on <26.1.
            implementation(project(":capability:upload"))
            implementation(project(":capability:upload-url"))
            implementation(project(":capability:album"))
            implementation(project(":app:ios:photokit-discovery"))
            implementation(project(":app:ios:url-session-upload"))
            // The stable per-install device id (shared Keychain) the app's status lists by
            // (`/devices/<deviceId>/files/`) — the SAME item the extension reads (capability `device-identity`).
            implementation(project(":capability:device-id"))
            implementation(project(":capability:join"))
            // The re-join reconciliation: the list fetch + JoinEvent gate, and the join status seam
            // (re-exported by :capability:membership) wired into the container.
            implementation(project(":capability:membership"))
            implementation(project(":capability:download"))
            // Push-notification registration + receive seams (capability `push-registration`): the
            // AppDelegate feeds the OS-delivered APNs token in, the collector PUTs devices/<id>/config.
            implementation(project(":capability:push"))
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
