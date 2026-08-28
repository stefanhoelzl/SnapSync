package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The module set withholds; packages organize** (spec `module-architecture`; capability
 * `architecture-guards`, "The migration's laws are permanent gates").
 *
 * The build's include set SHALL equal what `module-architecture` enumerates — and the expected set
 * is **derived from that spec at test runtime**, not held here.
 *
 * IT USED TO BE HELD HERE, and that is the whole point of this file's shape. The guard compared
 * `settings.gradle.kts` against an 18-name table three lines below the assertion, and told you a new
 * module "is a spec delta to module-architecture". It said that twice and got a table edit both
 * times: `:app:ios:forge` arrived in `3d3947a9` touching only `settings.gradle.kts` and this file,
 * with no OpenSpec delta, and `:tools:diagrams` arrived in `f096f287` — the commit that WROTE the
 * spec — and went unaccounted for from its first day. Two archived changes have ever touched the
 * module-set requirement; neither is those.
 *
 * The tether was on the wrong link. A module cannot be added by accident — it takes a directory, a
 * build file and an `include` line, the most visible change a PR can contain — so guarding
 * build-vs-a-copy-of-the-build caught nothing that needed catching, while whether the SPEC still
 * described the set was watched by nothing at all. Deriving from the spec makes the message true:
 * the build now fails until `module-architecture` accounts for the module, and this file cannot be
 * edited to make that go away.
 *
 * The spec groups modules by the law that justifies each — withholding, containment, support — so
 * the failure below can name which group a newcomer must join and what that group demands. "Must
 * withhold a dependency" is the right instruction for only one group of the three.
 */
class ModuleSetTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue(file.isFile, "guard is scanning nothing — $relative not found from $repoRoot")
        return file.readText()
    }

    /** The one requirement that enumerates the module set. */
    private val requirement: String = run {
        val spec = read("openspec/specs/module-architecture/spec.md")
        Regex(
            """^### Requirement: The module set withholds; packages organize\n.*?(?=^### Requirement: |^## )""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        ).find(spec)?.value
            ?: fail(
                "openspec/specs/module-architecture/spec.md has no `### Requirement: The module set " +
                    "withholds; packages organize` — the module set's single home is gone or renamed",
            )
    }

    /**
     * The members of one group. A backticked `:`-prefixed token inside a group's bullet IS a
     * membership claim (the spec says so), which is why prose there names other modules by
     * description rather than by path — a stray backticked path would silently enrol that module in
     * a second group, and [`groups are disjoint`] would catch it.
     */
    private fun group(label: String): Set<String> {
        val bullet = Regex(
            """^- \*\*${Regex.escape(label)}\*\*(.*?)(?=\n\n|\n- \*\*)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        ).find(requirement)?.groupValues?.get(1)
            ?: fail(
                "the module-set requirement has no `- **$label**` group. The gate derives the expected " +
                    "module set from these group labels; renaming one silently empties it, so the label " +
                    "is part of the contract. Fix the spec or this guard, in the same commit.",
            )
        return Regex("""`(:[a-z][a-z0-9:-]*)`""").findAll(bullet).map { it.groupValues[1] }.toSet()
    }

    private val withholding get() = group("Withholding modules")
    private val contained get() = group("Contained modules")
    private val support get() = group("Support modules")
    private val specModules get() = withholding + contained + support

    private val includes: Set<String> = Regex("""include\("([^"]+)"\)""")
        .findAll(read("settings.gradle.kts"))
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun `every group scanned a non-empty scope`() {
        // Non-vacuity twins, PER GROUP. A reworded label empties one group while the other two keep
        // the suite looking alive, so an overall floor would not catch it. `group()` already fails
        // loudly on a missing label; these catch a label that matches but yields nothing.
        assertTrue(withholding.isNotEmpty(), "the Withholding group parsed to zero modules — extraction is broken")
        assertTrue(contained.isNotEmpty(), "the Contained group parsed to zero modules — extraction is broken")
        assertTrue(support.isNotEmpty(), "the Support group parsed to zero modules — extraction is broken")
        assertTrue(specModules.size >= 12, "the spec parsed to only ${specModules.size} modules — extraction is broken")
        assertTrue(includes.isNotEmpty(), "settings.gradle.kts parsed to zero includes — the scan is broken")
    }

    @Test
    fun `groups are disjoint`() {
        val doubled = listOf(
            "Withholding/Contained" to (withholding intersect contained),
            "Withholding/Support" to (withholding intersect support),
            "Contained/Support" to (contained intersect support),
        ).filter { it.second.isNotEmpty() }
        if (doubled.isEmpty()) return
        fail(
            buildString {
                appendLine("a module is enumerated in more than one group of the module-set requirement:")
                doubled.forEach { (pair, both) -> appendLine("  $pair: $both") }
                appendLine(
                    "Each module belongs to exactly one group — the law that justifies it. A duplicate is " +
                        "usually prose naming another module by its backticked path inside a group; name it " +
                        "by description instead.",
                )
            },
        )
    }

    @Test
    fun `the settings module set equals the spec's enumeration`() {
        val missingFromSpec = includes - specModules
        val missingFromBuild = specModules - includes
        if (missingFromSpec.isEmpty() && missingFromBuild.isEmpty()) return
        fail(
            buildString {
                appendLine("the build's module set and openspec/specs/module-architecture/spec.md disagree.")
                if (missingFromSpec.isNotEmpty()) {
                    appendLine("  in settings.gradle.kts but in no group: $missingFromSpec")
                }
                if (missingFromBuild.isNotEmpty()) {
                    appendLine("  enumerated by the spec but not included by the build: $missingFromBuild")
                }
                appendLine()
                appendLine("Amend the SPEC, not this guard — it holds no copy of the set. Every module joins")
                appendLine("exactly one group, and the group is the argument for its existence:")
                appendLine("  · Withholding — it withholds a third-party/platform dependency by compile error.")
                appendLine("                  Anything finer than that is a package with a derived text gate.")
                appendLine("  · Contained   — it exists so something is ABSENT from a production build, linked")
                appendLine("                  only under a build property (`module-architecture`, \"A build-time-only")
                appendLine("                  module is contained by compilation, not by a runtime check\").")
                appendLine("  · Support     — it never links into any shipped-format binary, and is exempt from")
                appendLine("                  the production-module laws.")
                appendLine("A module that fits none of the three is a package with a gate, not a module.")
            },
        )
    }

    @Test
    fun `the core declares only permitted zone edges`() {
        // KEPT DELIBERATELY, and not as belt-and-braces. The platform-free guarantee is a COMPILE error
        // ("a core zone cannot name a platform API"), but only because of a precondition the compiler
        // does not check: that no core module declares a project dependency reaching OUT of the core.
        // Adding `project(":adapter:ios:ext-safe")` to a zone build file compiles perfectly happily and
        // silently hands the core a platform. Nothing but this assertion stands between that edit and a
        // green build.
        //
        // Since the split it asserts two further things the module graph cannot state about itself: that
        // each zone declares only the edge its law permits (so `ports` cannot reach `feature`), and that
        // it declares it with `implementation()` — an `api()` edge would republish the zone to every
        // downstream consumer, dissolving the boundary the split exists to create.
        val permitted = mapOf(
            "model" to emptySet<String>(),
            "ports" to setOf(":domain:model"),
            "feature" to setOf(":domain:model", ":domain:ports"),
            "flow" to setOf(":domain:model", ":domain:feature"),
            "compose" to setOf(":domain:model", ":domain:ports", ":domain:feature", ":domain:flow"),
        )
        val problems = permitted.flatMap { (zone, allowed) ->
            val build = File(repoRoot, "domain/$zone/build.gradle.kts")
            assertTrue(build.isFile, "domain/$zone/build.gradle.kts is missing — the core moved")
            val text = build.readText()
            Regex("""(\w+)\(project\(\s*"([^"]+)"\s*\)\)""").findAll(text).mapNotNull { m ->
                val (configuration, target) = m.destructured
                when {
                    target !in allowed ->
                        ":domain:$zone declares project(\"$target\"), which is not a permitted zone edge. " +
                            "A core module may depend only on ${allowed.ifEmpty { "nothing" }} — an edge out " +
                            "of the core hands it a platform, and the platform-free compile error " +
                            "silently stops holding."
                    configuration != "implementation" ->
                        ":domain:$zone declares $configuration(project(\"$target\")) — zone edges SHALL use " +
                            "implementation(), or the zone is republished to every downstream consumer and " +
                            "the boundary dissolves transitively."
                    else -> null
                }
            }
        }
        assertTrue(problems.isEmpty(), "core zone dependency violations:\n  " + problems.joinToString("\n  "))
    }
}
