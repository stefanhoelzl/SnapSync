package app.snapsync.buildlogic

import java.io.File
import java.nio.file.FileSystems

/**
 * **Every detekt rule's `includes` glob matches a file** (`docs/architecture.md`, "Complexity ceilings"). A rule reads
 * only the files its `includes` names, so a glob that matches nothing switches the rule off without a word — the
 * failure mode of a moved or renamed module (the core moved from `domain/` to `core/` in phase 5). Checked by the
 * `detektIncludesMatch` task on every `check`.
 */
object DetektIncludes {
    /** The `includes` globs of [config], a detekt YAML file's text, in order. */
    fun globs(config: String): List<String> {
        val out = mutableListOf<String>()
        var inIncludes = false
        for (line in config.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("includes:") -> {
                    inIncludes = true
                    inlineList(trimmed.removePrefix("includes:").trim())?.let(out::addAll)
                }
                inIncludes && trimmed.startsWith("- ") -> out += unquoted(trimmed.removePrefix("- "))
                inIncludes && (trimmed.isEmpty() || trimmed.startsWith("#")) -> Unit
                else -> inIncludes = false
            }
        }
        return out
    }

    /** The globs among [globs] that match none of [files] (absolute paths, `/`-separated). */
    fun unmatched(globs: List<String>, files: List<String>): List<String> = globs.distinct().filter { glob ->
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        files.none { matcher.matches(File(it).toPath()) }
    }

    /** Every `.kt` file under [root]'s source trees, outside build output. */
    fun sources(root: File): List<String> = SOURCE_TREES.flatMap { tree ->
        File(root, tree).walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.absoluteFile.invariantSeparatorsPath }
            .toList()
    }

    private fun inlineList(rest: String): List<String>? =
        rest.takeIf { it.startsWith("[") }?.removeSurrounding("[", "]")?.split(',')
            ?.map { unquoted(it.trim()) }?.filter { it.isNotEmpty() }

    private fun unquoted(value: String): String =
        value.substringBefore(" #").trim().removeSurrounding("'").removeSurrounding("\"")

    private val SOURCE_TREES = listOf("core", "adapter", "app", "ui", "test", "build-logic")
}
