package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every platform entry point is marked and logged before it decides anything**
 * (capability `architecture-guards`; spec `diagnostic-logging`, "Uniform platform-invocation
 * logging"; spec `module-architecture`, "Absence is never silent").
 *
 * The obligation: record the raw inputs the platform handed over **before** any filter tests them,
 * and name the outcome on exit. An entry point that decides and returns without writing anything is
 * indistinguishable in a device log from one the platform never called — and that ambiguity is not
 * hypothetical. On Bugsink `SNAPSYNC-3` an event link opened while the app was running reached
 * nothing, and no dump could say whether iOS never delivered it or `onUserActivity` discarded it,
 * because the discard path wrote no line. The only evidence the join gate never opened was the
 * *absence of an unrelated HTTP request*.
 *
 * **The population is DERIVED, never hand-enumerated**, which `module-architecture`'s "Commands
 * cross one door" already requires of the trigger inventory: *"derived from entry points, never
 * hand-enumerated."* That is not pedantry — a hand-enumeration was attempted while designing this
 * guard and was wrong in **both** directions, including `onOpenUrl` (which the platform never calls;
 * it is reached from the activity entries and the launch-env trigger) and missing the second
 * Swift→Kotlin door entirely.
 *
 * Two rules cover the surface:
 *
 *  1. **What Swift calls.** Every `<Root>.shared.<member>(` in the Swift shells under `iosApp`. This is the
 *     transcriber boundary, and deriving it FROM the Swift side means a new delegate method that
 *     forwards to a new Kotlin function extends the population automatically. `SwiftShellGuardTest`
 *     closes the loop from the other end: every Swift shell function must forward to Kotlin, so
 *     nothing can enter without appearing here.
 *  2. **What the OS calls in Kotlin.** Every `override fun` in a class conforming to an ObjC
 *     callback protocol — structurally `: NSObject(), …Protocol`. That is not a keyword guess; it is
 *     what such a surface *is*, so a new one anywhere in the iOS sources is picked up with no list
 *     edited.
 *
 * **Residue, named on purpose.** These rules do not describe every conceivable callback shape: a C
 * function pointer, a KVO observation, a `dispatch_source` handler, or a bare notification-observer
 * lambda (`addObserverForName`) whose body is not a declaration this can name. Those are covered by
 * convention, not by this guard. If one bites, **extend the rules** — do not add a pinned exception,
 * which is the hand-maintained list this whole design exists to avoid.
 */
class PlatformEntryLoggingTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val kotlinRoots = listOf(
        "app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt",
        "app/ios/src/iosMain/kotlin/app/snapsync/ios/MainViewController.kt",
        "app/ios/extension/src/iosMain/kotlin/app/snapsync/ios/upload/UploadExtensionRoot.kt",
    )

    private val callbackZones = listOf("adapter/ios", "app/ios")

    private val marker = "@PlatformEntry"
    private val wrapper = "invocation("

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue(file.isFile, "guard is scanning nothing — $relative not found from $repoRoot")
        return file.readText()
    }

    // ── Rule 1: whatever Swift calls on a composition root ────────────────────────────────────────

    private fun swiftCalledMembers(): Set<String> {
        val swift = File(repoRoot, "iosApp").walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .joinToString("\n") { it.readText() }
        assertTrue(swift.isNotBlank(), "read no Swift at all — iosApp/ has moved and this guard proves nothing")
        return Regex("""(?:SnapSyncRoot|UploadExtensionRoot)\.shared\.(\w+)\s*\(""")
            .findAll(swift).map { it.groupValues[1] }.toSet()
    }

    /** The declaration line of [member] in whichever root declares it, with the lines above it. */
    private fun declarationOf(member: String): Pair<String, String>? =
        kotlinRoots.firstNotNullOfOrNull { path ->
            val lines = read(path).lines()
            val index = lines.indexOfFirst { Regex("""^\s*fun\s+$member\s*\(""").containsMatchIn(it) }
            if (index < 0) null else path to lines.drop((index - 4).coerceAtLeast(0)).take(12).joinToString("\n")
        }

    @Test
    fun `every entry point Swift calls is marked and logged`() {
        val members = swiftCalledMembers()
        // Non-vacuity twin: the Swift shells forward at least the launch, activity, push, and
        // background-task entries. A regex that stops matching must fail here, never pass empty.
        assertTrue(members.size >= 6, "derived only ${members.size} Swift-called entry points — the derivation is broken")

        val problems = members.mapNotNull { member ->
            val found = declarationOf(member)
                ?: return@mapNotNull "$member — Swift calls it, but no composition root declares `fun $member(`"
            val (path, context) = found
            when {
                !context.contains(marker) -> "$path :: $member — missing $marker"
                !instrumented(path, context) -> "$path :: $member — does not open with the logging wrapper"
                else -> null
            }
        }
        if (problems.isEmpty()) return
        fail(explain("Swift forwards to these, but they are not instrumented entry points:", problems))
    }

    /**
     * Whether [context] logs before deciding — directly, or by delegating to a shared instrumented
     * helper in the same file.
     *
     * ONE level of delegation is resolved, deliberately. Three activity hooks forward to one
     * `deliverUserActivity` so that the enter line, the params, and the outcome naming exist exactly
     * once; demanding the literal wrapper at each site would force triplicating it, and three copies
     * of a diagnostic are three chances for one to drift. More than one level is not resolved: a
     * chain long enough to need it is a chain a reader cannot follow either. The delegate need not
 * be private: `processRawValue` forwards to the instrumented `process`, which the OS also calls.
     */
    private fun instrumented(path: String, context: String): Boolean {
        if (context.contains(wrapper)) return true
        val delegate = Regex("""=\s*(\w+)\s*\(""").find(context.substringAfter("fun "))?.groupValues?.get(1)
            ?: return false
        val lines = read(path).lines()
        val index = lines.indexOfFirst { Regex("""^\s*(?:private )?fun\s+$delegate\s*\(""").containsMatchIn(it) }
        if (index < 0) return false
        return lines.drop(index).take(14).joinToString("\n").contains(wrapper)
    }

    // ── Rule 2: whatever the OS calls on an ObjC protocol conformance ─────────────────────────────

    private fun callbackConformances(): List<File> = callbackZones.flatMap { zone ->
        File(repoRoot, zone).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex(""":\s*NSObject\(\)\s*,\s*\w*Protocol""").containsMatchIn(it.readText()) }
            .toList()
    }

    @Test
    fun `every OS callback on a protocol conformance is marked and logged`() {
        val files = callbackConformances()
        assertTrue(files.size >= 3, "found only ${files.size} ObjC callback conformances — the structural scan is broken")

        val problems = files.flatMap { file ->
            val relative = file.toRelativeString(repoRoot)
            val lines = file.readText().lines()
            // Only the members of the CONFORMING class are OS callbacks. A file may also hold our own
            // port implementations — `IosDownloadTransport.start`/`cancel` are `override fun` too, and
            // scanning the whole file reported them as platform entry points, which they are not.
            val conforming = conformanceRanges(lines)
            lines.mapIndexedNotNull { index, line ->
                if (conforming.none { index in it }) return@mapIndexedNotNull null
                if (!Regex("""^\s*override fun\s+\w+""").containsMatchIn(line)) return@mapIndexedNotNull null
                val context = lines.drop((index - 6).coerceAtLeast(0)).take(20).joinToString("\n")
                when {
                    !context.contains(marker) -> "$relative:${index + 1} — missing $marker: ${line.trim()}"
                    !context.contains(wrapper) -> "$relative:${index + 1} — no logging wrapper: ${line.trim()}"
                    else -> null
                }
            }
        }
        if (problems.isEmpty()) return
        fail(explain("The OS calls these, and they record nothing before deciding:", problems))
    }

    /** The line ranges of the ObjC-conforming class bodies in [lines], by brace depth. */
    private fun conformanceRanges(lines: List<String>): List<IntRange> {
        val starts = lines.indices.filter { Regex(""":\s*NSObject\(\)\s*,\s*\w*Protocol""").containsMatchIn(lines[it]) }
        return starts.map { start ->
            var depth = 0
            var seenOpen = false
            var end = lines.lastIndex
            for (i in start..lines.lastIndex) {
                depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
                if (lines[i].contains('{')) seenOpen = true
                if (seenOpen && depth <= 0) { end = i; break }
            }
            start..end
        }
    }

    private fun explain(headline: String, problems: List<String>): String = buildString {
        appendLine(headline)
        problems.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Mark it $marker and open its body with the logging wrapper, so the raw platform inputs")
        appendLine("are recorded BEFORE any filter tests them and the outcome is named on exit.")
        appendLine()
        appendLine("Why this is gating: an entry point that decides and returns silently is byte-identical")
        appendLine("in a dump to one the platform never called. SNAPSYNC-3 could not be diagnosed for")
        appendLine("exactly that reason — the only evidence the join gate never opened was the ABSENCE of")
        appendLine("an unrelated HTTP request.")
        appendLine()
        appendLine("If the entry point does not fit these derivation rules (a C function pointer, a KVO")
        appendLine("observation, a dispatch-source handler), EXTEND THE RULES rather than adding a pinned")
        appendLine("exception — a hand-maintained list is the failure mode this guard replaced.")
    }
}
