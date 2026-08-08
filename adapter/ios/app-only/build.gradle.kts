// `:adapter:ios:app-only` (spec `module-architecture`): iOS adapters only the MAIN APP process
// links — placed by linkage. Two reference app-only OS surfaces outright (`BGTaskScheduler`);
// the others are app-process-bound by identity or need: `IosUrlSessionUploadPlatform` and
// `IosDownloadTransport` own background-`URLSession` ids the OS reattaches to the app process
// across relaunch (a second, extension-side claimant of an OS-held session identity must be
// structurally impossible), `IosPhotoLibraryImporter` is the download feature's import writer,
// and `PhotoLibraryPermission` requests authorization where the system sheet can present.
// Keeping them out of `:adapter:ios:ext-safe` keeps the extension binary lean and the
// extension-safety gate scoped to code the appex can actually contain.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // Sentry test-link (capability `crash-reporting`): this module's simulator TEST binary links
    // :adapter:ios:ext-safe (api) and therefore Sentry symbols — even with no test sources, K/N
    // still links an empty test.kexe. Reuse the Sentry-Dynamic framework ext-safe provisions.
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
            // The shared PhotoKit discovery walk (IosDiscovery) the URLSession tier reuses.
            api(project(":adapter:ios:ext-safe"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Full failure messages in CI (same configuration as `:adapter:generic:app`): the Kotlin/Native
// simulator runner otherwise prints a terse "AssertionError at null:-1" with no message and no
// line, which makes a red iOS-only test unreadable from Linux — the one place these tests cannot
// be re-run.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // Standard streams stay OFF, unlike `:adapter:generic:app`: `DarwinHttpClientTest` aims
        // requests at a closed port on purpose, and the client's own failure logging would then
        // print a multi-line NSError with a stack trace for each one — burying real output.
    }
}
