package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The OS upload-job subsystem is bound by compilation target, and the SIMULATOR actual is the one that
 * kills the process if it is wrong** (capability `architecture-guards`, "The upload-job subsystem binding
 * gate").
 *
 * Two seams, bound the same way for the same measured reason (`ios-photokit-upload`, "The upload-job
 * subsystem binding is fixed by the compilation target"):
 *
 * - `uploadJobQueue` in `:adapter:ios:ext-safe` — fetch, create, retry, acknowledge;
 * - `uploadExtensionRegistry` in `:adapter:ios:app-only` — the registration record.
 *
 * A source-text gate for the reason [TransferSessionBindingTest] states: every iOS test in this repo runs
 * on `iosSimulatorArm64`, so the **device** actual is executed by nothing in CI. A swap of the two actuals
 * would ship a binary whose uploads are inert to real users, and would pass the build, `codesign` and the
 * whole simulator suite.
 *
 * **The stakes here run the other way too, and harder.** Reaching PhotoKit's job creation on a simulator
 * does not degrade — `creationRequestForJobWithDestination` raises `NSInvalidArgumentException` from inside
 * `-[PHAssetResourceUploadJobChangeRequest setUploadJobConfiguration:]` and terminates the process
 * (measured 2026-08-26, iOS 26.5; `changes/exercise-os-driven-upload-on-simulator/PROBE-FINDINGS.md`). So a
 * mis-bound simulator actual destroys the host it exists to serve, with a stack naming Apple's frames
 * rather than ours.
 *
 * **What this does NOT establish:** that the PhotoKit subsystem accepts a registration on a device, or
 * refuses one on a simulator. Those are platform facts with their own forcing proofs and expiry triggers in
 * `ios-photokit-upload`; a text gate that claimed them would be asserting what it cannot observe.
 */
class UploadJobSubsystemBindingTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /**
     * The two per-target seams, each with the PhotoKit token whose presence IS the device binding.
     *
     * The token is the platform call itself rather than the adapter's class name: a substitute could be
     * named anything, but only the real binding can name the selector.
     */
    private val seams = listOf(
        Seam(
            need = "upload-job queue",
            path = "adapter/ios/ext-safe/src/%s/kotlin/app/snapsync/ios/upload/UploadJobQueue.kt",
            deviceToken = "IosPhotoKitUploadPlatform",
        ),
        Seam(
            need = "extension registration",
            path = "adapter/ios/app-only/src/%s/kotlin/app/snapsync/ios/registry/UploadExtensionRegistry.kt",
            deviceToken = "PhotoKitExtensionRegistry",
        ),
    )

    private class Seam(val need: String, val path: String, val deviceToken: String)

    /**
     * The PhotoKit calls that must never appear in a simulator actual.
     *
     * Checked in the substitute's own file as well as via its binding, because the failure is not "the
     * wrong adapter was selected" but "this call was reached at all".
     */
    private val fatalOnSimulator = listOf(
        "setUploadJobExtensionEnabled",
        "creationRequestForJobWithDestination",
        "creationRequestForAssetResourceUploadJob",
    )

    /**
     * The file's **code**, with KDoc and comments stripped.
     *
     * Necessary, not tidy: a substitute's KDoc legitimately names the implementation it stands in for
     * ("delegates discovery exactly as `IosPhotoKitUploadPlatform` does"), and a substitute's rationale
     * legitimately quotes the very selector its host refuses. A guard that read prose would forbid the
     * documentation that makes these seams comprehensible, and would be silenced by deleting it — which is
     * precisely backwards.
     */
    private fun codeOf(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun actual(seam: Seam, sourceSet: String): String {
        val file = File(repoRoot, seam.path.format(sourceSet))
        // A deleted actual must fail here rather than pass vacuously: "the token is absent" is exactly what
        // a missing file reports, and it is the answer this guard wants for ONE of the two targets.
        assertTrue(
            file.isFile,
            "the $sourceSet actual for the ${seam.need} is missing (${seam.path.format(sourceSet)}). " +
                "Deleting an actual is not a way past this gate — see `architecture-guards`, " +
                "\"The upload-job subsystem binding gate\".",
        )
        return codeOf(file.readText())
    }

    @Test
    fun `the device actuals bind the PhotoKit implementations`() {
        for (seam in seams) {
            assertTrue(
                actual(seam, "iosArm64Main").contains(seam.deviceToken),
                "iosArm64Main's ${seam.need} binding must name ${seam.deviceToken} — this is the binding " +
                    "every shipped binary links, and it is executed by nothing in CI. Without it a " +
                    "released build registers nothing and creates no upload job, silently.",
            )
        }
    }

    @Test
    fun `the simulator actuals bind no PhotoKit implementation`() {
        for (seam in seams) {
            assertTrue(
                !actual(seam, "iosSimulatorArm64Main").contains(seam.deviceToken),
                "iosSimulatorArm64Main's ${seam.need} binding names ${seam.deviceToken}. On that host the " +
                    "PhotoKit subsystem is refused and job creation TERMINATES THE PROCESS, so this is not " +
                    "a degraded path — it is a dead one.",
            )
        }
    }

    @Test
    fun `no simulator-linked source names a call that is fatal on that host`() {
        val roots = listOf(
            "adapter/ios/ext-safe/src/iosSimulatorArm64Main",
            "adapter/ios/app-only/src/iosSimulatorArm64Main",
            "test/rig/src/iosSimulatorArm64Main",
        )
        val offences = buildList {
            for (root in roots) {
                val dir = File(repoRoot, root)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                    val text = codeOf(file.readText())
                    fatalOnSimulator.filter { text.contains(it) }.forEach { token ->
                        add("${file.relativeTo(repoRoot)} names $token")
                    }
                }
            }
        }
        assertTrue(
            offences.isEmpty(),
            "these calls are fatal or refused on a simulator and must not appear in source only that " +
                "target compiles:\n  ${offences.joinToString("\n  ")}",
        )
    }

    /**
     * The seams are only meaningful while each is genuinely two-valued. A third iOS target, or a rename that
     * left this list pointing at nothing, must fail here rather than let a binding escape the pin
     * (`architecture-guards`, "Gates fail closed on novelty").
     */
    @Test
    fun `every pinned seam has both actuals`() {
        for (seam in seams) {
            for (sourceSet in listOf("iosArm64Main", "iosSimulatorArm64Main")) {
                assertTrue(
                    actual(seam, sourceSet).isNotBlank(),
                    "${seam.need}'s $sourceSet actual is empty",
                )
            }
        }
        val iosTargets = File(repoRoot, "adapter/ios/ext-safe/src").listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("ios") && it.name.endsWith("Main") }
            .map { it.name }
            .toSet()
        assertTrue(
            iosTargets == setOf("iosMain", "iosArm64Main", "iosSimulatorArm64Main"),
            "the iOS target set changed to $iosTargets — extend this pin to name the new target's binding " +
                "explicitly rather than letting it inherit one silently.",
        )
    }
}
