package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlin.test.assertTrue as assertTrueKt

/**
 * **The main lane is contained to platform UI** (`docs/architecture.md`; law:
 * `docs/architecture.md`, "Dispatcher lanes are fixed by the composition").
 *
 * Whether a blocking platform call lands on the main thread used to be a property of *who called it* —
 * not decidable where the call is written, and duly not decided: 21 of 23 iOS adapter files touching a
 * blocking platform API hopped nowhere, and the two that did were both written after an incident. The
 * fix moved the decision to the composition scope, which runs on a dedicated non-UI lane. This gate is
 * what stops it moving back.
 *
 * **It contains a lane; it does not decide whether a call blocks.** That question is undecidable from
 * source — the same adapter is safe in the extension (whose cycle runs under `runBlocking` on the
 * OS-invoked thread) and lethal in the app. So the rule inverts: the main lane is unreachable by
 * default and reachable only through an allowlist edit a reviewer sees.
 *
 * **Both languages, because either can put work back on main.** Kotlin names it as `Dispatchers.Main`,
 * `MainScope()`, `dispatch_get_main_queue` or `NSOperationQueue.mainQueue`; Swift as
 * `DispatchQueue.main`. A gate watching only the Kotlin forms would have missed the Swift shell.
 *
 * Source text rather than detekt for the same reason [KeychainContainmentTest] gives: the forms include
 * fully-qualified references that import nothing, and detekt has no type resolution for Kotlin/Native
 * source sets. Reading files reaches `iosMain` and the Swift shells alike from a JVM test (see
 * [SourceScan]).
 */
class MainLaneContainmentTest {

    /**
     * The main lane's allowlist: files that present platform UI, plus the one shell that names the lane
     * to inject it. Each entry states why it is here — an allowlist without reasons decays into a list
     * of whatever failed the gate last.
     */
    private val allowed = mapOf(
        // The platform's own UI: presents the system share sheet over the top view controller, and opens this
        // app's Settings page. UIKit is main-thread-only, and the adapter names the lane itself so it is correct
        // for any caller rather than only the commands declared on that lane.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/systemui/IosSystemUi.kt" to
            "presents UIActivityViewController + UIApplication.openURL(Settings)",
        // The seam `IosSystemUi.openUrl` records and replays through: leaves the app for a URL — the
        // update-required screen's store button (capability `app-update-required`). `UIApplication` is
        // main-thread-only, and the seam names the lane so a synchronous replay never waits on it.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/link/UrlOpenerApi.kt" to
            "UIApplication.openURL",
        // Presents the limited-library picker (`choosePhotos`, absorbed from the former top-level
        // PresentLimitedLibraryPicker.kt), and observes UIApplication notifications; both main-thread-only.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/permission/PhotoLibraryPermission.kt" to
            "presentLimitedLibraryPicker + a UIApplication notification observer",
        // Releases `handleEventsForBackgroundURLSession`'s completion handler, which is part of UIKit and must be
        // called on the main thread (Apple's `UIApplicationDelegate` documentation). The core decides when; this
        // adapter's `Completion` puts the call where UIKit requires (phase 11f — before it, `OsCompletions` took a lane).
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/ios/urlsession/SessionCompletion.kt" to
            "the background URLSession completion handler (UIKit, main-thread-only)",
        // Reads the main-thread-only `isProtectedDataAvailable` for the background entry points' diagnostics
        // (capability `sync-status`). The read moved here from `SnapSyncRoot` when the shell became a driving
        // adapter: the core's entries ask the `ProcessInfo` port, and this adapter names the lane itself.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/protection/IosProcessInfo.kt" to
            "UIApplication.isProtectedDataAvailable",
        // Reads the main-thread-only `UIDevice` battery (switching battery monitoring on and back off in the same hop)
        // and `UIApplication.backgroundRefreshStatus` for a bug report (capability `privacy-security`).
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/device/IosDeviceConditions.kt" to
            "UIDevice battery + UIApplication.backgroundRefreshStatus",
        // The hand-off contracts' simulator-app binding (rig-gated): disposing a clause dismisses the share
        // sheet it presented, and UIKit dismissal is main-thread-only like the presentation it undoes.
        "/adapter/ios/app-only/src/rig/kotlin/app/snapsync/contract/HandoffContracts.kt" to
            "dismisses the UIActivityViewController a clause presented",
        // The Android app shell: its composition scope runs on the main lane — the lifecycle adapter's process observer
        // must be added on the main thread. The composition itself names no main lane: every platform-UI adapter
        // hops there on its own (`docs/architecture.md`, "Dispatcher lanes are fixed by the composition").
        "/app/android/src/main/kotlin/app/snapsync/android/SnapSyncRoot.kt" to "composes on the main lane",
        // Android's photo-permission dialog and selection sheet: an activity-result registration and its launch must
        // happen on the main thread (capability `photo-access`).
        "/adapter/android/src/androidMain/kotlin/app/snapsync/android/permission/AndroidPhotoPermission.kt" to
            "launches the permission request through the activity-result registry",
        // Android's own UI: starts the share chooser, a URL's app, and this app's Settings page from the activity in
        // front — `startActivity`, named on the main lane so the adapter is correct for any caller.
        "/adapter/android/src/androidMain/kotlin/app/snapsync/android/systemui/AndroidSystemUi.kt" to
            "starts the share chooser + ACTION_VIEW",
        // The iOS `Lifecycle` adapter: `didBecomeActive` / `willResignActive` are observed on the main queue, where
        // UIKit posts them and where the scene record they write is confined.
        "/adapter/ios/ui/src/iosMain/kotlin/app/snapsync/scene/IosLifecycle.kt" to
            "observes UIApplication's lifecycle notifications on the main queue",
    )

