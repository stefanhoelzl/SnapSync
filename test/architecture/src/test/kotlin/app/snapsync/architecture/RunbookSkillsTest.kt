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
 * 2. **An index that outlives its source.** The `ios-device` skill carries a compressed operator
 *    index of the `SNAPSYNC_*` launch triggers — a DUPLICATE, which this repo forbids unless it is
 *    loud-when-stale (see [LawsDigestTest], whose rationale records that "the previous CLAUDE.md
 *    module graph rotted silently for months precisely because nothing held it to anything"). The
 *    drift was already present when this guard was written: `SNAPSYNC_POLICY_PROBE` shipped in
 *    production Kotlin, was named in CLAUDE.md's ordering chain, and was documented nowhere.
 *
 * Both comparisons are by NAME only. `ios-app-shell` remains the contract of record for every
 * trigger it specifies; the index says what to type, never what it guarantees. Two authorities for
 * semantics would be worse than one — the same line [LawsDigestTest] draws.
 *
 * The index is compared against **source**, not against `ios-app-shell`'s requirements, because four
 * triggers (`SEED_PHOTOS`, `SEED_POLICY`, `WIPE_GALLERY`, `POLICY_PROBE`) ship in production Kotlin
 * and appear in no spec at all. A spec-keyed guard would silently cover a subset — the failure mode
 * this capability exists to refuse. That those four are unspecified is a stated gap, named in the
 * decision record; this guard holds their documentation, not their contract.
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

    // ---------------------------------------------------------------- launch-trigger index

    /** Every `"SNAPSYNC_*"` literal in production Kotlin (main source sets; tests and `build/` out). */
    private val sourceTriggers: Set<String> = run {
        val roots = listOf("domain", "app", "adapter", "ui").map { File(repoRoot, it) }
        roots.forEach { assertTrue(it.isDirectory, "guard is scanning nothing — ${it.name}/ not found") }
        roots.asSequence()
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/build/") }
            .filterNot { it.path.contains("/commonTest/") || it.path.contains("/jvmTest/") }
            .filterNot { it.path.contains("Test/") || it.path.contains("/test/") }
            .flatMap { TRIGGER_LITERAL.findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()
    }

    /** Every `SNAPSYNC_*` name the device skill's operator index documents. */
    private val documentedTriggers: Set<String> =
        Regex("""SNAPSYNC_[A-Z_]+""")
            .findAll(read(skillPath(DEVICE_SKILL)))
            .map { it.value }
            .toSet()

    @Test
    fun `the trigger scan is not vacuous`() {
        assertTrue(
            sourceTriggers.size >= 5,
            "found only ${sourceTriggers.size} SNAPSYNC_* literals in production Kotlin — the source " +
                "scan is broken, so the index would be held to nothing",
        )
        assertTrue(
            documentedTriggers.size >= 5,
            "found only ${documentedTriggers.size} SNAPSYNC_* names in ${skillPath(DEVICE_SKILL)} — the " +
                "index extraction is broken",
        )
    }

    @Test
    fun `the launch-trigger index names exactly the triggers production source declares`() {
        val undocumented = sourceTriggers - documentedTriggers
        val stale = documentedTriggers - sourceTriggers
        if (undocumented.isEmpty() && stale.isEmpty()) return
        fail(
            buildString {
                appendLine("${skillPath(DEVICE_SKILL)}'s launch-trigger index has drifted from production Kotlin.")
                if (undocumented.isNotEmpty()) appendLine("  in the source but not the index: $undocumented")
                if (stale.isNotEmpty()) appendLine("  in the index but not the source: $stale")
                appendLine(
                    "Fix BOTH sides in one commit. The index is what an operator types; `ios-app-shell` " +
                        "stays the contract of record for what a trigger guarantees — document the name and " +
                        "the invocation there, not the semantics.",
                )
            },
        )
    }

    private companion object {
        const val RUNBOOK_HEADING = "## Runbooks"
        const val DEVICE_SKILL = "ios-device"
        val TRIGGER_LITERAL = Regex(""""(SNAPSYNC_[A-Z_]+)"""")
        fun skillPath(skill: String) = ".claude/skills/$skill/SKILL.md"
    }
}
