package app.snapsync.buildlogic

/**
 * The source scan behind [CoverageZero]'s Compose declaration glue: where each `@Composable` function's declaration
 * lines are. [CoverageZero] checks the scan against the bytecode, so a misread signature fails the build.
 */
object ComposableDeclarations {

    /** A scanned `@Composable` declaration: the function's simple name and its declaration lines (1-based). */
    data class Declaration(val name: String, val lines: IntRange)

    private val ANNOTATION = Regex("""^\s*@Composable\b(?!\s*\()""")
    private val FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?,\s]+\.)?`?(\w+)`?\s*[(<]""")

    /** How many lines, the annotation's own included, the `fun` an annotation opens may sit within. */
    private const val LOOKAHEAD = 6

    private val DECLARATION_START = Regex(
        """^\s*(@|fun\b|val\b|var\b|class\b|interface\b|object\b|override\b|private\b|internal\b|public\b""" +
            """|protected\b|abstract\b|\})""",
    )

    /**
     * Every `@Composable` function DECLARATION in [lines] with a body: the annotation (on its own line or before the
     * modifiers) opens one, the `fun` line starts its range, and the range ends on the line holding the body's opening
     * `{` or `=`, found by balancing parentheses after the parameter list opens — so a function-type parameter's `->`
     * and a default value's `=` or `{` inside the list end nothing. A declaration with no body (abstract) is skipped:
     * it compiles to no code. String and character literals and `//` comments are not read as brackets.
     */
    fun scan(lines: List<String>): List<Declaration> {
        val out = mutableListOf<Declaration>()
        var i = 0
        while (i < lines.size) {
            val funLine = annotatedFun(lines, i)
            val end = funLine?.let { bodyStart(lines, it) }
            if (funLine != null && end != null) {
                out += Declaration(FUN.find(lines[funLine])!!.groupValues[1], (funLine + 1)..(end + 1))
            }
            i = (end ?: funLine ?: i) + 1
        }
        return out
    }

    /** The 0-based `fun` line the `@Composable` annotation on line [at] opens, or null when [at] holds none. */
    private fun annotatedFun(lines: List<String>, at: Int): Int? =
        if (!ANNOTATION.containsMatchIn(lines[at])) {
            null
        } else {
            (at until minOf(lines.size, at + LOOKAHEAD)).firstOrNull { FUN.containsMatchIn(lines[it]) }
        }

    /** The 0-based line holding the body's `{` or `=`, or null when the declaration has none. */
    private fun bodyStart(lines: List<String>, from: Int): Int? {
        val signature = Signature()
        for (n in from until lines.size) {
            val code = codeOf(lines[n])
            if (n > from && signature.afterParameters && startsNewDeclaration(code)) break
            if (signature.opensBody(code)) return n
        }
        return null
    }

    /** A declaration's signature read line by line, balancing parentheses from its `fun` line on. */
    private class Signature {
        private var depth = 0
        private var opened = false

        /** Whether the parameter list has opened and closed again, so a `{` or `=` now is the body's. */
        val afterParameters: Boolean get() = opened && depth == 0

        /** Reads [code] up to the body's opening `{` or `=`: whether it holds one. */
        fun opensBody(code: String): Boolean = code.any { ch ->
            when (ch) {
                '(' -> {
                    depth++
                    opened = true
                }
                ')' -> depth--
            }
            afterParameters && (ch == '{' || ch == '=')
        }
    }

    private fun startsNewDeclaration(code: String) = code.isNotBlank() && DECLARATION_START.containsMatchIn(code)

    /** [line] with string/char literal contents and a trailing `//` comment blanked. */
    internal fun codeOf(line: String): String {
        val sb = StringBuilder()
        var quote: Char? = null
        var k = 0
        while (k < line.length) {
            val ch = line[k]
            when {
                quote != null && ch == '\\' -> {
                    sb.append(if (k + 1 < line.length) "  " else " ")
                    k++
                }
                quote != null && ch == quote -> {
                    quote = null
                    sb.append(ch)
                }
                quote != null -> sb.append(' ')
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    sb.append(ch)
                }
                ch == '/' && line.getOrNull(k + 1) == '/' -> return sb.toString()
                else -> sb.append(ch)
            }
            k++
        }
        return sb.toString()
    }
}
