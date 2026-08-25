package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **A KDoc block is never silently dropped** (capability `architecture-guards`, "A KDoc block is never
 * silently dropped"; decision record: `correct-superseded-composition-claims`).
 *
 * Kotlin binds only the **last** KDoc block preceding a declaration. An earlier one is neither an error
 * nor a warning — the text simply stops being that declaration's documentation, with no symptom anywhere.
 * It arises the same way every time: a revised rationale or an "Absence:" note is added as a *second*
 * block instead of being merged into the existing one, and the block meant to be kept is the one that
 * disappears. Eleven sites accumulated this way, and what was dropped was load-bearing — the one-line
 * summaries of what `AttestStore.token()` and `keyId()` return, and the only statement of why the upload
 * lifecycle lives in tested `:domain` rather than the untested iOS composition root.
 *
 * **The file-header convention is exempt by construction, not by an exception list.** A file-level KDoc
 * documenting the file as a whole can only be the *first* block in the file, so requiring that a
 * declaration already appear earlier excludes every such header without naming one. A list of permitted
 * sites would itself be a duplicate that goes stale — the failure this capability exists to prevent.
 *
 * This pins a **documentation** invariant rather than a structural one, which is deliberate and narrow.
 * It is admissible because the rule is mechanical and total — it is the compiler's own binding rule, not
 * a style preference — and because the failure is silent. It must NOT be widened into a general prose or
 * content check: whether documentation is *correct* stays unguarded, and a green build here is no
 * evidence that it is.
 */
class StackedKDocTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** One KDoc block's line span, 0-indexed and inclusive. */
    private data class Block(val start: Int, val end: Int)

    private data class Finding(val file: File, val droppedAt: Int, val survivorAt: Int)

    /** Every `.kt` source that is ours: `build/` output and the immutable archive are not. */
    private val sources: List<File> = repoRoot.walkTopDown()
        .onEnter { it.name !in setOf("build", ".git", ".gradle", "node_modules", "archive") }
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /**
     * Scan one file: its KDoc blocks in order, and the line the first *declaration* appears on.
     *
     * A declaration is anything that is not blank, not a comment, and not `package` / `import` /
     * `@file:` — the only things that may precede a file header. Non-KDoc `/* */` comments are tracked
     * so their prose can never be mistaken for code.
     */
    private fun scan(lines: List<String>): Pair<List<Block>, Int?> {
        val blocks = mutableListOf<Block>()
        var firstDecl: Int? = null
        var kdocStart: Int? = null
        var inBlockComment = false
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            when {
                kdocStart != null -> if (line.endsWith("*/")) {
                    blocks += Block(kdocStart, i); kdocStart = null
                }
                inBlockComment -> if (line.endsWith("*/")) inBlockComment = false
                line.startsWith("/**") ->
                    if (line.endsWith("*/") && line.length > 4) blocks += Block(i, i) else kdocStart = i
                line.startsWith("/*") -> if (!line.endsWith("*/")) inBlockComment = true
                line.isEmpty() || line.startsWith("//") -> Unit
                line.startsWith("package ") || line.startsWith("import ") || line.startsWith("@file:") -> Unit
                firstDecl == null && (DECLARATION.any { line.startsWith(it) } || line.endsWith("{")) ->
                    firstDecl = i
            }
        }
        return blocks to firstDecl
    }

    /** A pair is stacked when only blank lines separate the two blocks. */
    private fun findings(file: File): List<Finding> {
        val lines = file.readText().split('\n')
        val (blocks, firstDecl) = scan(lines)
        if (firstDecl == null) return emptyList()
        return blocks.zipWithNext()
            .filter { (a, b) ->
                a.start > firstDecl &&
                    (a.end + 1 until b.start).all { lines[it].isBlank() }
            }
            .map { (a, b) -> Finding(file, a.start + 1, b.start + 1) }
    }

    private val all: List<Finding> by lazy { sources.flatMap(::findings) }

    @Test
    fun `the guard actually scanned something`() {
        // Non-vacuity twins: a refactor that empties either half must fail here, never pass silently.
        assertTrue(
            sources.size >= 100,
            "the guard scanned only ${sources.size} Kotlin files — the source walk is broken",
        )
        assertTrue(
            sources.any { scan(it.readText().split('\n')).first.isNotEmpty() },
            "no KDoc block was recognized in any file — the block scanner is broken",
        )
    }

    @Test
    fun `no declaration is preceded by two KDoc blocks`() {
        if (all.isEmpty()) return
        fail(
            buildString {
                appendLine("${all.size} KDoc block(s) are silently dropped — Kotlin binds only the last")
                appendLine("block before a declaration, so the earlier one documents nothing.")
                appendLine()
                appendLine("Merge each dropped block into the one below it; do not delete it, or the")
                appendLine("doc-loss this guard exists to catch is simply completed by hand.")
                appendLine()
                for (f in all) {
                    val rel = f.file.relativeTo(repoRoot).path
                    appendLine("  $rel:${f.droppedAt} is dropped by the block at :${f.survivorAt}")
                }
            },
        )
    }

    private companion object {
        val DECLARATION = listOf(
            "class ", "object ", "interface ", "fun ", "val ", "var ", "enum ", "data ", "sealed ",
            "private ", "internal ", "public ", "protected ", "abstract ", "open ", "typealias ",
            "const ", "expect ", "actual ", "suspend ", "annotation ", "companion ", "override ",
        )
    }
}
