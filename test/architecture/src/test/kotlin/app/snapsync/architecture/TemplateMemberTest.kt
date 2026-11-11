package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **A string template names a member in braces.** Kotlin reads `$Holder.NAME` inside a string as the template
 * `$Holder` followed by the text `.NAME`: the object's own `toString()` — `app.snapsync.format.DeviceLogText@3c4e6e0` —
 * and then the member's name, never its value. It compiles, it runs, and the string is wrong. Measured on the SE2
 * (2026-11-11): every iOS log went to `Documents/app.snapsync.format.DeviceLogText@3c4e6e0.APP_LOG`, so the bug
 * report's log tail, which reads `debug.log`, was empty — and the test beside it passed, because it built its expected
 * path with the same slip. `${Holder.NAME}` is the only form that reads the member.
 *
 * Scope: every `.kt` and `.kts`, test sources included — a test is where the slip hid.
 *
 * HEURISTIC, and it says so: it matches a `$` and a capitalised camel-case name followed by `.` and a member, after an
 * opening `"` on the same line. An all-caps constant (`$KEY.OFFLINE`) reads as the constant's value followed by text,
 * which is what such a template means, so it is not matched; a member read across a line break is not caught.
 */
class TemplateMemberTest {

    private val sources = SourceScan.files(setOf("kt", "kts"))
    private val slip = Regex("\"[^\"\\n]*(?<!\\\\)\\$[A-Z][a-z][A-Za-z0-9]*\\.[A-Za-z_]")

    @Test
    fun `the scan reaches the Kotlin sources`() {
        assertTrue(sources.size > 100, "only ${sources.size} Kotlin sources scanned — the layout moved")
        assertTrue(slip.containsMatchIn("\"\$it/\$Holder.NAME\""), "the pattern no longer matches the slip it is for")
        assertTrue(!slip.containsMatchIn("\"\$it/\${Holder.NAME}\""), "the pattern matches the braced form")
    }

    @Test
    fun `no string template reads a member without braces`() {
        val hits = sources.flatMap { source ->
            source.text.lineSequence().mapIndexedNotNull { index, line ->
                if (slip.containsMatchIn(line)) "  ${source.path}:${index + 1}  ${line.trim()}" else null
            }.toList()
        }
        if (hits.isEmpty()) return
        fail(
            "a template reads the holder, not its member (${hits.size}):\n" + hits.joinToString("\n") +
                "\nWrite \${Holder.NAME}: \$Holder.NAME is the holder's toString() followed by the text \".NAME\".",
        )
    }
}
