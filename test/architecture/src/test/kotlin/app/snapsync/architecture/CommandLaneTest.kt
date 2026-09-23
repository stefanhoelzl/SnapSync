package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every user command declares its dispatcher lane** (capability `architecture-guards`; law:
 * `module-architecture`, "Dispatcher lanes are fixed by the composition").
 *
 * The composition scope cannot cover this door. The presentation container launches an `intent { }` on
 * an unconfined dispatcher, so a command's synchronous prefix runs on whichever thread fired it — the
 * main thread, for a tap — whatever the scope says. A `suspend` function that never actually suspends
 * (synchronous PhotoKit XPC behind a `suspend` signature is exactly that shape) then runs to completion
 * there. So the lane has to be declared where the command is built.
 *
 * Two lanes, three decorators, **no default**: `awaitingOnCoreLane` (the caller waits on the result),
 * `detachedOnCoreLane` (fire-and-forget, outcome on a read-model), `onUiLane` (presents platform UI and
 * must stay on the main thread). A command built through none of them does not compile — this gate
 * catches the other half, a command built through none of them *because it was written inline*.
 *
 * It also keeps the manually-verified surface small. The UI-lane commands cannot be exercised by any
 * automated test available to this project — they are fakes on desktop, and driving them on device needs
 * a signed WebDriverAgent — so "is the lane right?" has to be answerable by reading one file. This gate
 * guarantees the answer is in that file rather than emergent.
 */
class CommandLaneTest {

    private val decorators = listOf("awaitingOnCoreLane", "detachedOnCoreLane", "onUiLane")

    /**
     * The query bundle's ONE admissible decorator (law "Queries cross a lane-gated door"): a query returns a
     * value the caller waits for, and presents no platform UI, so it is awaited on the core lane — never
     * detached (the caller would have no answer) and never on the UI lane (the reads are the blocking work
     * the main lane must never see; the shareable count used to run there).
     */
    private val queryDecorators = listOf("awaitingOnCoreLane")

    private fun file(nameEnd: String) = SourceScan.kotlinFiles()
        .firstOrNull { it.path.endsWith(nameEnd) }
        ?: fail("expected to find $nameEnd")

    /** A bundle's declared fields — `val <name>: <type>` in its `model/` type. */
    private fun declared(type: String): List<String> =
        Regex("""^\s{4}val (\w+):""", RegexOption.MULTILINE)
            .findAll(file("/domain/model/src/commonMain/kotlin/app/snapsync/model/$type.kt").text)
            .map { it.groupValues[1] }
            .toList()

    /** Where each bundle is built — one file per bundle, both in `compose/`. */
    private val builtIn = mapOf("UserCommands" to "SnapSyncApp.kt", "UserQueries" to "QueryComposition.kt")

    /** The `<type>(...)` argument block in the one place the bundle is built. */
    private fun built(type: String): String {
        val text = file("/domain/compose/src/commonMain/kotlin/app/snapsync/compose/${builtIn.getValue(type)}").text
        val start = text.indexOf("$type(")
        assertTrue(start >= 0, "compose/ no longer builds a $type bundle — this gate is stale")
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        fail("unbalanced $type( block in ${builtIn.getValue(type)}")
    }

    /** The fields of [type] built through none of [allowed]. */
    private fun undecorated(type: String, allowed: List<String>): List<String> {
        val bundle = built(type)
        // Split into per-argument chunks at the argument indentation the bundle is written with, so a
        // decorator naming one field cannot vouch for its neighbour.
        val chunks = Regex("""\n {4,12}(\w+) = """).findAll(bundle).toList()
        assertTrue(chunks.isNotEmpty(), "no arguments parsed from the $type( block")
        return chunks.mapIndexedNotNull { index, match ->
            val to = chunks.getOrNull(index + 1)?.range?.first ?: bundle.length
            val body = bundle.substring(match.range.first, to)
            // Anything with a body must say where it runs.
            match.groupValues[1].takeIf { allowed.none { d -> body.contains(d) } }
        }
    }

    @Test
    fun `every command in the bundle is built through a lane-declaring decorator`() {
        val undeclared = undecorated("UserCommands", decorators)
        assertTrue(
            undeclared.isEmpty(),
            "these commands are built without a lane-declaring decorator " +
                "(${decorators.joinToString(" / ")}): $undeclared",
        )
    }

    @Test
    fun `every query in the bundle is awaited on the core lane`() {
        val undeclared = undecorated("UserQueries", queryDecorators)
        assertTrue(
            undeclared.isEmpty(),
            "these queries are built without `awaitingOnCoreLane` — a query run where it was asked runs its port " +
                "reads on the main thread (law \"Queries cross a lane-gated door\"): $undeclared",
        )
    }

    @Test
    fun `the gate sees every command and query the bundles declare`() {
        listOf("UserCommands", "UserQueries").forEach { type ->
            val bundle = built(type)
            val missing = declared(type).filterNot { Regex("""\n {4,12}$it = """).containsMatchIn(bundle) }
            assertTrue(
                missing.isEmpty(),
                "declared in $type but not built in compose/, so the lane gate never sees them: $missing",
            )
        }
    }
}
