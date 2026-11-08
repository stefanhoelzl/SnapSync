package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What happened is a literal; what it happened with is a field** (`docs/patterns.md`, "Logging"). Every structured
 * log call's event, and every `InvalidProgramState`'s guarantee, is a string literal with no template: a line reads —
 * and a failure report is titled and grouped — by what happened, never by the ids and values it happened with, which
 * ride as fields. An event that interpolates splits one cause into one report per value and puts ids in the operator's
 * overview.
 *
 * Read as text, comments stripped: a call's first argument is a syntactic shape. A file is scanned for `Log` calls when
 * it imports the structured log — every adapter and root does: it is the only log there is.
 */
class EventNameLiteralTest {

    private val roots = listOf("/core/", "/adapter/", "/app/", "/ui/")

    private fun isProduction(path: String): Boolean {
        val sourceSet = path.substringAfter("/src/", "").substringBefore('/')
        return roots.any { path.startsWith(it) } && sourceSet.isNotEmpty() && !sourceSet.endsWith("Test") &&
            sourceSet != "test"
    }

    /** A structured log call: `log.i(`, `log.unlabelled.e(`, `Log(tag).unlabelled.w(`. */
    private val logCall = Regex("""\.(?:d|i|w|e)\(""")

    private val guarantee = Regex("""(?<!class )InvalidProgramState\(""")

    @Test
    fun `every log event and every program-state guarantee is a literal`() {
        val files = SourceScan.kotlinFiles().filter { isProduction(it.path) }
        assertTrue(files.size >= 300, "scanned only ${files.size} production files — the source tree moved")
        val logging = files.filter {
            "import app.snapsync.inbound.Log\n" in it.text || "package app.snapsync.inbound\n" in it.text
        }
        assertTrue(logging.size >= 50, "only ${logging.size} files write the structured log — the scan reads nothing")

        val violations = logging.flatMap { source -> nonLiterals(source, logCall) } +
            files.flatMap { source -> nonLiterals(source, guarantee) }

        assertEquals(
            emptyList(),
            violations,
            "a log event or an InvalidProgramState guarantee that is not a plain literal — name what happened, and pass " +
                "what it happened with as a field (`\"eventId\" of id`)",
        )
    }

    /** Each call of [call] in [source] whose first argument is not a template-free string literal, as `path:line`. */
    private fun nonLiterals(source: SourceScan.Source, call: Regex): List<String> {
        val code = source.text.lines().joinToString("\n") { it.substringBefore("//") }
        return call.findAll(code).mapNotNull { match ->
            val start = code.indexOfFirstNonBlank(match.range.last + 1)
            val literal = start < code.length && code[start] == '"' && !code.startsWith("\"\"\"", start)
            when {
                literal -> if (code.literalAt(start).contains('$')) where(source, code, match.range.first) else null
                else -> where(source, code, match.range.first)
            }
        }.toList()
    }

    private fun where(source: SourceScan.Source, code: String, at: Int) =
        "${source.path}:${code.substring(0, at).count { it == '\n' } + 1}"

    private fun String.indexOfFirstNonBlank(from: Int): Int {
        var i = from
        while (i < length && this[i].isWhitespace()) i++
        return i
    }

    /** The string literal opening at [start], escapes kept. */
    private fun String.literalAt(start: Int): String {
        var i = start + 1
        while (i < length && this[i] != '"') i += if (this[i] == '\\') 2 else 1
        return substring(start + 1, minOf(i, length))
    }
}