    private val mainLaneForms = listOf(
        "Dispatchers.Main",
        "MainScope()",
        "dispatch_get_main_queue",
        "NSOperationQueue.mainQueue",
        "DispatchQueue.main",
    )

    /**
     * `runBlocking` blocks whichever thread it is called on, which defeats the lane its caller was
     * placed on. The extension's entry-port adapter is the one pinned use: `process()` is synchronous by
     * the OS's own contract there, and the process does not outlive it.
     */
    private val runBlockingCallForms = listOf("runBlocking {", "runBlocking(")

    private val runBlockingAllowed =
        "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/extension/IosExtensionHost.kt"

    // Production source only: the spec exempts TEST SOURCE SETS, which a path names as `src/<name>Test/`
    // (`commonTest`, `jvmTest`, `iosSimulatorArm64Test`, …) or, for a JVM-only module, `src/test/`. A file-name
    // match alone missed a helper in a test source set not named `*Test.kt` — the backend contracts' `LiveEdge`
    // in `:adapter:generic:app` jvmTest, whose setup must block because `Binding.create` does.
    private fun productionFiles() = SourceScan.kotlinFiles()
        .filterNot { it.path.contains("/test/") } // test source sets, incl. this file, name the forms
        .filterNot { testSourceSet.containsMatchIn(it.path) }
        .filterNot { it.path.contains("Test.kt") }

    private val testSourceSet = Regex("""/src/\w*Test/""")

    @Test
    fun `the main lane is named only by platform-UI adapters and the shell that injects it`() {
        val offenders = productionFiles()
            .filterNot { file -> allowed.keys.any { file.path.endsWith(it) } }
            .flatMap { file -> mainLaneForms.filter { it in file.text }.map { "${file.path} names $it" } }
        assertTrueKt(
            offenders.isEmpty(),
            "a main-thread dispatcher is named outside the platform-UI allowlist. The lane is " +
                "unreachable by default and reachable only by an allowlist edit a reviewer sees:\n  " +
                offenders.sorted().joinToString("\n  "),
        )
    }

    @Test
    fun `runBlocking appears only in the extension entry-port adapter`() {
        // Code forms only. The bare word is legitimate in prose — `Reconciler` and `UploadCycle` both
        // explain the extension's OS-imposed `runBlocking` cap — and a gate that policed comments would
        // be answered by rewording rather than by fixing anything.
        val offenders = productionFiles()
            .filterNot { it.path.endsWith(runBlockingAllowed) }
            .flatMap { file -> runBlockingCallForms.filter { it in file.text }.map { "${file.path} calls $it" } }
        assertTrueKt(
            offenders.isEmpty(),
            "`runBlocking` blocks whichever thread it is called on, defeating the lane its caller was " +
                "placed on:\n  " + offenders.sorted().joinToString("\n  "),
        )
    }

    /**
     * The Swift half — and it did not exist until it was measured.
     *
     * This guard's rule has always named both languages, and its own documentation said "a gate watching
     * only the Kotlin forms would have missed the Swift shell". It was watching only the Kotlin forms:
     * the scan covered KOTLIN files only, so `DispatchQueue.main`
     * in `iosApp/**/*.swift` was never read. Measured 2026-08-28 — appending `DispatchQueue.main.async {}`
     * to `iosApp/iosApp/iOSApp.swift` and forcing a rerun left the build GREEN, while the same rule in
     * Kotlin correctly failed.
     *
     * Read as plain text, for the reason `SwiftShellGuardTest` gives: nothing in the Kotlin toolchain
     * parses Swift at all. The build file already declares `iosApp/**/*.swift` as a task input, so this
     * re-runs when a shell changes.
     */
    @Test
    fun `the main lane is not named in the Swift shells`() {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: fail("could not locate the repository root")
        val swift = File(repoRoot, "iosApp").walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .toList()
        assertTrueKt(
            swift.isNotEmpty(),
            "the Swift half of the main-lane gate scanned nothing — iosApp/ has moved, and this gate " +
                "would pass forever without reading a line",
        )
        val offenders = swift.flatMap { file ->
            file.readLines().withIndex().mapNotNull { (i, line) ->
                val code = line.substringBefore("//")
                if (SWIFT_MAIN_LANE !in code) {
                    null
                } else {
                    "${file.toRelativeString(repoRoot)}:${i + 1} names $SWIFT_MAIN_LANE"
                }
            }
        }
        assertTrueKt(
            offenders.isEmpty(),
            "a Swift shell names the main lane. The shells are transcribers: they forward the OS's raw " +
                "input to Kotlin and decide nothing, so dispatching work there puts it on a lane the " +
                "composition cannot govern:\n  " + offenders.joinToString("\n  "),
        )
    }

    private companion object {
        /** Swift's form of the main lane. The Kotlin forms are in [mainLaneForms]. */
        const val SWIFT_MAIN_LANE = "DispatchQueue.main"
    }
}
