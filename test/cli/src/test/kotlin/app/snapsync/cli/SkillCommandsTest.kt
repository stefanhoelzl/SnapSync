package app.snapsync.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every `rig` command a runbook names exists** — the second half of the runbook guards (`RunbookSkillsTest` checks that
 * every skill the root agent file points at exists).
 *
 * A skill or an agent file tells an agent to run `rig <service> <method> --<flag> …`. The command set is derived from
 * the services (`Commands.kt`), so a renamed method or parameter quietly leaves the runbook naming a command `rig` no
 * longer has — and an agent following it meets a usage error mid-procedure, or improvises. So each command named in
 * code — an inline code span, or a line of a fenced block, never prose — is resolved here exactly as `rig` resolves it:
 * its service, its method, and each flag against the method's parameters or the CLI's own.
 */
class SkillCommandsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** The runbooks: every skill's files, and every agent file. */
    private val runbooks: List<File> =
        (
            File(repoRoot, ".claude/skills").walkTopDown().filter { it.isFile && it.extension == "md" } +
                repoRoot.walkTopDown()
                    .onEnter { it.name !in SKIPPED_DIRECTORIES }
                    .filter { it.isFile && it.name == "AGENTS.md" }
            )
            .toList()

    /** Every `rig` command named in the runbooks' code, with where. */
    private val named: List<Named> = runbooks.flatMap { file ->
        code(file.readText()).flatMap { text ->
            COMMAND.findAll(text).map { match ->
                Named(
                    file.toRelativeString(repoRoot),
                    match.groupValues[1],
                    match.groupValues[2],
                    FLAG.findAll(match.groupValues[3]).map { it.groupValues[1] }.toList(),
                )
            }
        }
    }

    @Test
    fun `the command scan is not vacuous`() {
        assertTrue(
            named.size >= MINIMUM,
            "the runbooks yielded only ${named.size} rig commands — the extraction is broken, or the commands were " +
                "written in a shape this guard cannot see. A guard that scans nothing fails open.",
        )
    }

    @Test
    fun `every rig command a runbook names exists`() {
        val broken = named.mapNotNull { command ->
            val service = serviceCommands(command.service)
                ?: return@mapNotNull "$command: no service `${command.service}`"
            val method = service.method(command.method)
                ?: return@mapNotNull "$command: `${command.service}` has no method `${command.method}`"
            val flags = method.parameters.map { it.name }.toSet() + GLOBAL_FLAGS
            (command.flags - flags).takeIf { it.isNotEmpty() }?.let { unknown ->
                "$command: no flag ${unknown.joinToString { "--$it" }} (it takes ${flags.sorted()})"
            }
        }
        assertEquals(
            emptyList(),
            broken,
            "a runbook names a `rig` command the CLI does not have — fix the runbook to the command `rig --help` lists",
        )
    }

    /** One command as a runbook names it. */
    private data class Named(val file: String, val service: String, val method: String, val flags: List<String>) {
        override fun toString() = "$file: rig $service $method${flags.joinToString("") { " --$it" }}"
    }

    /** A runbook's code: each fenced block's lines, its comments cut, and each inline span outside them. */
    private fun code(markdown: String): List<String> {
        val fenced = FENCE.findAll(markdown).flatMap { block ->
            block.groupValues[1].lines().map { it.replace(COMMENT, "") }
        }
        val spans = SPAN.findAll(markdown.replace(FENCE, "")).map { it.groupValues[1] }
        return (fenced + spans).toList()
    }

    private companion object {
        val FENCE = Regex("""```[^\n]*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
        val SPAN = Regex("""`([^`\n]+)`""")

        /** A shell comment: from a `#` that starts the line or follows a space. */
        val COMMENT = Regex("""(^|\s)#.*$""")

        /** `rig <service> <method>` and the flags after it — a word of its own, never `-Psnapsync.rig=true`. */
        val COMMAND =
            Regex("""(?<![\w./=-])rig ([a-z][\w-]*) ([a-z][\w-]*)((?:\s+--[\w-]+(?:[ =](?!--)[^\s`|;&]+)?)*)""")
        val FLAG = Regex("""--([\w-]+)""")

        /** The flags `rig` itself takes, before or after the command (`Main.kt`). */
        val GLOBAL_FLAGS = setOf("url", "json", "timeout", "help")

        val SKIPPED_DIRECTORIES = setOf("build", "node_modules", ".git", ".gradle", ".claude")

        /** Fewer means the scan broke: the runbooks named 31 commands when this guard was written. */
        const val MINIMUM = 15
    }
}
