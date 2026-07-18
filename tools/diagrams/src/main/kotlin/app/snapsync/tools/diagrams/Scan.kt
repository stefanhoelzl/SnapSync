package app.snapsync.tools.diagrams

import java.io.File

/**
 * Shared source-scanning infrastructure for the derived diagrams (capability
 * `architecture-diagrams`). Everything here is a directory walk plus text heuristics — the scope
 * is always DERIVED, never a hand-enumerated file list, so the diagrams track the migration
 * automatically as modules move.
 *
 * Determinism (spec requirement): every collection is sorted with a fixed code-point comparator
 * (`compareBy { it }` on strings), output uses hardcoded `"\n"` and explicit UTF-8, and no
 * timestamp or absolute path ever reaches an output file.
 */

/** A Kotlin source file: repo-relative path, owning Gradle module, raw text, comment-blanked text. */
data class KtSource(val relPath: String, val module: String?, val text: String, val stripped: String) {
    private val lineStarts: IntArray by lazy {
        val starts = ArrayList<Int>()
        starts.add(0)
        text.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
        starts.toIntArray()
    }

    /** 1-based line number of a character offset (offsets are identical in [text] and [stripped]). */
    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}

/** Walk up from [start] to the directory holding `settings.gradle.kts` (the repository root). */
fun repoRoot(start: File = File(System.getProperty("user.dir"))): File {
    var dir: File? = start.absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("settings.gradle.kts not found above ${start.absolutePath}")
}

/** Every `include(":…")` path from `settings.gradle.kts`, code-point sorted. */
fun includedModules(root: File): List<String> =
    Regex("include\\(\"(:[^\"]+)\"\\)")
        .findAll(File(root, "settings.gradle.kts").readText(Charsets.UTF_8))
        .map { it.groupValues[1] }
        .sortedWith(compareBy { it })
        .toList()

/** `:ui:components` → `ui/components`. */
fun moduleDir(path: String): String = path.trimStart(':').replace(':', '/')

/** The longest-prefix module owning [relPath], or null when no included module contains it. */
fun moduleForFile(relPath: String, modules: List<String>): String? =
    modules
        .map { it to moduleDir(it) + "/" }
        .filter { relPath.startsWith(it.second) }
        .maxByOrNull { it.second.length }
        ?.first

/**
 * Blank out comments (line, nested block, KDoc) with spaces, preserving every character offset and
 * newline, so scans over the result never match commented-out code yet report real line numbers.
 * String literals are preserved (a `//` inside a string is not a comment).
 */
fun stripComments(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    var blockDepth = 0
    var inLine = false
    var quote: Char? = null
    var triple = false
    fun keep(c: Char) = out.append(if (c == '\n') '\n' else c)
    fun blank(c: Char) = out.append(if (c == '\n') '\n' else ' ')
    while (i < text.length) {
        val c = text[i]
        val n = if (i + 1 < text.length) text[i + 1] else ' '
        when {
            inLine -> {
                if (c == '\n') inLine = false
                blank(c); i++
            }
            blockDepth > 0 -> when {
                c == '/' && n == '*' -> { blockDepth++; blank(c); blank(n); i += 2 }
                c == '*' && n == '/' -> { blockDepth--; blank(c); blank(n); i += 2 }
                else -> { blank(c); i++ }
            }
            quote != null -> when {
                triple && c == '"' && n == '"' && i + 2 < text.length && text[i + 2] == '"' -> {
                    out.append("\"\"\""); quote = null; triple = false; i += 3
                }
                !triple && c == '\\' && i + 1 < text.length -> { keep(c); keep(n); i += 2 }
                !triple && c == quote -> { keep(c); quote = null; i++ }
                else -> { keep(c); i++ }
            }
            c == '/' && n == '/' -> { inLine = true; blank(c); blank(n); i += 2 }
            c == '/' && n == '*' -> { blockDepth = 1; blank(c); blank(n); i += 2 }
            c == '"' -> {
                if (n == '"' && i + 2 < text.length && text[i + 2] == '"') {
                    triple = true; quote = '"'; out.append("\"\"\""); i += 3
                } else {
                    quote = '"'; keep(c); i++
                }
            }
            c == '\'' -> { quote = '\''; keep(c); i++ }
            else -> { keep(c); i++ }
        }
    }
    return out.toString()
}

