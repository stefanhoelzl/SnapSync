package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The module set withholds; packages organize.**
 *
 * The build's include set SHALL equal [WITHHOLDING] + [CONTAINED] + [SUPPORT], and every module SHALL
 * belong to exactly one of the three. A group names the law that justifies its members' existence; a
 * module justified by no law is a package with a derived text gate instead.
 *
 * The expected set used to be parsed at test runtime out of the `docs/architecture.md` spec's
 * "The module set withholds; packages organize" requirement. That spec is gone, so the enumeration now
 * lives HERE, and this KDoc carries the rationale the spec used to:
 *
 * - **Why a guard at all.** A module cannot be added by accident — it takes a directory, a build file
 *   and an `include` line — so the point is not to catch a stray module but to force the newcomer to
 *   name the law that justifies it. Adding a module therefore means editing `settings.gradle.kts` AND
 *   placing the module in exactly one group below, with the group's argument in the commit.
 * - **Withholding** — each exists because it withholds a dependency from its consumers by compile
 *   error: usually a third-party or platform one, or another zone of the core, where a module boundary
 *   is the only construction that makes the zone edge unresolvable rather than merely forbidden.
 *   `:ui:components` is the only module that may depend on Material 3; `:app:composition` is the one
 *   module that sees both the core's composition zone and presentation, so neither gains the other.
 * - **Contained** — each exists so that something is ABSENT from a production build, linked only under
 *   a build property (a build-time-only module is contained by compilation, not by a runtime check):
 *   `:app:ios:forge` under `-Psnapsync.forge`; `:test:rig` and `:test:contracts` under `-Psnapsync.rig`
 *   (`:test:contracts` is also the only module whose main code may assert). Grouped by the law that
 *   governs them, not by name prefix.
 * - **Support** — never linked into any shipped-format binary, and exempt from the production-module
 *   laws.
 *
 * The core's zone split (`:domain:*`) is the one place withholding is satisfied by an internal
 * boundary: a module boundary enumerates nothing and cannot be renamed into passivity, unlike the text
 * gates it replaced. [`the core declares only permitted zone edges`] pins the precondition for that.
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

    private val withholding get() = WITHHOLDING
    private val contained get() = CONTAINED
    private val support get() = SUPPORT
    private val enumerated get() = withholding + contained + support

    private val includes: Set<String> = Regex("""include\("([^"]+)"\)""")
        .findAll(read("settings.gradle.kts"))
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun `every group scanned a non-empty scope`() {
        // Non-vacuity twins, PER GROUP: an emptied group would let the other two keep the suite
        // looking alive, so an overall floor alone would not catch it.
        assertTrue(withholding.isNotEmpty(), "the Withholding group is empty")
        assertTrue(contained.isNotEmpty(), "the Contained group is empty")
        assertTrue(support.isNotEmpty(), "the Support group is empty")
        assertTrue(enumerated.size >= 12, "the groups enumerate only ${enumerated.size} modules")
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
                appendLine("a module is enumerated in more than one group:")
                doubled.forEach { (pair, both) -> appendLine("  $pair: $both") }
                appendLine(
                    "Each module belongs to exactly one group — the law that justifies it.",
                )
            },
        )
    }

    @Test
    fun `the settings module set equals the enumeration`() {
        val missingFromGroups = includes - enumerated
        val missingFromBuild = enumerated - includes
        if (missingFromGroups.isEmpty() && missingFromBuild.isEmpty()) return
        fail(
            buildString {
                appendLine("the build's module set and ModuleSetTest's groups disagree.")
                if (missingFromGroups.isNotEmpty()) {
                    appendLine("  in settings.gradle.kts but in no group: $missingFromGroups")
                }
                if (missingFromBuild.isNotEmpty()) {
                    appendLine("  enumerated in a group but not included by the build: $missingFromBuild")
                }
                appendLine()
                appendLine("Place the module in exactly one group (WITHHOLDING/CONTAINED/SUPPORT below). Every module joins")
                appendLine("exactly one group, and the group is the argument for its existence:")
                appendLine("  · Withholding — it withholds a third-party/platform dependency by compile error.")
                appendLine("                  Anything finer than that is a package with a derived text gate.")
                appendLine("  · Contained   — it exists so something is ABSENT from a production build, linked")
                appendLine("                  only under a build property (a build-time-only module is contained by")
                appendLine("                  compilation, not by a runtime check).")
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

    private companion object {
        /** Each withholds a dependency (third-party, platform, or another core zone) by compile error. */
        val WITHHOLDING = setOf(
            ":domain:model", ":domain:ports", ":domain:feature", ":domain:flow", ":domain:compose",
            ":ui:presentation", ":ui:screens", ":ui:components",
            ":adapter:ios:ext-safe", ":adapter:ios:app-only", ":adapter:generic:app", ":adapter:generic:fake",
            ":app:ios", ":app:ios:extension", ":app:desktop",
            ":app:composition",
        )

        /** Each exists so something is absent from a production build; linked only under a build property. */
        val CONTAINED = setOf(":app:ios:forge", ":test:rig", ":test:contracts")

        /** Never linked into any shipped-format binary; exempt from the production-module laws. */
        val SUPPORT = setOf(
            ":test:world", ":test:integration", ":test:architecture", ":test:harness-driver",
            ":tools:diagrams", ":test:edge", ":test:control",
        )
    }
}
