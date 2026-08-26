package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every scene the shell hands out is recorded** (capability `architecture-guards`; capability
 * `ios-app-shell`).
 *
 * `SnapSyncRoot.onSceneActive()` answers the SwiftUI host's rebuild signal from `sceneGeneration` — a
 * monotonic count advanced each time a scene is handed out. It is advanced in exactly one place,
 * `sceneMode()`, and the count is complete **only because `sceneMode()` is the single path by which a
 * scene is obtained**. A second caller would install a scene the count never saw, or advance the count without
 * installing anything, and either way `onSceneActive()` would answer for a scene that is not the one on
 * screen — which is the shape that blanked the screen in the first place (Bugsink SNAPSYNC-15,
 * SNAPSYNC-24).
 *
 * That single-caller property is an invariant the compiler cannot express: `sceneMode()` is `internal`, so
 * anything in `:app:ios` may call it and nothing complains. This gate is what holds it, and it is the
 * design's stated open question answered mechanically rather than by review — the laws prefer a red build
 * to a remembered rule.
 *
 * ⚠️ **If you are here because this failed:** do not simply add the new call site to an allowlist. Ask
 * first whether the new caller INSTALLS the returned scene. If it does, the count is still complete and
 * the gate needs widening deliberately; if it does not, it is corrupting the record and should read the
 * mode some other way.
 *
 * Text matching rather than Konsist symbol resolution, for the same reason [KeychainContainmentTest] uses
 * source text: `iosMain` is not on the JVM test classpath, so there is no resolved model to query.
 */
class SceneRecordCompletenessTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** The one file allowed to call it — the Compose door Swift's `ContentView` invokes. */
    private val permittedCaller = "MainViewController.kt"

    private fun appIosSources(): List<File> =
        File(repoRoot, "app/ios/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
            .toList()

    @Test
    fun `sceneMode has exactly one caller, and it is the platform entry point`() {
        val sources = appIosSources()
        assertTrue(sources.isNotEmpty(), "scene-record gate scanned zero sources — app/ios/src moved")

        // A call, not the declaration and not a KDoc reference: `sceneMode(` preceded by a receiver dot or
        // at a call position, excluding the `fun sceneMode(` declaration itself.
        val callers = sources.filter { f ->
            f.readLines().any { line ->
                val code = line.substringBefore("//").trim()
                "sceneMode(" in code &&
                    !code.startsWith("*") &&
                    !Regex("""\bfun\s+sceneMode\s*\(""").containsMatchIn(code)
            }
        }.map { it.name }.sorted()

        assertEquals(
            listOf(permittedCaller),
            callers,
            "`sceneMode()` must have exactly one caller, because `sceneGeneration` — the count " +
                "`onSceneActive()` answers from — is advanced there and is complete only if every scene " +
                "handed out passes through it. Found: $callers. Read this test's KDoc before widening it.",
        )
    }

    @Test
    fun `the generation is advanced where the mode is resolved, and nowhere else`() {
        val root = File(repoRoot, "app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt")
        assertTrue(root.isFile, "SnapSyncRoot.kt moved — update this gate")

        val assignments = root.readLines().count { line ->
            val code = line.substringBefore("//").trim()
            Regex("""^sceneGeneration\s*=""").containsMatchIn(code)
        }
        assertEquals(
            1,
            assignments,
            "`sceneGeneration` must be assigned exactly once — in `sceneMode()`, as it resolves. A second " +
                "writer makes the signal move without a scene being handed out, and `.id(…)` reacts to " +
                "ANY change, so a stray write is a rebuild.",
        )
    }
}