/**
 * Every Kotlin source under the scanned top-level trees, module-mapped and comment-blanked.
 * `build/` output is excluded — generated sources are other tasks' outputs, not diagram subjects
 * (the same rule `:test:architecture` applies to its guard inputs).
 */
fun kotlinSources(root: File): List<KtSource> {
    val modules = includedModules(root)
    val out = mutableListOf<KtSource>()
    for (top in listOf("adapter", "app", "capability", "domain", "test", "ui")) {
        val dir = File(root, top)
        if (!dir.isDirectory) continue
        dir.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (!rel.contains("/src/")) return@forEach
                val text = f.readText(Charsets.UTF_8)
                out += KtSource(rel, moduleForFile(rel, modules), text, stripComments(text))
            }
    }
    return out.sortedWith(compareBy { it.relPath })
}

/** A `class`/`object`/`interface` declaration with its resolved simple-name supertypes. */
data class KtDeclaration(
    val kind: String,
    val name: String,
    val supertypes: List<String>,
    val offset: Int,
    val topLevel: Boolean,
)

private val DECLARATION = Regex("\\b(class|object|interface)\\s+([A-Z][A-Za-z0-9_]*)")

/** All named type declarations in a comment-blanked source. */
fun declarations(source: KtSource): List<KtDeclaration> =
    DECLARATION.findAll(source.stripped).map { m ->
        // `class`/`object`/`interface` also appear as soft tokens (e.g. `::class`); requiring the
        // keyword to not follow `:` or `.` filters class-literals and qualified references.
        val before = if (m.range.first > 0) source.stripped[m.range.first - 1] else ' '
        if (before == ':' || before == '.') return@map null
        // Top-level = the declaration's line begins at column 0 (modifiers may precede the keyword
        // on that line, but never indentation) — nested/companion declarations are indented.
        val lineStart = source.stripped.lastIndexOf('\n', m.range.first) + 1
        val prefix = source.stripped.substring(lineStart, m.range.first)
        val topLevel = prefix.isEmpty() || !prefix.first().isWhitespace()
        val header = headerAfter(source.stripped, m.range.last + 1)
        KtDeclaration(
            kind = m.groupValues[1],
            name = m.groupValues[2],
            supertypes = supertypesIn(header),
            offset = m.range.first,
            topLevel = topLevel,
        )
    }.filterNotNull().toList()

/**
 * The declaration header from just past the type name to its body brace: balanced through the
 * primary constructor's parentheses, ended by the first `{` at depth 0 or a line that completes a
 * bodyless declaration.
 */
private fun headerAfter(text: String, from: Int): String {
    var i = from
    var depth = 0
    val sb = StringBuilder()
    while (i < text.length && sb.length < 8000) {
        val c = text[i]
        when (c) {
            '(' -> depth++
            ')' -> depth--
            '{' -> if (depth == 0) return sb.toString()
            '\n' -> if (depth == 0) {
                val soFar = sb.toString().trim()
                if (soFar.isEmpty() || !(soFar.endsWith(":") || soFar.endsWith(","))) {
                    return sb.toString()
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/** Simple names of the supertypes in a declaration header (generics, arguments, `by` stripped). */
private fun supertypesIn(header: String): List<String> {
    // Angle depth is tracked only outside parentheses: at paren depth 0 a `<`/`>` in a header can
    // only be generics (a `->` function type always sits inside the constructor's parentheses),
    // so a generic constraint's `:` is never mistaken for the supertype-list colon.
    var depth = 0
    var angle = 0
    var colon = -1
    for ((i, c) in header.withIndex()) {
        when (c) {
            '(' -> depth++
            ')' -> depth--
            '<' -> if (depth == 0) angle++
            '>' -> if (depth == 0) angle--
            ':' -> if (depth == 0 && angle == 0) { colon = i; break }
        }
    }
    if (colon < 0) return emptyList()
    val list = header.substring(colon + 1)
    val entries = mutableListOf<String>()
    val current = StringBuilder()
    depth = 0
    for (c in list) {
        when (c) {
            '(', '<' -> depth++
            ')', '>' -> depth--
            ',' -> if (depth == 0) { entries += current.toString(); current.clear(); continue }
        }
        current.append(c)
    }
    entries += current.toString()
    return entries
        .map { it.substringBefore(" by ").trim() }
        .map { it.substringBefore('(').substringBefore('<').trim().substringAfterLast('.') }
        .filter { it.isNotEmpty() && it.first().isUpperCase() }
}
