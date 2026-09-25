package app.snapsync.architecture

/**
 * **Just enough declaration parsing for the seam-shape gates** (`docs/architecture.md`): constructor
 * parameter lists, property declarations, and whether a declared type is a function type.
 *
 * Text, not a resolved model, for [SourceScan]'s reasons — it must reach `iosMain`, which has no JVM bytecode.
 * Every caller strips comments first ([ZoneGates.stripComments]), so KDoc that names a field a change deleted
 * is never read as live.
 *
 * The one subtlety every function here shares: `->` is consumed as ONE token, so its `>` never reads as a
 * closing generic bracket — the difference between seeing `() -> Unit` and seeing garbage.
 */
internal object KotlinDecls {

    /** A declared parameter or property: its name, its type as written, and whether it declares a default. */
    data class Decl(val owner: String, val name: String, val type: String, val hasDefault: Boolean, val line: Int)

    /**
     * Every primary- and secondary-constructor parameter declared in [code] (comments already stripped), with
     * the class that declares it. A secondary constructor's parameters are reported under its class too.
     */
    fun constructorParams(code: String): List<Decl> {
        val out = mutableListOf<Decl>()
        val header = Regex(
            """\bclass\s+(\w+)\s*(?:<[^>{(]*>)?\s*(?:(?:private|internal|public|protected)\s+)?(?:constructor\s*)?\(""",
        )
        header.findAll(code).forEach { m -> out += paramsAt(code, m.range.last, m.groupValues[1]) }
        // Secondary constructors: `constructor(` inside a class body, owned by the nearest preceding class.
        Regex("""(?<![\w.])constructor\s*\(""").findAll(code).forEach { m ->
            val before = code.substring(0, m.range.first)
            if (Regex("""\bclass\s+\w+\s*(?:<[^>{(]*>)?\s*(?:(?:private|internal|public|protected)\s+)?$""").containsMatchIn(before)) {
                return@forEach // a primary constructor, already reported
            }
            val owner = Regex("""\bclass\s+(\w+)""").findAll(before).lastOrNull()?.groupValues?.get(1) ?: "?"
            out += paramsAt(code, m.range.last, owner)
        }
        return out
    }

    /** Every `var` declared in [code] with a type written out, and its type. */
    fun varDecls(code: String): List<Decl> =
        Regex("""\bvar\s+(\w+)\s*:""").findAll(code).map { m ->
            val typeStart = m.range.last + 1
            val end = typeEnd(code, typeStart)
            Decl(
                owner = "",
                name = m.groupValues[1],
                type = code.substring(typeStart, end).trim(),
                hasDefault = code.getOrNull(end) == '=',
                line = code.take(m.range.first).count { it == '\n' } + 1,
            )
        }.toList()

    /**
     * Whether [type] is a function type: a `->` at nesting depth 0 (`suspend (A) -> B`, `() -> (X) -> Y`), or a
     * nullable one written `(… -> …)?`, whose arrow sits one level down.
     */
    fun isFunctionType(type: String): Boolean {
        val t = type.trim()
        var arrow = false
        scan(t, null) { arrow = true }
        if (arrow) return true
        return t.startsWith("(") && t.endsWith(")?") && isFunctionType(t.substring(1, t.length - 2))
    }

    private fun paramsAt(code: String, open: Int, owner: String): List<Decl> {
        val close = balanced(code, open) ?: return emptyList()
        return splitTopLevel(code.substring(open + 1, close)).mapNotNull { (text, offset) ->
            val decl = text.trim()
            if (decl.isBlank()) return@mapNotNull null
            val colon = indexOfTopLevel(decl, ':') ?: return@mapNotNull null
            val rest = decl.substring(colon + 1)
            val eq = indexOfTopLevel(rest, '=')
            Decl(
                owner = owner,
                name = decl.take(colon).trim().substringAfterLast(' '),
                type = (eq?.let { rest.take(it) } ?: rest).trim(),
                hasDefault = eq != null,
                line = code.take(open + 1 + offset).count { it == '\n' } + 1,
            )
        }
    }

    /** Where a property's type ends: the first `=`, `{`, newline or `by` at depth 0. */
    private fun typeEnd(code: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < code.length) {
            if (code.startsWith("->", i)) { i += 2; continue }
            val c = code[i]
            when {
                c in "(<[" -> depth++
                c in ")>]" -> { if (depth == 0) return i; depth-- }
                depth == 0 && (c == '=' || c == '{' || c == '\n' || c == ',') -> return i
                depth == 0 && code.startsWith(" by ", i) -> return i
            }
            i++
        }
        return code.length
    }

    /** The index of the `)` closing the `(` at [open], or null. */
    private fun balanced(code: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code.startsWith("->", i)) { i += 2; continue }
            when (code[i]) {
                '"' -> { i++; while (i < code.length && code[i] != '"') { if (code[i] == '\\') i++; i++ } }
                '(', '{', '[' -> depth++
                ')', '}', ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun scan(text: String, stop: Char?, onTop: (Int) -> Unit) {
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("->", i) -> { if (depth == 0 && stop == null) onTop(i); i += 2; continue }
                c == '"' -> { i++; while (i < text.length && text[i] != '"') i++ }
                c in "(<{[" -> depth++
                c in ")>}]" -> depth--
                depth == 0 && stop != null && c == stop -> onTop(i)
            }
            i++
        }
    }

    private fun splitTopLevel(text: String): List<Pair<String, Int>> {
        val cuts = mutableListOf<Int>()
        scan(text, ',') { cuts += it }
        var start = 0
        return (cuts + text.length).map { cut -> (text.substring(start, cut) to start).also { start = cut + 1 } }
    }

    private fun indexOfTopLevel(text: String, char: Char): Int? {
        var found: Int? = null
        scan(text, char) { if (found == null) found = it }
        return found
    }
}
