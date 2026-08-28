package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue as assertTrueKt
import kotlin.test.fail

/**
 * **The main lane is contained to platform UI** (capability `architecture-guards`; law:
 * `module-architecture`, "Dispatcher lanes are fixed by the composition").
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
        // Presents the system share sheet over the top view controller; UIKit is main-thread-only.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/share/IosShareSheet.kt" to
            "presents UIActivityViewController",
        // Opens the Settings URL, presents the limited-library picker (`choosePhotos`, absorbed from
        // the former top-level PresentLimitedLibraryPicker.kt), and observes UIApplication
        // notifications; all three are main-thread-only.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/permission/PhotoLibraryPermission.kt" to
            "UIApplication.openURL + presentLimitedLibraryPicker + a UIApplication notification observer",
        // The app shell: injects the lane into the composition (`AppPorts.uiLane`), reads the
        // main-thread-only `isProtectedDataAvailable`, and observes UIApplication lifecycle
        // notifications. The ONE place in the app process that may name the lane.
        "/app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt" to
            "injects AppPorts.uiLane; UIApplication reads and lifecycle observers",
        // The forge binary's entry point. It composes a UI and nothing else — there is no live core in
        // that binary to keep off the main lane, because it does not link `:app:ios`. The lane it names is
        // the scope its forged container runs on, which IS platform UI.
        "/app/ios/forge/src/iosMain/kotlin/app/snapsync/ios/forge/ForgeViewController.kt" to
            "the forged container's scope; this binary composes UI and holds no live core",
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
     * placed on. The extension's composition root is the one pinned use: `process()` is synchronous by
     * the OS's own contract there, and the process does not outlive it.
     */
    private val runBlockingCallForms = listOf("runBlocking {", "runBlocking(")

    private val runBlockingAllowed =
        "/app/ios/extension/src/iosMain/kotlin/app/snapsync/ios/upload/UploadExtensionRoot.kt"

    private fun productionFiles() = SourceScan.kotlinFiles()
        .filterNot { it.path.contains("/test/") } // test source sets, incl. this file, name the forms
        .filterNot { it.path.contains("Test.kt") }

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
    fun `runBlocking appears only in the extension composition root`() {
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
                if (SWIFT_MAIN_LANE !in code) null
                else "${file.toRelativeString(repoRoot)}:${i + 1} names $SWIFT_MAIN_LANE"
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
