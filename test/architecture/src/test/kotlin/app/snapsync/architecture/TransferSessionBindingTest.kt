package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The transport binding is fixed by the compilation target, and the DEVICE actual is the one nothing can
 * run** (capability `architecture-guards`, "The transport-binding gate").
 *
 * `transferSessionConfiguration` is `expect`/`actual` across `iosArm64` and `iosSimulatorArm64`
 * (`ios-url-session-upload`, "The transport binding is fixed by the compilation target"). The simulator
 * actual has an executable test beside it in `:adapter:ios:app-only`; the device actual has none and can
 * have none, because every iOS test in this repo runs on `iosSimulatorArm64`. A swap of the two actuals —
 * or a "simplification" giving both targets the default configuration — would ship a **foreground session
 * to real users**, and would pass the build, `codesign`, and the entire simulator test suite without a
 * murmur. Reading the source is the only mechanism left, which is the same reason
 * [KeychainContainmentTest] is a text guard.
 *
 * Plain file reads: the property is about which *source set directory* a call appears in, which
 * `File.readText()` answers directly. Every guard in this module now reads source the same way — the one
 * Kotlin-parsing dependency was removed once it turned out no guard used anything but a file's path and
 * its text (see [SourceScan]).
 *
 * **What this does NOT establish**, stated so nobody reads it as more than it is: that the device actual
 * *names* the background factory, never that the resulting session behaves. Whether a background
 * `URLSession` actually transfers on a device is a platform fact with its own forcing proof and expiry
 * trigger in `ios-url-session-upload`; only a device run shows it, and this guard is blind to it.
 */
class TransferSessionBindingTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val seamPath = "adapter/ios/app-only/src/%s/kotlin/app/snapsync/ios/urlsession/TransferSessions.kt"

    /** The factory that hands a session to `nsurlsessiond`. Its presence IS the background binding. */
    private val backgroundFactory = "backgroundSessionConfigurationWithIdentifier"

    private fun actual(sourceSet: String): String {
        val file = File(repoRoot, seamPath.format(sourceSet))
        // A deleted actual must fail here rather than pass vacuously: "the token is absent" is exactly
        // what a missing file reports, and it is the answer this guard wants for ONE of the two targets.
        assertTrue(
            file.isFile,
            "the $sourceSet transport-session actual is missing (${seamPath.format(sourceSet)}). " +
                "Deleting an actual is not a way past this gate — see `architecture-guards`, " +
                "\"The transport-binding gate\".",
        )
        return file.readText()
    }

    @Test
    fun `the device actual binds a background session`() {
        assertTrue(
            actual("iosArm64Main").contains(backgroundFactory),
            "iosArm64Main's transferSessionConfiguration must call $backgroundFactory — this is the " +
                "actual EVERY shipped binary compiles, and no test in this repo can execute it. If it " +
                "yields a default session, TestFlight and App Store builds stop transferring while " +
                "suspended, and nothing else in the build fails.",
        )
    }

    @Test
    fun `the simulator actual does not bind a background session`() {
        assertFalse(
            actual("iosSimulatorArm64Main").contains(backgroundFactory),
            "iosSimulatorArm64Main's transferSessionConfiguration must NOT call $backgroundFactory: " +
                "nsurlsessiond rejects every client there that has no bundle identifier, so a background " +
                "session transfers nothing and the host goes back to being unable to move a byte. The pin " +
                "is exact in both directions deliberately.",
        )
    }

    /**
     * The seam is the single place either transport resolves its configuration. A call site that built its
     * own configuration would be target-blind — that is how the two transports could come to hold
     * different bindings in one build, which the requirement forbids.
     */
    @Test
    fun `no transport builds its own session configuration`() {
        val offenders = File(repoRoot, "adapter/ios/app-only/src/iosMain")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("NSURLSessionConfiguration.") }
            .map { it.relativeTo(repoRoot).path }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "these target-blind files construct an NSURLSessionConfiguration directly instead of calling " +
                "transferSessionConfiguration: $offenders",
        )
    }
}
