package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every scene the UI adapter hands out is recorded** (`docs/architecture.md`; capability `sync-status`).
 *
 * `IosUi.onSceneActive()` answers the SwiftUI host's rebuild signal from `SceneRecord.generation` — a monotonic
 * count advanced each time a scene is handed out. It is advanced in exactly one place, `SceneRecord.resolve()`, and
 * the count is complete **only because `resolve()` is the single path by which a scene is obtained** — `IosUi`'s
 * `viewController()`, the pull SwiftUI makes. A second caller would install a scene the count never saw, or advance
 * the count without installing anything, and either way `onSceneActive()` would answer for a scene that is not the
 * one on screen — which is the shape that blanked the screen in the first place (Bugsink SNAPSYNC-15, SNAPSYNC-24).
 *
 * That single-caller property is an invariant the compiler cannot express: `resolve()` is public, so anything that
 * holds the record may call it and nothing complains. This gate is what holds it — the laws prefer a red build to a
 * remembered rule.
 *
 * ⚠️ **If you are here because this failed:** do not simply add the new call site to an allowlist. Ask first
 * whether the new caller INSTALLS the returned scene. If it does, the count is still complete and the gate needs
 * widening deliberately; if it does not, it is corrupting the record and should read the mode some other way.
 *
 * Text matching rather than symbol resolution, for the same reason [KeychainContainmentTest] gives: `iosMain` is not
 * on the JVM test classpath, so there is no resolved model to query.
 */
class SceneRecordCompletenessTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** The one file allowed to call it — the UI adapter's scene pull, which SwiftUI's `ContentView` reaches. */
    private val permittedCaller = "IosUi.kt"

    /** Every production source that could hold the record: the UI adapter module, the app shell and the rig hook. */
    private fun sources(): List<File> =
        listOf("adapter/ios/ui/src", "app/ios/src", "test/rig/src").flatMap { root ->
            File(repoRoot, root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "Test/" !in it.path }
                .toList()
        }

    private val resolveCall = Regex("""\b(?:record|sceneRecord)\.resolve\(""")

    @Test
    fun `the scene is resolved in exactly one place, the UI adapter's pull`() {
        val sources = sources()
        assertTrue(sources.isNotEmpty(), "scene-record gate scanned zero sources — the UI adapter moved")

        // A call on the record, not the declaration and not a KDoc reference.
        val callers = sources.filter { f ->
            f.readLines().any { line ->
                val code = line.substringBefore("//").trim()
                resolveCall.containsMatchIn(code) && !code.startsWith("*")
            }
        }.map { it.name }.sorted()

        assertEquals(
            listOf(permittedCaller),
            callers,
            "`SceneRecord.resolve()` must have exactly one caller, because the generation `onSceneActive()` answers " +
                "from is advanced there and is complete only if every scene handed out passes through it. " +
                "Found: $callers. Read this test's KDoc before widening it.",
        )
    }

    @Test
    fun `the generation is advanced where the mode is resolved, and nowhere else`() {
        val record = File(repoRoot, "adapter/ios/ui/src/iosMain/kotlin/app/snapsync/scene/SceneRecord.kt")
        assertTrue(record.isFile, "SceneRecord.kt moved — update this gate")

        val assignments = sources().sumOf { f ->
            f.readLines().count { line ->
                val code = line.substringBefore("//").trim()
                Regex("""^generation\s*=""").containsMatchIn(code)
            }
        }
        assertEquals(
            1,
            assignments,
            "the scene generation must be assigned exactly once — in `SceneRecord.resolve()`, as it resolves. A " +
                "second writer makes the signal move without a scene being handed out, and `.id(…)` reacts to ANY " +
                "change, so a stray write is a rebuild.",
        )
    }
}
