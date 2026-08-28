package app.snapsync.architecture

import com.lemonappdev.konsist.api.Konsist
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

    private fun file(nameEnd: String) = Konsist
        .scopeFromProject()
        .files
        .filterNot { it.path.contains("/build/") }
        .firstOrNull { it.path.endsWith(nameEnd) }
        ?: fail("expected to find $nameEnd")

    /** The bundle's declared commands — `val <name>: <type>` in the `model/` type. */
    private fun declaredCommands(): List<String> =
        Regex("""^\s{4}val (\w+):""", RegexOption.MULTILINE)
            .findAll(file("/domain/model/src/commonMain/kotlin/app/snapsync/model/UserCommands.kt").text)
            .map { it.groupValues[1] }
            .toList()

    /** The `UserCommands(...)` argument block in the one place commands are built. */
    private fun builtBundle(): String {
        val text = file("/domain/compose/src/commonMain/kotlin/app/snapsync/compose/SnapSyncApp.kt").text
        val start = text.indexOf("UserCommands(")
        assertTrue(start >= 0, "compose/ no longer builds a UserCommands bundle — this gate is stale")
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
        fail("unbalanced UserCommands( block in SnapSyncApp.kt")
    }

    @Test
    fun `every command in the bundle is built through a lane-declaring decorator`() {
        val bundle = builtBundle()
        // Split into per-argument chunks at the argument indentation the bundle is written with, so a
        // decorator naming one command cannot vouch for its neighbour.
        val chunks = Regex("""\n {12}(\w+) = """).findAll(bundle).toList()
        assertTrue(chunks.isNotEmpty(), "no arguments parsed from the UserCommands( block")

        val undeclared = chunks.mapIndexedNotNull { index, match ->
            val from = match.range.first
            val to = chunks.getOrNull(index + 1)?.range?.first ?: bundle.length
            val name = match.groupValues[1]
            val body = bundle.substring(from, to)
            // A command bound to a plain default (`= {}`) or wired to nothing declares no lane and needs
            // none; anything with a body must say where it runs.
            name.takeIf { decorators.none { d -> body.contains(d) } }
        }

        assertTrue(
            undeclared.isEmpty(),
            "these commands are built without a lane-declaring decorator " +
                "(${decorators.joinToString(" / ")}): $undeclared",
        )
    }

    @Test
    fun `the gate sees every command the bundle declares`() {
        val declared = declaredCommands()
        val bundle = builtBundle()
        val missing = declared.filterNot { bundle.contains("\n            $it = ") }
        assertTrue(
            missing.isEmpty(),
            "declared in UserCommands but not built in compose/, so the lane gate never sees them: $missing",
        )
    }
}
