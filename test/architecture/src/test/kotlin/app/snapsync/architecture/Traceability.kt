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
 *
 * The Deno suites (`api/test`, `site/scripts`) claim what the api and the web pages decide, with the same
 * annotation written as a line comment above the `Deno.test` ([denoTestSet]). One parser reads both, so one gate
 * and one report hold both. A scenario split between the api and the app carries one claim per deciding place,
 * each saying in a line which part it proves; the gate counts any one.
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
    data class Claim(
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

    /**
     * The Deno suite a repo-relative path belongs to, or null. Everything under `api/test/` is test code (its `support/`
     * and `fixtures/` included); under `site/scripts/` only the `*.test.ts` files are, the rest being the site's build
     * checks and deploy. Every other `.ts`, `.astro` and `.mjs` under `api/` and `site/` is production.
     */
    fun denoTestSet(path: String): String? = when {
        path.startsWith("/api/test/") -> "api/test"
        path.startsWith("/site/scripts/") && path.endsWith(".test.ts") -> "site/scripts"
        else -> null
    }

    /**
     * Every claim, anywhere in the repository: each `@Verifies` written in a Kotlin test source set, and each
     * `// @Verifies(…)` comment directly above a `Deno.test` in a Deno test file ([denoTestSet]).
     */
    fun claims(): List<Claim> = kotlinClaims() + denoClaims()

    private fun kotlinClaims(): List<Claim> =
        SourceScan.kotlinFiles().flatMap { source ->
            val set = testSourceSet(source.path) ?: return@flatMap emptyList()
            if ("@Verifies(" !in source.text) return@flatMap emptyList()
            ANNOTATION.findAll(source.text).map { claimAt(source, set, it.range.last + 1) }.toList()
        }

    /**
     * The Deno form: a line comment in the annotation's own syntax, `// @Verifies(spec = "…", requirement = "…",
     * scenario = "…")`, on ONE line, followed by nothing but comment lines up to its `Deno.test(`. The comment form
     * loses nothing against Kotlin's: the annotation is `SOURCE`-retained and read from text too, by this same parser.
     */
    private fun denoClaims(): List<Claim> =
        SourceScan.files(setOf("ts")).flatMap { source ->
            if (!source.path.endsWith(".test.ts")) return@flatMap emptyList()
            val set = denoTestSet(source.path) ?: return@flatMap emptyList()
            if ("@Verifies(" !in source.text) return@flatMap emptyList()
            DENO_CLAIM.findAll(source.text).map { denoClaimAt(source, set, it.range.last + 1) }.toList()
        }

    private fun denoClaimAt(source: SourceScan.Source, set: String, argsStart: Int): Claim {
        val text = source.text
        val lineEnd = text.indexOf('\n', argsStart).let { if (it < 0) text.length else it }
        val parsed = claimAt(source, set, argsStart, end = lineEnd)
        if (parsed.problem != null) return parsed
        // Past the claim's line, skip the comment lines (more claims, the one-line account of what it proves).
        var i = lineEnd + 1
        while (i < text.length) {
            val next = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            if (!text.substring(i, next).trim().startsWith("//")) break
            i = next + 1
        }
        i = skipBlankOnLine(text, i)
        if (!text.startsWith("Deno.test(", i)) {
            return parsed.copy(
                problem = "a Deno claim sits directly above its `Deno.test(`, with only comment lines between",
            )
        }
        return parsed.copy(target = testName(text, skipBlank(text, i + "Deno.test(".length)) ?: "?")
    }

    private fun skipBlankOnLine(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    /** A Deno test's name: its first argument when that is a `"…"`, `'…'` or `` `…` `` literal, as written. */
    private fun testName(text: String, from: Int): String? {
        val quote = text.getOrNull(from)?.takeIf { it == '"' || it == '\'' || it == '`' } ?: return null
        val close = text.indexOf(quote, from + 1).takeIf { it > 0 } ?: return null
        return text.substring(from + 1, close)
    }

    private fun claimAt(source: SourceScan.Source, set: String, argsStart: Int, end: Int = Int.MAX_VALUE): Claim {
        val text = source.text
        val line = text.substring(0, argsStart).count { it == '\n' } + 1
        val args = mutableMapOf<String, String>()
        var problem: String? = null
        var i = argsStart
        while (problem == null) {
            i = skipBlank(text, i)
            if (i >= end) {
                problem = "a Deno claim fits on one line, its closing `)` included"
                break
            }
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

    /** `// @Verifies(` opening a line: the Deno form. A mention inside other comment text is not a claim. */
    private val DENO_CLAIM = Regex("""(?m)^[ \t]*// @Verifies\(""")

    /** The declaration an annotation run sits on. */
    private val TARGET = Regex("""\b(class|object|fun)\s+(`[^`]+`|\w+)""")

    private const val REQUIREMENT = "### Requirement: "
    private const val SCENARIO = "#### Scenario: "
    private val ESCAPED = setOf('"', '\\', '$')

    /**
     * Source sets holding tests that are named neither `test` nor `*Test`: the all-real journeys (`:test:journeys`), the
     * screenshot captures (`:test:integration`), and the crash reporter's shared wire parsing
     * (`:adapter:generic:sentry`). Pinned: a new one is production to [NoSpecCitationTest] until listed here.
     */
    private val TEST_ONLY_SETS = setOf("journeys", "screenshots", "wireTest")
}
