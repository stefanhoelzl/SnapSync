package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **What the screen SHOWS is `UiState`; how it DRAWS is local** (capability `sync-status`).
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
 * The create form was the one decision surface left here until its draft and the range calendar's rules moved into the
 * reduction (`ports-entry-ui`); what remains on the list is input mechanics and drawing, each entry naming why.
 *
 * The share-count row's in-flight state was a second entry until the count moved into the reduction
 * (`harden-seam-bug-classes`): the container computes it over the lane-decorated query bundle and the row
 * renders it, so the screen holds nothing for it.
 *
 * The design system is scanned too, against its own list: since the range calendar's rules left it, a component holds
 * only how it draws — a dialog's visibility, a scroll, a gesture in progress, the text typed into a sheet (the stated
 * IME exception, `sync-status`).
 */
class ScreenStateContainmentTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /**
     * Each scanned source set and the files in it permitted to hold Compose-remembered state, each with the reason it is
     * not "what is shown". Keyed by file name because the reason is a property of the surface, not of the line.
     */
    private val allowed = mapOf(
        File(repoRoot, "ui/screens/src/commonMain") to mapOf(
            "CreateEventScreen.kt" to "the name field's edit buffer — input mechanics, like the cursor: a field fed " +
                "from the reduction a frame late loses keystrokes; the name that decides is the reduction's",
        ),
        File(repoRoot, "ui/components/src/commonMain") to mapOf(
            "AppJoinGate.kt" to "whether the access explainer's sheet is open — a dialog's visibility, raising nothing",
            "AppEventRangePicker.kt" to "which month the calendar shows — a scroll, not a choice",
            "AppSettlingWheels.kt" to "a wheel's drag and tap in progress — a gesture, reported when it settles",
            "AppTextPromptSheet.kt" to "the text typed into a sheet — the stated IME exception",
        ),
    )

    @Test
    fun `each scanned module holds no state outside its named allowlist`() {
        allowed.forEach { (src, permitted) ->
            val sources = src.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
                .toList()
            assertTrue(sources.isNotEmpty(), "screen-state gate scanned zero sources — $src moved")

            val holders = sources.filter { file ->
                file.readLines().any { line ->
                    val code = line.substringBefore("//")
                    "mutableStateOf" in code && !code.trimStart().startsWith("import ")
                }
            }.map { it.name }.toSortedSet()

            assertEquals(
                permitted.keys.toSortedSet(),
                holders,
                "\nScreen-held state drifted from the allowlist of $src.\n" +
                    "  Each entry must name why the state is HOW the screen draws rather than WHAT it shows;\n" +
                    "  anything that decides what is on screen belongs in the reduction (see this test's KDoc).\n",
            )
        }
    }

    @Test
    fun `every allowlist entry still exists and still holds state`() {
        // An entry that outlived its file is a permission nobody is using, and the next reader would read
        // it as a standing exemption rather than a spent one.
        allowed.forEach { (src, permitted) ->
            permitted.forEach { (name, reason) ->
                val file = src.walkTopDown().firstOrNull { it.name == name }
                    ?: fail("allowlisted `$name` no longer exists — drop the entry ($reason)")
                assertTrue(
                    file.readText().contains("mutableStateOf"),
                    "allowlisted `$name` no longer holds state — drop the entry ($reason)",
                )
            }
        }
    }
}
