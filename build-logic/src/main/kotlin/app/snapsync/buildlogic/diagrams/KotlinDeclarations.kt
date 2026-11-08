package app.snapsync.buildlogic.diagrams

/** A named type declaration found in Kotlin source text: its kind, simple name and simple-name supertypes. */
data class Declaration(val kind: String, val name: String, val supertypes: List<String>, val topLevel: Boolean)

/**
 * A light scan of Kotlin source TEXT for type declarations and their supertypes — enough to draw a diagram from, never
 * a parser. Comments are blanked first, so commented-out code declares nothing; string literals are kept, so a `//`
 * inside one is no comment.
 */
object KotlinDeclarations {
    private val DECLARATION = Regex("""\b(class|object|interface)\s+([A-Z][A-Za-z0-9_]*)""")

    /** Every `class`, `object` and `interface` declared in [text], in source order. */
    fun of(text: String): List<Declaration> {
        val code = CommentBlanker(text).blanked()
        // `::class` and a qualified `.class` are references, not declarations.
        return DECLARATION.findAll(code).filterNot { it.range.first > 0 && code[it.range.first - 1] in ":." }.map {
            Declaration(
                kind = it.groupValues[1],
                name = it.groupValues[2],
                supertypes = supertypes(header(code, it.range.last + 1)),
                topLevel = topLevel(code, it.range.first),
            )
        }.toList()
    }

    /** Top level: the declaration's line starts at column 0 — modifiers may precede the keyword, indentation never. */
    private fun topLevel(code: String, at: Int): Boolean {
        val lineStart = code.lastIndexOf('\n', at) + 1
        return lineStart == at || !code[lineStart].isWhitespace()
    }

    /**
     * The header from just past the type name to its body: balanced through the primary constructor's parentheses,
     * ended by the first `{` outside them, or by a line end that does not continue the supertype list.
     */
    private fun header(code: String, from: Int): String {
        var depth = 0
        var i = from
        while (i < code.length && !(depth == 0 && endsHeader(code, from, i))) {
            when (code[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return code.substring(from, i)
    }

    private fun endsHeader(code: String, from: Int, at: Int): Boolean = when (code[at]) {
        '{' -> true
        '\n' -> code.substring(from, at).trim().let { it.isEmpty() || !(it.endsWith(":") || it.endsWith(",")) }
        else -> false
    }

    /** The simple names of the supertypes in [header]: generics, constructor arguments and `by` delegates stripped. */
    private fun supertypes(header: String): List<String> {
        val colon = supertypeColon(header) ?: return emptyList()
        return splitTopLevel(header.substring(colon + 1))
            .map { it.substringBefore(" by ").substringBefore('(').substringBefore('<').trim().substringAfterLast('.') }
            .filter { it.firstOrNull()?.isUpperCase() == true }
    }

    /**
     * The colon that opens the supertype list: outside the constructor's parentheses and outside type parameters, so a
     * parameter's type or a generic bound is never taken for it.
     */
    private fun supertypeColon(header: String): Int? {
        var paren = 0
        var angle = 0
        header.forEachIndexed { i, c ->
            when (c) {
                '(' -> paren++
                ')' -> paren--
                '<' -> if (paren == 0) angle++
                '>' -> if (paren == 0) angle--
                ':' -> if (paren == 0 && angle == 0) return i
            }
        }
        return null
    }

    private fun splitTopLevel(list: String): List<String> {
        val parts = mutableListOf(StringBuilder())
        var depth = 0
        list.forEach { c ->
            when (c) {
                '(', '<' -> depth++
                ')', '>' -> depth--
            }
            if (c == ',' && depth == 0) parts += StringBuilder() else parts.last().append(c)
        }
        return parts.map { it.toString() }
    }
}

/**
 * Kotlin source with its comments — line, block and nested block — replaced by spaces, every offset and newline kept.
 * String and character literals are kept as they are, escapes included.
 */
internal class CommentBlanker(private val text: String) {
    private val out = StringBuilder(text.length)
    private var i = 0

    fun blanked(): String {
        while (i < text.length) step()
        return out.toString()
    }

    private fun step() {
        when {
            starts("//") -> while (i < text.length && text[i] != '\n') blank()
            starts("/*") -> blankBlock()
            starts(RAW) -> keepRaw()
            text[i] == '"' || text[i] == '\'' -> keepQuoted(text[i])
            else -> keep(1)
        }
    }

    private fun starts(token: String) = text.startsWith(token, i)

    private fun keep(n: Int) {
        val end = minOf(i + n, text.length)
        out.append(text, i, end)
        i = end
    }

    private fun blank() {
        out.append(if (text[i] == '\n') '\n' else ' ')
        i++
    }

    private fun blankBlock() {
        var depth = 0
        do {
            when {
                starts("/*") -> depth++.also { repeat(2) { blank() } }
                starts("*/") -> depth--.also { repeat(2) { blank() } }
                else -> blank()
            }
        } while (i < text.length && depth > 0)
    }

    private fun keepRaw() {
        keep(RAW.length)
        while (i < text.length && !starts(RAW)) keep(1)
        keep(RAW.length)
    }

    private fun keepQuoted(quote: Char) {
        keep(1)
        while (i < text.length && text[i] != quote && text[i] != '\n') keep(if (text[i] == '\\') 2 else 1)
        if (i < text.length && text[i] == quote) keep(1)
    }

    private companion object {
        const val RAW = "\"\"\""
    }
}
