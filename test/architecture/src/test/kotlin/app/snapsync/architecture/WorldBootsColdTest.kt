package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The world boots cold** (`docs/testing.md`, "The world boots cold"; decision record
 * `harden-seam-bug-classes`).
 *
 * Constructing the world may force no member of the composed `AppCore`. It used to touch
 * `core.downloadController` in its `init`, "so its lazy construction installs the production `onStaged`
 * hook" — and that one line is why no test could see that a process iOS relaunches only to deliver download
 * events, which builds the jobs and nothing else, dropped every staged photo. A world that starts warm tests a
 * process that starts warm; the device's does not.
 *
 * So `World.kt` may reach `core.` only where the access is deferred to use — inside a `by lazy`, a `get()`,
 * or a function body — and never in an `init` block or an eagerly initialized property.
 *
 * **Heuristic, and it says so.** It reads `World.kt`'s text, not a call graph: a member the world's
 * constructor calls that itself touches the core is not seen, and neither is a core member forced by some
 * other object the world builds eagerly. It closes the direct form, which is the one that happened.
 */
class WorldBootsColdTest {

    private val worldFile = File(SourceScan.repoRoot, "test/world/src/commonMain/kotlin/app/snapsync/world/World.kt")

    /** The class-level statements of [code]: each starts at 4-space indentation and runs to the next one. */
    private fun memberStatements(code: String): List<Pair<Int, String>> {
        val lines = code.lines()
        val starts = lines.indices.filter { Regex("""^ {4}\S""").containsMatchIn(lines[it]) }
        return starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: lines.size
            start + 1 to lines.subList(start, end).joinToString("\n")
        }
    }

    /** [text] with every `{ … }` span removed — a lambda's body runs when it is called, not now. */
    private fun outsideLambdas(text: String): String {
        val out = StringBuilder()
        var depth = 0
        for (c in text) {
            when {
                c == '{' -> depth++
                c == '}' -> depth--
                depth == 0 -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun touchesCore(text: String) = Regex("""(?<![\w.])core\.""").containsMatchIn(text)

    private fun eagerCoreAccesses(code: String): List<String> = memberStatements(code).mapNotNull { (line, stmt) ->
        val head = stmt.trimStart()
        val isInit = head.startsWith("init ") || head.startsWith("init{")
        val isProperty = Regex("""^(?:(?:private|internal|public|override|protected)\s+)*(?:val|var)\s""").containsMatchIn(head)
        val deferred = Regex("""\bby\s+lazy\b""").containsMatchIn(stmt) || Regex("""\bget\(\)""").containsMatchIn(stmt)
        when {
            // An init block runs its statements now; a lambda inside it still runs later.
            isInit && touchesCore(outsideLambdas(head.substringAfter('{').substringBeforeLast('}'))) ->
                "  World.kt:$line :: init block touches core"
            isProperty && !deferred && !head.contains("val core:") && touchesCore(outsideLambdas(head)) ->
                "  World.kt:$line :: ${head.lineSequence().first().trim()}"
            else -> null
        }
    }

    @Test
    fun `constructing the world forces no member of the composed core`() {
        assertTrue(worldFile.isFile, "world boots-cold gate: World.kt moved — re-point the scan")
        val code = ZoneGates.stripComments(worldFile.readText())
        assertTrue(Regex("""\bval core: AppCore\b""").containsMatchIn(code), "World.kt no longer declares `core` — the gate is stale")
        val eager = eagerCoreAccesses(code)
        assertTrue(
            eager.isEmpty(),
            "the world touches the composed core while it is being constructed. A cold device process builds " +
                "nothing it is not asked for, so a world that warms a lazy up hides every path that depends on " +
                "something else having built it first (the B1 staging drop). Defer the access — `by lazy`, " +
                "`get()`, or the function that needs it.\n" + eager.joinToString("\n"),
        )
    }

    @Test
    fun `the scan sees an eager access and ignores a deferred one`() {
        val sample = """
            class World(scope: CoroutineScope) {
                val core: AppCore = snapSyncApp(scope, ports)
                val controller: DownloadController get() = core.downloadController
                val cycle: UploadCycle by lazy { uploadCore(scope, core.ports) }
                val eager = core.downloadJobs
                val port = WorldBackendPort(miniEdgeClient(store), base, { core.versionRefusal.value.toString() }) {}
                init {
                    core.downloadController
                }
                fun stage() { core.downloadJobs.awaitOutstandingStagings() }
            }
        """.trimIndent()
        val found = eagerCoreAccesses(sample)
        assertTrue(found.size == 2 && found.any { "init" in it } && found.any { "eager" in it }, "the scan misread: $found")
    }
}
