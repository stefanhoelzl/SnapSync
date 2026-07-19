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
            implementation(project(":ui:screens"))
            implementation(project(":ui:presentation"))
            // The technology adapters, placed by linkage (migration step 4): the Ktor/SQLDelight
            // impls (:adapter:generic:app), the extension-safe iOS adapters (:adapter:ios:ext-safe —
            // Keychain stores, ledger/download drivers, discovery walk, log writers), and the
            // app-only iOS adapters (:adapter:ios:app-only — URLSession upload/download transports,
            // BGTaskScheduler, PhotoKit importer, permission).
            implementation(project(":adapter:generic:app"))
            implementation(project(":adapter:ios:ext-safe"))
            implementation(project(":adapter:ios:app-only"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
    }
}
