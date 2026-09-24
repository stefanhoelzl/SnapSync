package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **State reached from OS callbacks is confined** (capability `module-architecture`; decision record
 * `harden-seam-bug-classes`, D12).
 *
 * A class that receives OS callbacks is reached from threads it does not choose — a URLSession delegate queue, a
 * PhotoKit observer thread, a notification block. Two of its fields were races that shipped: the selection
 * snapshots emitted from a parallel dispatcher (B9), and the download jobs' outstanding-import list shared between
 * the delegate queue and the drain. So every mutable field such a class holds either names its lane —
 * `@ConfinedTo("lane")` — or is `@Volatile` (a single-writer cell); a thread-safe primitive (`MutableStateFlow`,
 * `Channel`, a `Mutex`) is not a `var` or a mutable collection, so it never needs either.
 *
 * **Which classes.** Every `NSObject()` subclass in the iOS source (a delegate, by construction), plus [receivers]:
 * the classes that take OS callbacks through a Kotlin interface, which no text scan can recognise by shape.
 *
 * **Heuristic, and it says so.** It reads text: a class-level `var`, or a class-level `val` initialised with a
 * mutable collection constructor, is a mutable field; a field mutated through some other type (a `val` holding a
 * mutable object built by a factory) is not seen, and neither is a class that starts receiving callbacks without
 * being listed. The annotation documents the lane; nothing checks at runtime that the access happens on it.
 */
class ConfinementGateTest {

    /** Classes that receive OS callbacks through a Kotlin interface, by file and class name. */
    private val receivers = mapOf(
        "domain/feature/src/commonMain/kotlin/app/snapsync/feature/download/QueuedPhotoDownloadJobs.kt" to
            "QueuedPhotoDownloadJobs", // DownloadTransportHost, from the URLSession delegate queue
        "domain/ports/src/commonMain/kotlin/app/snapsync/ports/OsCompletions.kt" to
            "OsCompletions", // the OS's completion handlers, handed over and released on its own threads
        "adapter/generic/app/src/commonMain/kotlin/app/snapsync/selection/SelectionSnapshotLane.kt" to
            "SelectionSnapshotLane", // PhotoKit change notifications
        "adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/permission/PhotoSelectionSnapshotSource.kt" to
            "PhotoKitSelection",
        "adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/download/IosDownloadTransport.kt" to
            "IosDownloadTransport",
        "adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/ios/urlsession/IosUrlSessionUploadPlatform.kt" to
            "IosUrlSessionUploadPlatform",
        "adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/ios/upload/IosPhotoKitUploadPlatform.kt" to
            "IosPhotoKitUploadPlatform",
        "adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/permission/PhotoLibraryPermission.kt" to
            "PhotoLibraryPermission", // the did-become-active notification block
    )

    private val mutableCollection =
        Regex("""=\s*(?:mutableListOf|mutableMapOf|mutableSetOf|ArrayDeque|LinkedHashMap|HashMap|HashSet|ArrayList)\b""")

    /** The unconfined mutable fields of class [name] in [raw], or `null` when the class is not declared there. */
    internal fun unconfined(raw: String, name: String): List<String>? {
        val code = ZoneGates.stripComments(raw)
        val decl = Regex("""(?m)^([ \t]*)(?:[\w@()"]+[ \t]+)*class\s+$name\b""").find(code) ?: return null
        val indent = decl.groupValues[1].length + 4
        val open = bodyOpen(code, decl.range.last) ?: return emptyList()
        val body = code.substring(open + 1, closing(code, open))
        val lines = body.lines()
        return lines.indices.mapNotNull { i ->
            val line = lines[i]
            val lead = line.length - line.trimStart().length
            val text = line.trim()
            val isField = lead == indent &&
                (Regex("""^(?:(?:private|internal|protected|override|public|lateinit)\s+)*var\s""").containsMatchIn(text) ||
                    (Regex("""^(?:(?:private|internal|protected|override|public)\s+)*val\s""").containsMatchIn(text) &&
                        mutableCollection.containsMatchIn(text)))
            val annotations = lines.subList(0, i).takeLastWhile { it.trim().startsWith("@") }.joinToString(" ")
            val marked = "@ConfinedTo(" in annotations || "@Volatile" in annotations || "@ConfinedTo(" in text
            if (isField && !marked) "$name: ${text.take(90)}" else null
        }
    }

    /** The `{` opening the class body: after the constructor's parentheses and the supertype list. */
    private fun bodyOpen(code: String, from: Int): Int? {
        var i = from
        var parens = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> parens++
                ')' -> parens--
                '{' -> if (parens == 0) return i
                '\n' -> if (parens == 0 && code.substring(i + 1).trimStart().startsWith("class ")) return null
            }
            i++
        }
        return null
    }

    private fun closing(code: String, open: Int): Int {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return code.length - 1
    }

    @Test
    fun `every mutable field of an OS-callback class names its lane`() {
        val listed = receivers.flatMap { (path, name) ->
            val file = SourceScan.repoRoot.resolve(path)
            assertTrue(file.isFile, "confinement gate: $path moved — re-point the entry for $name")
            val found = unconfined(file.readText(), name)
            assertTrue(found != null, "confinement gate: $name is no longer declared in $path")
            found
        }
        val delegates = SourceScan.kotlinFiles()
            .filter { "/src/iosMain/" in it.path }
            .flatMap { f ->
                Regex("""\bclass\s+(\w+)[^{]*?:\s*NSObject\(\)""").findAll(ZoneGates.stripComments(f.text))
                    .flatMap { unconfined(f.text, it.groupValues[1]).orEmpty() }
                    .map { "${f.path} $it" }
            }
        val found = listed + delegates
        assertTrue(
            found.isEmpty(),
            "these fields of classes that receive OS callbacks name no lane (law \"State reached from OS callbacks " +
                "is confined\"). Confine each to one serial lane and say which — `@ConfinedTo(\"lane\")` — or hold " +
                "it in a thread-safe primitive (`MutableStateFlow`, `Channel`, an atomic, `@Volatile` for a " +
                "single-writer cell).\n" + found.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `the scan sees an unconfined field and passes a confined one`() {
        val sample = """
            class Receiver(private val scope: CoroutineScope) : Host {
                private var transport: Transport? = null
                private val queued = ArrayDeque<String>()
                @ConfinedTo("composition")
                private val inFlight = LinkedHashMap<String, Task>()
                @Volatile
                private var generation = 0
                private val cells = MutableStateFlow(emptyList<Job>())
                fun work() {
                    var local = 0
                    val scratch = mutableListOf<String>()
                }
            }
        """.trimIndent()
        assertEquals(
            listOf("Receiver: private var transport: Transport? = null", "Receiver: private val queued = ArrayDeque<String>()"),
            unconfined(sample, "Receiver"),
        )
    }
}
