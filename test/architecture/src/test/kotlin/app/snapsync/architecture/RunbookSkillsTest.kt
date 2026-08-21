package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The runbook pointers resolve, and the launch-trigger index agrees with source**
 * (capability `architecture-guards`; decision record: `move-runbooks-behind-skills`).
 *
 * The operator runbooks moved out of CLAUDE.md into `.claude/skills/`, leaving one imperative
 * pointer line per skill. That buys ~15k tokens off every session and costs two new ways to rot,
 * both of which this guard closes.
 *
 * 1. **A pointer that reaches nothing.** An agent reads "load the `ios-device` skill", finds no such
 *    skill, and proceeds **without** it — executing the very procedure the skill exists to make
 *    safe. Nothing raises. That is the "absence is never silent" law (spec `module-architecture`)
 *    applied to the seam between the always-loaded file and the on-demand ones.
 *
 * 2. **A launch trigger returning to production Kotlin.** Dev/test control of a device is the control
 *    channel's surface (`:test:rig`), contained at compile time and absent from every shipped build. A
 *    `SNAPSYNC_*` literal in production Kotlin is a regression to the surface this repo removed on
 *    purpose: a remote-control affordance present in every binary, inert only because a SpringBoard
 *    launch supplies no environment — a property of how the app is *started*, not of what it *contains*.
 *
 * The second half used to be the opposite assertion. It held the `ios-device` skill's operator index
 * EQUAL to the launch-trigger literals in production Kotlin, with a `>= 5` non-vacuity floor — which is
 * the exact negation of the invariant that now holds, so it could not be retuned and was replaced
 * (decision record: `…-retire-launch-env-triggers` D16). Deleting it outright was the alternative, and
 * was rejected: nothing would then be watching, which is how the index it guarded drifted in the first
 * place.
 */
class RunbookSkillsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue(file.isFile, "guard is scanning nothing — $relative not found from $repoRoot")
        return file.readText()
    }

    // ---------------------------------------------------------------- pointer integrity

    /**
     * The skills CLAUDE.md's runbook block tells an agent to load. Derived from the file at runtime
     * rather than compared against a maintained list ("Gates fail closed on novelty"): a sixth
     * pointer is covered with zero guard edits.
     */
    private val pointedSkills: Set<String> = run {
        val claudeMd = read("CLAUDE.md")
        val start = claudeMd.indexOf(RUNBOOK_HEADING)
        assertTrue(
            start >= 0,
            "CLAUDE.md has no `$RUNBOOK_HEADING` section — the pointers agents load skills from are gone",
        )
        val section = claudeMd.substring(start).substringAfter('\n').substringBefore("\n## ")
        Regex("""load \*\*`([a-z0-9-]+)`\*\*""")
            .findAll(section)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `the pointer scan is not vacuous`() {
        assertTrue(
            pointedSkills.size >= 3,
            "the runbook block yielded only ${pointedSkills.size} pointers ($pointedSkills) — the " +
                "extraction is broken, or the pointers were written in a shape this guard cannot see. " +
                "A guard that scans nothing fails open, which is the one thing these tests may not do.",
        )
    }

    @Test
    fun `every runbook pointer resolves to a skill that exists`() {
        val dangling = pointedSkills.filterNot { File(repoRoot, skillPath(it)).isFile }
        if (dangling.isEmpty()) return
        fail(
            buildString {
                appendLine("CLAUDE.md points at ${dangling.size} skill(s) that do not exist: $dangling")
                dangling.forEach { appendLine("  expected: ${skillPath(it)}") }
                appendLine(
                    "A dangling pointer does not raise anywhere: the agent looks for the skill, does not " +
                        "find it, and runs the procedure unguarded. Restore the skill or fix the pointer.",
                )
            },
        )
    }

    @Test
    fun `each pointed skill declares the name it is invoked by`() {
        val mismatched = pointedSkills
            .filter { File(repoRoot, skillPath(it)).isFile }
            .mapNotNull { skill ->
                val declared = Regex("""^name:\s*(\S+)\s*$""", RegexOption.MULTILINE)
                    .find(read(skillPath(skill)))
                    ?.groupValues?.get(1)
                if (declared == skill) null else "$skill (declares ${declared ?: "no name:"})"
            }
        if (mismatched.isEmpty()) return
        fail(
            "A skill's frontmatter `name:` must equal its directory — the invoked name and the resolved " +
                "path are the same string. Mismatched: $mismatched",
        )
    }

    // Deliberately NOT asserted: that every skill has a pointer. `bugsink` and the generated
    // `openspec-*` skills are reachable by their own descriptions, and demanding a pointer for each
    // would make `openspec update`'s regenerated output fail the build. Only the dangling direction
    // can mislead an agent.

    // ---------------------------------------------------------------- no launch triggers

    /**
     * Every `"SNAPSYNC_*"` literal in production Kotlin.
     *
     * The gated trees are excluded, and that is not a loophole — it is the whole distinction. A file under
     * `test/` is absent from a build without its build property, so a variable it reads is inert **by
     * construction** rather than by a runtime check. `SNAPSYNC_RIG_PORT` and the forge target's state
     * selector both live there.
     */
    private val sourceTriggers: Map<String, String> = run {
        val roots = listOf("domain", "app", "adapter", "ui").map { File(repoRoot, it) }
        roots.forEach { assertTrue(it.isDirectory, "guard is scanning nothing — ${it.name}/ not found") }
        val files = roots.asSequence()
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/build/") }
            .filterNot { it.path.contains("/commonTest/") || it.path.contains("/jvmTest/") }
            .filterNot { it.path.contains("Test/") || it.path.contains("/test/") }
            // The build-property-gated trees. Not a loophole — it is the whole distinction: these files
            // are absent from a build without their property, so a variable read there is inert BY
            // CONSTRUCTION rather than by a runtime check. `SNAPSYNC_RIG_PORT` lives in the first;
            // `SNAPSYNC_FORGE_STATE` lives in the second, whose binary is a separate Xcode target that
            // does not link `:app:ios` at all.
            .filterNot { it.path.contains("/app/ios/forge/") }
            .toList()
        scannedFileCount = files.size
        files.flatMap { file ->
            TRIGGER_LITERAL.findAll(file.readText()).map { it.groupValues[1] to file.relativeTo(repoRoot).path }
        }.toMap()
    }

    private var scannedFileCount: Int = 0

    @Test
    fun `the trigger scan is not vacuous`() {
        // The passing condition is an empty RESULT over a non-empty SCAN. An empty scan is not.
        assertTrue(
            scannedFileCount >= 100,
            "the production-source scan resolved only $scannedFileCount Kotlin files — it is broken, so " +
                "an empty result would prove nothing",
        )
    }

    @Test
    fun `production Kotlin declares no launch triggers`() {
        if (sourceTriggers.isEmpty()) return
        fail(
            buildString {
                appendLine("production Kotlin declares ${sourceTriggers.size} launch trigger(s):")
                sourceTriggers.forEach { (name, path) -> appendLine("  $name  ($path)") }
                appendLine(
                    "Dev/test control of a device is the control channel's surface (`:test:rig`), which a " +
                        "production build does not contain. A launch trigger here ships in every binary and " +
                        "is inert only because a SpringBoard launch supplies no environment — which is a " +
                        "fact about how the app is started, not about what it contains.",
                )
                appendLine(
                    "If a build-property-gated tree genuinely needs an environment variable, put the read " +
                        "there (as `SNAPSYNC_RIG_PORT` is) — those files are absent from a production build, " +
                        "so their inertness is structural.",
                )
            },
        )
    }

    private companion object {
        const val RUNBOOK_HEADING = "## Runbooks"
        val TRIGGER_LITERAL = Regex(""""(SNAPSYNC_[A-Z_]+)"""")
        fun skillPath(skill: String) = ".claude/skills/$skill/SKILL.md"
    }
}
