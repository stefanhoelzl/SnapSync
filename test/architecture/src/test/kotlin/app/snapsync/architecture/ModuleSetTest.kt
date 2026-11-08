package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
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
 * "The module set: three groups" requirement. That spec is gone, so the enumeration now
 * lives HERE, and this KDoc carries the rationale the spec used to:
 *
 * - **Why a guard at all.** A module cannot be added by accident — it takes a directory, a build file
 *   and an `include` line — so the point is not to catch a stray module but to force the newcomer to
 *   name the law that justifies it. Adding a module therefore means editing `settings.gradle.kts` AND
 *   placing the module in exactly one group below, with the group's argument in the commit.
 * - **Withholding** — each exists because it withholds a dependency from its consumers by compile
 *   error: usually a third-party or platform one, or another zone of the core, where a module boundary
 *   is the only construction that makes the zone edge unresolvable rather than merely forbidden.
 *   `:ui:components` is the only module that may depend on Material 3; `:ui:state`, the screen's vocabulary,
 *   references nothing project-internal, so the screens built on it cannot name a domain type; the host, `:core:host`, is the one
 *   zone that sees both the core's composition zone and presentation, so neither gains the other.
 * - **Contained** — each exists so that something is ABSENT from a production build, linked only under
 *   a build property (a build-time-only module is contained by compilation, not by a runtime check):
 *   `:test:rig`, `:test:contracts` and `:test:launch-adapters` under `-Psnapsync.rig`
 *   (`:test:contracts` is also the only module whose main code may assert). Grouped by the law that
 *   governs them, not by name prefix.
 * - **Support** — never linked into any shipped-format binary, and exempt from the production-module
 *   laws.
 *
 * The core's zone split (`:core:*`) is the one place withholding is satisfied by an internal
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
                appendLine(
                    "Place the module in exactly one group (WITHHOLDING/CONTAINED/SUPPORT below). Every module joins",
                )
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
        // each zone declares only the edge its law permits (so `ports` cannot reach `data`), and that
        // it declares it with `implementation()` — an `api()` edge would republish the zone to every
        // downstream consumer, dissolving the boundary the split exists to create. The one edge outside a zone's law is
        // its tests' `:test:verifies`, inside `commonTest.dependencies`: an annotation that depends on nothing.
        val permitted = mapOf(
            // The domain layer: values and pure policy, depending on nothing.
            "domain" to emptySet<String>(),
            // The text a value takes on the wire and in a store, once per format: it spells the domain's values.
            "format" to setOf(":core:domain"),
            // The backend API's HTTP shapes, generated from `api/openapi.yaml`: they reference nothing.
            "wire" to emptySet<String>(),
            // The inbound kinds an event port delivers through, the trigger and the structured log: nothing below them.
            "inbound" to emptySet<String>(),
            // The deliveries' kinds: an event port's handlers are typed by them. The domain's values a port hands over
            // as they are (the build's store page).
            "ports" to setOf(":core:inbound", ":core:domain"),
            // The runtime runs on the process's background time and labels its threads: two ports, nothing above — and
            // the domain's `InvalidProgramState`, which its containment never contains.
            "scheduling" to setOf(":core:inbound", ":core:ports", ":core:domain"),
            // The data layer: the repositories over the ports and the backend's protocol over the transport and the
            // generated wire — translating, never deciding — labelling its lines with the structured log.
            "data" to setOf(":core:domain", ":core:ports", ":core:wire", ":core:inbound", ":core:format"),
            // The application layer: use cases over data and the domain's verdicts, writing the structured log.
            "application" to setOf(":core:domain", ":core:data", ":core:inbound", ":core:format"),
            // The query layer: what the screen reads — the membership, the join's and switch's lookups, and the
            // request-keyed statuses — over data and the domain, writing nothing; the invite in its link's text.
            "query" to setOf(":core:domain", ":core:data", ":core:inbound", ":core:format"),
            // The entry points order the work of each trigger: the runtime's steps over the use cases, the data layer
            // and the queries — and host assembly, a person's start, which builds the status host (presentation) and
            // shows the screen's states (`:ui:state`).
            "entrypoints" to setOf(
                ":core:inbound", ":core:scheduling", ":core:domain", ":core:ports", ":core:application",
                ":core:data", ":core:query", ":core:presentation", ":ui:state",
            ),
            // The composition layer (`docs/architecture.md`, "The composition layer"), wiring only. Its shared half: what
            // every process wires — every layer below presentation, and no old zone.
            "composition/process" to setOf(
                ":core:domain", ":core:inbound", ":core:ports", ":core:scheduling", ":core:data",
                ":core:application", ":core:entrypoints",
            ),
            // The upload extension's half: the shared half and the same layers — never presentation, the query layer or
            // the screen's vocabulary, which the extension does not link.
            "composition/extension" to setOf(
                ":core:composition:process", ":core:domain", ":core:inbound", ":core:ports",
                ":core:scheduling", ":core:data", ":core:application", ":core:entrypoints",
            ),
            // The app's half: the shared half, every layer, presentation and the screen's vocabulary its `Ui` port is bound
            // to — and no old zone.
            "composition/app" to setOf(
                ":core:composition:process", ":core:domain", ":core:inbound", ":core:ports",
                ":core:scheduling", ":core:data", ":core:application", ":core:query", ":core:entrypoints",
                ":core:presentation", ":core:format", ":ui:state",
            ),
            // The UI-state reduction: the domain's vocabulary, the query layer it reads, the inbound kinds its command door
            // fires (`UserCommand`), and the screen's vocabulary it reduces into. `:ui:state` references nothing
            // project-internal, so none of the four hands it a platform.
            "presentation" to setOf(":core:domain", ":core:query", ":core:inbound", ":ui:state"),
        )
        val problems = permitted.flatMap { (zone, allowed) ->
            val build = File(repoRoot, "core/$zone/build.gradle.kts")
            assertTrue(build.isFile, "core/$zone/build.gradle.kts is missing — the core moved")
            val module = ":core:" + zone.replace("/", ":")
            val text = build.readText()
            val tests = commonTestBlock(text)
            Regex("""(\w+)\(project\(\s*"([^"]+)"\s*\)\)""").findAll(text).mapNotNull { m ->
                val (configuration, target) = m.destructured
                when {
                    // A test claims the scenario its zone decides (`@Verifies`). `:test:verifies` depends on nothing,
                    // so it hands the zone no platform, and only the zone's tests see it.
                    target == VERIFIES && m.range.first in tests && configuration == "implementation" -> null
                    target !in allowed ->
                        "$module declares project(\"$target\"), which is not a permitted zone edge. " +
                            "A core module may depend only on ${allowed.ifEmpty { "nothing" }} — an edge out " +
                            "of the core hands it a platform, and the platform-free compile error " +
                            "silently stops holding."
                    configuration != "implementation" ->
                        "$module declares $configuration(project(\"$target\")) — zone edges SHALL use " +
                            "implementation(), or the zone is republished to every downstream consumer and " +
                            "the boundary dissolves transitively."
                    else -> null
                }
            }
        }
        assertTrue(problems.isEmpty(), "core zone dependency violations:\n  " + problems.joinToString("\n  "))
    }

    private companion object {
        const val VERIFIES = ":test:verifies"

        /** The character range of [build]'s `commonTest.dependencies { … }` block, or an empty range if it has none. */
        fun commonTestBlock(build: String): IntRange {
            val open = Regex("""commonTest\.dependencies\s*\{""").find(build) ?: return IntRange.EMPTY
            var depth = 0
            for (i in open.range.last until build.length) {
                when (build[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return open.range.last..i
                }
            }
            return IntRange.EMPTY
        }

        /** Each withholds a dependency (third-party, platform, or another core zone) by compile error. */
        val WITHHOLDING = setOf(
            // The core's layers (`docs/architecture.md`, "The core and its layers").
            ":core:domain", ":core:format", ":core:wire", ":core:ports",
            ":core:presentation",
            // The layers the core's work runs on (`docs/patterns.md`): the deliveries, the runtime, the entry points.
            ":core:inbound", ":core:scheduling", ":core:entrypoints", ":core:data", ":core:application",
            ":core:query",
            // The composition layer: the extension's half withholds everything the app composes from the extension.
            ":core:composition:process", ":core:composition:extension", ":core:composition:app",
            // `:ui:state` withholds the domain from the screens: they see the screen's vocabulary, and no domain
            // type resolves there.
            ":ui:state", ":ui:screens", ":ui:components",
            ":adapter:ios:ext-safe", ":adapter:ios:app-only", ":adapter:ios:ui", ":adapter:generic:app",
            ":adapter:generic:mock", ":adapter:generic:sentry", ":adapter:android",
            // The Motion Photo codec both import adapters share: it withholds the core from them — the format they must
            // agree on lives here, not in the core, so an adapter reaches it without linking the core.
            ":adapter:generic:motionphoto",
            // The operator faces' typed interfaces: it withholds the mocks from the tests, which compile against these and
            // call them through the control channel, while the mocks implement them (`docs/testing.md`, "Typed
            // services"). Linked, like the mocks, only into a rig build.
            ":test:levers",
            ":app:ios", ":app:ios:extension", ":app:android", ":app:desktop",
        )

        /** Each exists so something is absent from a production build; linked only under a build property. */
        val CONTAINED = setOf(":test:rig", ":test:contracts", ":test:launch-adapters")

        /** Never linked into any shipped-format binary; exempt from the production-module laws. */
        val SUPPORT = setOf(
            ":app:jvm",
            ":test:integration", ":test:architecture", ":test:harness-driver",
            ":tools:diagrams", ":tools:detekt-rules", ":test:edge", ":test:client", ":test:control", ":test:verifies",
            ":test:cli",
            ":test:feature", ":test:journeys",
            ":test:data",
            ":test:partial-grant", ":test:no-grant",
        )
    }
}
