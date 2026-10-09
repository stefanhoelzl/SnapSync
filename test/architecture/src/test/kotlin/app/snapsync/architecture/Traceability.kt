package app.snapsync.architecture

import java.io.File
import kotlin.test.assertTrue

/**
 * **The specs and the tests that claim them** — what [VerifiesGateTest], [NoSpecCitationTest] and the
 * `verifiesReport` task read.
 *
 * Specs are linked to code through tests only: a test carries `@Verifies(spec, requirement[, scenario])`
 * (`:test:control`'s `Verifies`), and production code cites no spec. Both sides are read as TEXT. The
 * journeys source set is on no classpath this module has, and the annotation is `SOURCE`-retained.
 */
internal object Traceability {

    /** One `### Requirement:` of a spec, with the `#### Scenario:` titles beneath it. */
    class Requirement(val title: String, val scenarios: List<String>)

    /** `openspec/specs/<name>/spec.md`, in file order. */
    class Spec(val name: String, val requirements: List<Requirement>) {
        fun requirement(title: String): Requirement? = requirements.firstOrNull { it.title == title }
    }

    /**
     * One `@Verifies` as written. [problem] is set when the annotation could not be read as named
     * string literals. The gate reports it rather than guessing.
     */
    class Claim(
        val path: String,
        val line: Int,
        val target: String,
        val sourceSet: String,
        val spec: String,
        val requirement: String,
        val scenario: String,
        val problem: String?,
    )

    /**
     * Every spec under `openspec/specs/`, derived from the directory. A capability added there is in scope
     * with no edit here.
     */
    fun specs(): List<Spec> {
        val dir = File(SourceScan.repoRoot, "openspec/specs")
        val specs = dir.listFiles().orEmpty()
            .filter { File(it, "spec.md").isFile }
            .sortedBy { it.name }
            .map { Spec(it.name, requirementsOf(File(it, "spec.md").readLines())) }
        assertTrue(
            specs.isNotEmpty(),
            "no spec found under $dir — the specs moved, and the traceability gates read nothing",
        )
        specs.forEach {
            assertTrue(
                it.requirements.isNotEmpty(),
                "openspec/specs/${it.name}/spec.md yielded no `$REQUIREMENT` heading — the format moved",
            )
        }
        return specs
    }

    private fun requirementsOf(lines: List<String>): List<Requirement> {
        val result = mutableListOf<Requirement>()
        var title: String? = null
        val scenarios = mutableListOf<String>()
        fun flush() {
            title?.let { result += Requirement(it, scenarios.toList()) }
            scenarios.clear()
        }
        for (line in lines) {
            when {
                line.startsWith(REQUIREMENT) -> {
                    flush()
                    title = line.removePrefix(REQUIREMENT).trim()
                }
                line.startsWith(SCENARIO) && title != null -> scenarios += line.removePrefix(SCENARIO).trim()
                line.startsWith("## ") -> {
                    flush()
                    title = null
                }
            }
        }
        flush()
        return result
    }

    /**
     * The test source set a repo-relative path lies in, or null for production source. A test source set is
     * `src/<name>` where `<name>` is `test`, ends in `Test`, or is one of the test-only sets [TEST_ONLY_SETS] names.
     * Everything else — every `*Main`, `main`, `rig`, `prod`, the hooks, `entries`, and every build script — is
     * production.
     */
    fun testSourceSet(path: String): String? =
        Regex("""/src/([^/]+)/""").find(path)?.groupValues?.get(1)
            ?.takeIf { it == "test" || it.endsWith("Test") || it in TEST_ONLY_SETS }

    /** Every `@Verifies` written in a test source set, anywhere in the repository. */
    fun claims(): List<Claim> =
        SourceScan.kotlinFiles().flatMap { source ->
            val set = testSourceSet(source.path) ?: return@flatMap emptyList()
            if ("@Verifies(" !in source.text) return@flatMap emptyList()
            ANNOTATION.findAll(source.text).map { claimAt(source, set, it.range.last + 1) }.toList()
        }

    private fun claimAt(source: SourceScan.Source, set: String, argsStart: Int): Claim {
        val text = source.text
        val line = text.substring(0, argsStart).count { it == '\n' } + 1
        val args = mutableMapOf<String, String>()
        var problem: String? = null
        var i = argsStart
        while (problem == null) {
            i = skipBlank(text, i)
            if (text.startsWith(")", i)) break
            val name = Regex("""\G([a-z]+)\s*=\s*""").find(text, i)
            if (name == null) {
                problem = "write every argument named (`spec = \"…\"`)"
                break
            }
            i = name.range.last + 1
            val literal = stringLiteral(text, i)
            if (literal == null) {
                problem = "`${name.groupValues[1]}` is not a plain string literal (no constants, templates or concatenation)"
                break
            }
            args[name.groupValues[1]] = literal.first
            i = skipBlank(text, literal.second)
            if (text.startsWith(",", i)) i++
        }
        val unknown = args.keys - setOf("spec", "requirement", "scenario")
        if (problem == null && unknown.isNotEmpty()) problem = "unknown argument(s) $unknown"
        if (problem == null && (args["spec"] == null || args["requirement"] == null)) {
            problem = "`spec` and `requirement` are both required"
        }
        val target = TARGET.find(text, i)?.let { it.groupValues[2] } ?: "?"
        return Claim(
            path = source.path,
            line = line,
            target = target,
            sourceSet = set,
            spec = args["spec"].orEmpty(),
            requirement = args["requirement"].orEmpty(),
            scenario = args["scenario"].orEmpty(),
            problem = problem,
        )
    }

    private fun skipBlank(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** A `"…"` literal at [from] → its value and the index after it; null for anything else. */
    private fun stringLiteral(text: String, from: Int): Pair<String, Int>? {
        if (!text.startsWith("\"", from) || text.startsWith("\"\"\"", from)) return null
        val value = StringBuilder()
        var i = from + 1
        var closed = false
        while (i < text.length && !closed) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '"' -> closed = true
                c == '$' || c == '\n' -> return null
                c == '\\' && next in ESCAPED -> value.append(next).also { i++ }
                c == '\\' -> return null
                else -> value.append(c)
            }
            i++
        }
        return if (closed) value.toString() to i else null
    }

    /** `@Verifies(` opening a line: a mention in a comment or a string is not a claim. */
    private val ANNOTATION = Regex("""(?m)^[ \t]*@Verifies\(""")

    /** The declaration an annotation run sits on. */
    private val TARGET = Regex("""\b(class|object|fun)\s+(`[^`]+`|\w+)""")

    private const val REQUIREMENT = "### Requirement: "
    private const val SCENARIO = "#### Scenario: "
    private val ESCAPED = setOf('"', '\\', '$')

    /**
     * Source sets holding tests that are named neither `test` nor `*Test`: the all-real journeys and the
     * screenshot captures (`:test:integration`), and the crash reporter's shared wire parsing
     * (`:adapter:generic:sentry`). Pinned: a new one is production to [NoSpecCitationTest] until listed here.
     */
    private val TEST_ONLY_SETS = setOf("journeys", "screenshots", "wireTest")
}
