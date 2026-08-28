package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **What the screen SHOWS is `UiState`; how it DRAWS is local** (capability `sync-status-screen`).
 *
 * The screens module may not hold state that decides what is on screen. A value the screen renders is a
 * value the reduction carries, so no host can supply the state and silently omit something rendered —
 * which is exactly what happened when the invalid-link banner was left off one call site and nothing
 * failed, in either the build or the test suite.
 *
 * This gate is the whole reason the form was lifted. With the seven range choices still held here, the
 * allowlist below would have had to exempt the largest state holder in the module, and the gate would
 * have asserted approximately nothing — a rule whose gate exempts its biggest violation is a comment.
 *
 * ⚠️ **If you are here because this failed:** the fix is almost never to add an entry. Ask what the new
 * state DECIDES. If it changes what the screen shows — which surface, which dialog, which choice is
 * selected — it belongs in the reduction, where it can be tested without Compose and transported without
 * being re-derived. An entry here is for state that only affects how something is *drawn*.
 *
 * Two entries exist, and each names why it is not "what is shown":
 *
 * - the **share-count row's in-flight state** — the count is an asynchronous query, and whether its
 *   answer has arrived yet is a property of this render pass, not of the membership;
 * - the **create form** — the event name and date range a host types before an event exists. It is the
 *   one decision surface this change did not lift, and it is called out in `design.md` rather than left
 *   as an unexplained exemption.
 *
 * Text typed into a sheet is the stated IME exception (`sync-status-screen`) and lives in
 * `:ui:components`, which this gate does not scan: a design-system control may own how it draws itself.
 */
class ScreenStateContainmentTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val screensSrc = File(repoRoot, "ui/screens/src/commonMain")

    /**
     * The files permitted to hold Compose-remembered state, each with the reason it is not "what is
     * shown". Keyed by file name because the reason is a property of the surface, not of the line.
     */
    private val allowed = mapOf(
        "JoinReadySurface.kt" to "the share-count row's in-flight state — an async query's arrival, not a membership fact",
        "CreateEventScreen.kt" to "the create form — the one decision surface this change did not lift",
    )

    @Test
    fun `the screens module holds no state outside the named allowlist`() {
        val sources = screensSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
            .toList()
        assertTrue(sources.isNotEmpty(), "screen-state gate scanned zero sources — ui/screens/src moved")

        val holders = sources.filter { file ->
            file.readLines().any { line ->
                val code = line.substringBefore("//")
                "mutableStateOf" in code && !code.trimStart().startsWith("import ")
            }
        }.map { it.name }.toSortedSet()

        assertEquals(
            allowed.keys.toSortedSet(),
            holders,
            "\nScreen-held state drifted from the allowlist.\n" +
                "  Each entry must name why the state is HOW the screen draws rather than WHAT it shows;\n" +
                "  anything that decides what is on screen belongs in the reduction (see this test's KDoc).\n",
        )
    }

    @Test
    fun `every allowlist entry still exists and still holds state`() {
        // An entry that outlived its file is a permission nobody is using, and the next reader would read
        // it as a standing exemption rather than a spent one.
        allowed.forEach { (name, reason) ->
            val file = screensSrc.walkTopDown().firstOrNull { it.name == name }
                ?: fail("allowlisted `$name` no longer exists — drop the entry ($reason)")
            assertTrue(
                file.readText().contains("mutableStateOf"),
                "allowlisted `$name` no longer holds state — drop the entry ($reason)",
            )
        }
    }
}
