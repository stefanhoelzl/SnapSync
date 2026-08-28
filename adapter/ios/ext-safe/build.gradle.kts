// `:adapter:ios:ext-safe` (spec `module-architecture`): every iOS adapter the background-upload
// EXTENSION process links — placed by linkage, so the extension binary's contents are decided by
// this module boundary rather than by luck. The extension-safety text gate
// (`:test:architecture` ExtensionSafetyTest) forbids `platform.UIKit`/`platform.BackgroundTasks`
// anywhere under this module, because Kotlin/Native does not model `NS_EXTENSION_UNAVAILABLE`.
// This is also the Keychain containment module — the ONLY module that may touch `SecItem*`
// (capability `architecture-guards`; KeychainContainmentTest).

import java.net.URI

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Sentry test-link provisioning (capability `crash-reporting`): the sentry-kmp klib references the
// sentry-cocoa framework, which is provided by SPM at Xcode link time for the shipped frameworks —
// but this module's own SIMULATOR TEST EXECUTABLE is linked by Gradle, so a framework must exist
// for that link. It must be the DYNAMIC variant: the static archive's Swift objects force-load
// Apple's Swift compatibility shims AND platform overlays (swiftCompatibility56,
// swiftAVFoundation, …), whose search paths only a Swift-driven link supplies — two CI rounds of
// -L whack-a-mole (measured 2026-07-21) ended by switching to the prebuilt dylib, which has all
// its Swift deps already bound and so propagates no FORCE_LOAD symbols to the consumer. The test
// binary then needs an rpath to the slice at runtime (a simulator process shares the host
// filesystem, so the absolute build/ path is loadable). macOS-only by construction: only the
// mac-side test-link tasks depend on it (Linux never links iOS binaries).
val sentryCocoaVersion = libs.versions.sentry.cocoa.get()
val sentryFrameworkDir = layout.buildDirectory.dir("sentry-cocoa/$sentryCocoaVersion")
val provisionSentryCocoa by tasks.registering {
    val zipUrl = "https://github.com/getsentry/sentry-cocoa/releases/download/" +
        "$sentryCocoaVersion/Sentry-Dynamic.xcframework.zip"
    val outDir = sentryFrameworkDir
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        val marker = dir.resolve("Sentry-Dynamic.xcframework")
        if (marker.exists()) return@doLast
        dir.mkdirs()
        val zip = dir.resolve("Sentry-Dynamic.xcframework.zip")
        URI(zipUrl).toURL().openStream().use { input ->
            zip.outputStream().use { input.copyTo(it) }
        }
        // Symlinks inside the xcframework make java.util.zip unusable here; the tool exists
        // wherever this runs (the link itself needs Xcode).
        val unzip = ProcessBuilder("unzip", "-q", "-o", zip.absolutePath, "-d", dir.absolutePath)
            .inheritIO().start().waitFor()
        check(unzip == 0) { "unzip of Sentry-Dynamic.xcframework failed ($unzip)" }
        zip.delete()
    }
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // The -F search path for the test-executable link and the -rpath for its runtime load (see
    // provisionSentryCocoa above). The slice is the xcframework's simulator entry; iosArm64 tests
    // don't exist (device binaries are linked only by Xcode, where SPM provides Sentry).
    val sentrySimulatorSlice =
        "${sentryFrameworkDir.get().asFile}/Sentry-Dynamic.xcframework/ios-arm64_x86_64-simulator"
    iosSimulatorArm64().binaries.all {
        if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
            linkTaskProvider.configure { dependsOn(provisionSentryCocoa) }
            // `-lsqlite3`: the sqliter cinterop that backs `NativeSqliteDriver` declares this
            // itself, but only for a compilation that depends on the driver DIRECTLY — here it is
            // an `implementation` dep of commonMain, and the option does not reach the test
            // executable's own link. It goes unnoticed until a test actually opens a database
            // (`IosLedgerStoreTest`, `IosDownloadStoreTest`), at which point the link fails with a
            // wall of undefined `_sqlite3_*` symbols. The system library is present on every Apple
            // platform; this only tells the linker to use it.
            linkerOpts("-F$sentrySimulatorSlice", "-rpath", sentrySimulatorSlice, "-lsqlite3")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(project(":domain:feature"))
            // The SQLDelight stores these drivers open (SqlDelightLedgerStore/SqlDelightDownloadStore)
            // and the Ktor core types darwinHttpClient() returns.
            api(project(":adapter:generic:app"))
            // (The interim :capability:album and :domain:gallery edges died at migration step 6:
            // the album seams now live in :domain ports/, albumMapSource in feature/album, and the
            // ResourceEnumerator composition in feature/upload — all reached via api(":domain").)
            implementation(libs.sqldelight.driver.native)
            implementation(libs.ktor.client.darwin)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
            // Crash reporting (capability `crash-reporting`): the SDK dep lives here because both
            // processes link this module. sentry-cocoa itself is provided at EXECUTABLE link time —
            // by SPM in iosApp.xcodeproj for the app/appex (the exported frameworks are static, so
            // Gradle's libtool "link" needs no Sentry symbols), and by the provisioning below for
            // this module's own simulator test binary.
            implementation(libs.sentry.kmp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // A mock engine so `withCredentialInterceptor`'s 401 branch — the entry point of the
            // credential-recovery loop — can be exercised against a real response. The Darwin engine
            // cannot produce one without a server, and this module's tests deliberately stand up none.
            implementation(libs.ktor.client.mock)
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
