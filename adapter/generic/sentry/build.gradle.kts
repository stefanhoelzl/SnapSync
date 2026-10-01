// `:adapter:generic:sentry` (`docs/architecture.md`): the crash-reporting seat of BOTH platforms — the
// `CrashReporter` port over the Sentry KMP SDK (capability `privacy-security`). One module rather than a copy per
// platform, because the translation is where "nothing unshaped leaves" is enforced and two copies would drift. It
// reads no platform API: every fact of the build arrives in `CrashOptions`.
//
// Its contract bindings are platform-bound tests only — `iosTest` on the simulator, `androidDeviceTest` on the
// emulator — and it has no `commonTest` (a module with device tests declares none, `snapsync.android`). The wire
// parsing both bindings share is one source directory compiled into each.

import java.net.URI

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.android")
}

// Sentry test-link provisioning (capability `privacy-security`): the sentry-kmp klib references the
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
val provisionSentryCocoa = tasks.register("provisionSentryCocoa") {
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
    jvmToolchain(libs.versions.jdk.get().toInt())
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
            linkerOpts("-F$sentrySimulatorSlice", "-rpath", sentrySimulatorSlice)
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            // sentry-cocoa itself is provided at EXECUTABLE link time — by SPM in iosApp.xcodeproj for the app/appex
            // (the exported frameworks are static, so Gradle's libtool "link" needs no Sentry symbols), and by the
            // provisioning above for this module's own simulator test binary. On Android it brings sentry-android,
            // WITHOUT its native-crash module: SnapSync ships no native code, the module adds native libraries per ABI,
            // and its frames would need native symbol files no build produces.
            implementation(libs.sentry.kmp.get().toString()) {
                exclude(group = "io.sentry", module = "sentry-android-ndk")
            }
        }
        iosTest {
            kotlin.srcDir("src/wireTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":test:contracts"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kermit)
            }
        }
        getByName("androidDeviceTest") {
            kotlin.srcDir("src/wireTest/kotlin")
            dependencies {
                implementation(project(":test:contracts"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kermit)
            }
        }
    }
}
