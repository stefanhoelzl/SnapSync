package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The JVM root boots cold** (`docs/testing.md`, "The JVM root boots cold"; decision record
 * `harden-seam-bug-classes`).
 *
 * Constructing a launch of the JVM root may force no member of the composed `AppCore`. The world it replaced once
 * touched `core.downloadController` in its `init`, "so its lazy construction installs the production `onStaged`
 * hook" — and that one line is why no test could see that a process iOS relaunches only to deliver download events,
 * which builds the jobs and nothing else, dropped every staged photo. A root that starts warm tests a process that
 * starts warm; the device's does not — and every rig test on the JVM host runs over this root.
 *
 * So `JvmApp.kt` — its own members and its per-launch `Launch`'s — may reach the core only where the access is
 * deferred to use — inside a `by lazy`, a `get()`, or a function body — and never in an `init` block or an eagerly
 * initialized property.
 *
 * **Heuristic, and it says so.** It reads `JvmApp.kt`'s text, not a call graph: a member the root's constructor calls
 * that itself touches the core is not seen, and neither is a core member forced by some other object the root builds
 * eagerly. It closes the direct form, which is the one that happened.
 */
class JvmRootBootsColdTest {

    private val rootFile = File(SourceScan.repoRoot, "app/jvm/src/main/kotlin/app/snapsync/jvm/JvmApp.kt")

    /**
     * The member statements of [code] at [indent] — the root's own (4) or its launch's (8): each starts at that
     * indentation and runs to the next one.
     */
    private fun memberStatements(code: String, indent: Int): List<Pair<Int, String>> {
        val lines = code.lines()
        val starts = lines.indices.filter { Regex("""^ {$indent}\S""").containsMatchIn(lines[it]) }
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

    /** `core.` itself or reached through the composed app (`composed.core.`). */
    private fun touchesCore(text: String) = Regex("""(?<!\w)core\.""").containsMatchIn(text)

    private fun eagerCoreAccesses(code: String): List<String> = listOf(4, 8).flatMap { indent ->
        memberStatements(code, indent).mapNotNull { (line, stmt) ->
            val head = stmt.trimStart()
            val isInit = head.startsWith("init ") || head.startsWith("init{")
            val isProperty = Regex("""^(?:(?:private|internal|public|override|protected)\s+)*(?:val|var)\s""").containsMatchIn(head)
            val deferred = Regex("""\bby\s+lazy\b""").containsMatchIn(stmt) || Regex("""\bget\(\)""").containsMatchIn(stmt)
            when {
                // An init block runs its statements now; a lambda inside it still runs later.
                isInit && touchesCore(outsideLambdas(head.substringAfter('{').substringBeforeLast('}'))) ->
                    "  JvmApp.kt:$line :: init block touches core"
                isProperty && !deferred && touchesCore(outsideLambdas(head)) ->
                    "  JvmApp.kt:$line :: ${head.lineSequence().first().trim()}"
                else -> null
            }
        }
    }

    @Test
    fun `constructing a launch forces no member of the composed core`() {
        assertTrue(rootFile.isFile, "JVM root boots-cold gate: JvmApp.kt moved — re-point the scan")
        val code = ZoneGates.stripComments(rootFile.readText())
        assertTrue(Regex("""\bval core: AppCore\b""").containsMatchIn(code), "JvmApp.kt no longer declares `core` — the gate is stale")
        val eager = eagerCoreAccesses(code)
        assertTrue(
            eager.isEmpty(),
            "the JVM root touches the composed core while a launch is being constructed. A cold device process builds " +
                "nothing it is not asked for, so a root that warms a lazy up hides every path that depends on " +
                "something else having built it first (the B1 staging drop). Defer the access — `by lazy`, " +
                "`get()`, or the function that needs it.\n" + eager.joinToString("\n"),
        )
    }

    @Test
    fun `the scan sees an eager access and ignores a deferred one`() {
        val sample = """
            class JvmApp(scope: CoroutineScope) {
                val core: AppCore get() = launch.composed.core
                val eager = core.downloadJobs
                private inner class Launch {
                    val composed: ComposedApp = snapSyncHost(scope, appPorts(), formatter)
                    val extension: ComposedExtension = snapSyncExtension(extensionPorts())
                    val controller: DownloadController get() = composed.core.downloadController
                    val warm = composed.core.downloadController
                    init {
                        composed.core.downloadJobs
                        snapSyncExtension(ports = { composed.core.ports })
                    }
                    fun stage() { composed.core.downloadJobs.awaitOutstandingStagings() }
                }
            }
        """.trimIndent()
        val found = eagerCoreAccesses(sample)
        assertTrue(
            found.size == 3 && found.any { "init" in it } && found.any { "eager" in it } && found.any { "warm" in it },
            "the scan misread: $found",
        )
    }
}
