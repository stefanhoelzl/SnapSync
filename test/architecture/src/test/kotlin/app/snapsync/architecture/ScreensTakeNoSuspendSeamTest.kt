package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`:ui:screens` takes no `suspend` seam** (`docs/architecture.md`, presentation zone; decision record
 * `harden-seam-bug-classes`, G5).
 *
 * A composable that is handed a `suspend` function calls it from a `LaunchedEffect` — on the main thread, and outside
 * the container's lanes, the command door and its in-flight guards. That is how the shareable count ran a download
 * store read on the main thread (B6's thread half) while no lane gate could see it. Screens render state and fire
 * plain callbacks; anything that suspends belongs to the container, which reduces its result into state.
 *
 * So no `suspend` FUNCTION TYPE may appear in `:ui:screens`' production source — a parameter, a property, a type
 * alias. A `suspend fun` the screen declares for its own animation is not a seam and is not matched.
 *
 * **Text, and it says so:** it matches `suspend (` after comments are stripped; a function type reached through a
 * type alias declared in another module is not seen.
 */
class ScreensTakeNoSuspendSeamTest {

    private val suspendType = Regex("""\bsuspend\s*\(""")

    internal fun offenders(raw: String): List<Int> {
        val code = ZoneGates.stripComments(raw)
        return suspendType.findAll(code).map { m -> code.take(m.range.first).count { it == '\n' } + 1 }.toList()
    }

    @Test
    fun `screens receive no suspend function`() {
        val files = SourceScan.kotlinFiles().filter { it.path.startsWith("/ui/screens/src/commonMain/") }
        assertTrue(files.size >= 5, "the screens gate scanned only ${files.size} files — the scope is broken")
        val found = files.flatMap { f -> offenders(f.text).map { "  ${f.path}:$it" } }
        assertTrue(
            found.isEmpty(),
            "`:ui:screens` takes a suspend function here. Screens render state and fire plain callbacks; move the " +
                "suspending work into the container, behind the command or query door, and reduce its result into " +
                "state.\n" + found.joinToString("\n"),
        )
    }

    @Test
    fun `the scan sees a suspend seam and ignores a suspend fun`() {
        val sample = """
            @Composable
            fun Row(count: suspend (Int) -> Int?, onTap: () -> Unit) {}
            class Actions(val load: suspend () -> Unit)
            private suspend fun animate() {}
        """.trimIndent()
        assertEquals(listOf(2, 3), offenders(sample))
    }
}
