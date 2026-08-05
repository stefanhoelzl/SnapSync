package app.snapsync.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

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
 * Konsist rather than detekt for the same reason [KeychainContainmentTest] uses it: the forms include
 * fully-qualified references that import nothing, and detekt has no type resolution for Kotlin/Native
 * source sets. Konsist reads source, so `iosMain` and the Swift files are both reachable from a JVM test.
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
        // Presents the limited-library picker; same reason.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/permission/PresentLimitedLibraryPicker.kt" to
            "presents PHPicker",
        // Opens the Settings URL and observes UIApplication notifications; both are main-thread-only.
        "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/permission/PhotoLibraryPermission.kt" to
            "UIApplication.openURL + a UIApplication notification observer",
        // The app shell: injects the lane into the composition (`AppPorts.uiLane`), reads the
        // main-thread-only `isProtectedDataAvailable`, and observes UIApplication lifecycle
        // notifications. The ONE place in the app process that may name the lane.
        "/app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt" to
            "injects AppPorts.uiLane; UIApplication reads and lifecycle observers",
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

    private fun productionFiles() = Konsist
        .scopeFromProject()
        .files
        .filterNot { it.path.contains("/build/") }
        .filterNot { it.path.contains("/test/") } // test source sets, incl. this file, name the forms
        .filterNot { it.path.contains("Test.kt") }

    @Test
    fun `the main lane is named only by platform-UI adapters and the shell that injects it`() {
        productionFiles()
            .filterNot { file -> allowed.keys.any { file.path.endsWith(it) } }
            .assertTrue(testName = "no main-thread dispatcher outside the platform-UI allowlist") { file ->
                mainLaneForms.none { form -> file.text.contains(form) }
            }
    }

    @Test
    fun `runBlocking appears only in the extension composition root`() {
        productionFiles()
            .filterNot { it.path.endsWith(runBlockingAllowed) }
            .assertTrue(testName = "no runBlocking in production source") { file ->
                // Code forms only. The bare word is legitimate in prose — `Reconciler` and `UploadCycle`
                // both explain the extension's OS-imposed `runBlocking` cap — and a gate that policed
                // comments would be answered by rewording rather than by fixing anything.
                runBlockingCallForms.none { form -> file.text.contains(form) }
            }
    }
}
