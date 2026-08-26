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

    // ---- The dev/test control channel (`:test:rig`), contained at COMPILE TIME ------------------
    //
    // `-Psnapsync.rig=true` adds BOTH the module and the source directory it contributes; without the
    // property it adds NEITHER, so a production build contains no rig source at all — not a stub, not an
    // inert branch (spec `module-architecture`, "A build-time-only module is contained by compilation").
    // That is why this change alters no `ios-app-shell` requirement: nothing shipped can observe the rig
    // or the env var its hook reads.
    //
    // The contributed directory compiles INTO this module, which is what lets it reach
    // `SnapSyncRoot.app` at `internal` visibility without widening anything to `public`. It is listed in
    // the root build's `appShellSources`, so it is gated like any other shell source.
    val rigEnabled = providers.gradleProperty("snapsync.rig").map(String::toBoolean).getOrElse(false)

    sourceSets {
        // Both together, or neither: the contributed call site and the module it names cannot be
        // half-present. Inside `sourceSets { }` because `iosMain` is created by the hierarchy template
        // and does not exist as a named source set before this block runs.
        if (rigEnabled) {
            iosMain { kotlin.srcDir("../../test/rig/src/hook/kotlin") }
        }
        iosMain.dependencies {
            if (rigEnabled) implementation(project(":test:rig"))
            // The upload extension's composition root, so the control channel can invoke the OS-driven
            // tier's REAL cycle rather than a copy of its wiring. Rig-gated exactly like `:test:rig`
            // itself: a production build links no part of this and contains no route to that root.
            //
            // Two static Xcode frameworks in one bundle would both pull the shared domain code into one
            // image, which is why the app and the appex are separate frameworks. A Gradle module
            // dependency folds it into `SnapSyncKit` instead — settled by a compile
            // (`linkDebugFrameworkIosSimulatorArm64`), not by reasoning.
            if (rigEnabled) implementation(project(":app:ios:extension"))
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
