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
            api(project(":domain"))
            implementation(project(":domain:ui"))
            implementation(project(":domain:presentation"))
            implementation(project(":domain:status"))
            implementation(project(":domain:logging"))
            // ProtectedDataGate/ProtectedDataAvailability (the ProtectedData seam keeps its old home
            // until migration step 12; the SecItem impls moved to :adapter:ios:ext-safe at step 4).
            implementation(project(":domain:keychain"))
            implementation(project(":domain:gallery"))
            // The technology adapters, placed by linkage (migration step 4): the Ktor/SQLDelight
            // impls (:adapter:generic), the extension-safe iOS adapters (:adapter:ios:ext-safe —
            // Keychain stores, ledger/download drivers, discovery walk, log writers), and the
            // app-only iOS adapters (:adapter:ios:app-only — URLSession upload/download transports,
            // BGTaskScheduler, PhotoKit importer, permission).
            implementation(project(":adapter:generic"))
            implementation(project(":adapter:ios:ext-safe"))
            implementation(project(":adapter:ios:app-only"))
            // The app-driven (iOS 18–26.0) upload tier's shared UploadCycle/pump/scheduler seam,
            // composed in the main app process on <26.1.
            implementation(project(":capability:upload"))
            implementation(project(":capability:album"))
            implementation(project(":capability:attest"))
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
