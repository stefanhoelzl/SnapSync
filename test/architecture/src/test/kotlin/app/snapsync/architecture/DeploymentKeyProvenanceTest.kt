package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **No reader is left behind on a moved deployment key** (capability `architecture-guards`; the
 * invariant it enforces belongs to `deployment-configuration`).
 *
 * When a key moves out of the committed `Config.xcconfig` into a generated rendering, every reader must
 * move with it. A reader left behind **does not raise anywhere**: the file it reads still exists and is
 * still readable, it simply no longer assigns the key, so the extraction yields the empty string and the
 * reader proceeds. "This file does not assign that key" and "that key's value is empty" are the same
 * answer.
 *
 * That is survivable for a value that only configures something. It is not survivable for a value that
 * composes an IDENTITY. Measured 2026-08-25: the `ssh-mac-build` re-sign step still awked `TEAM_ID` out of
 * `Config.xcconfig` weeks after it moved, so `$(AppIdentifierPrefix)` expanded to a bare `.` and the IPA
 * was signed claiming keychain group `.app.snapsync.shared`. The app installed, launched, looked entirely
 * normal — and had no device id, because `device-identity` names the group explicitly, so the read threw
 * `errSecMissingEntitlement` (-34018) into the app-scope error boundary, which logs. The device id is
 * written once and never rewritten, so the mistake freezes permanently on the device.
 *
 * Neither existing check could see it. The re-sign step's guard greps the signed entitlements for `*` —
 * key-agnostic on purpose, so it catches whichever wildcard Apple adds next — but `.app.snapsync.shared`
 * contains no wildcard: that guard tests for the ABSENCE OF A LEAKED GRANT, and cannot see a substitution
 * that produced garbage. `codesign -v` passed too; the signature is valid, it just claims the wrong
 * identity. Absence of a wildcard is not presence of the right prefix.
 *
 * **What this asserts, and why it is phrased negatively.** Not "these known keys moved" — that would guard
 * the past. The rule is that nothing may extract from `Config.xcconfig` a setting `Config.xcconfig` does
 * not itself ASSIGN. That needs no key list at all: the committed file states its own contents, and every
 * key that has left it, or ever will, is covered by construction. The first draft derived the key set from
 * the xcconfig rendering instead, and `internal(config): bake the values the app reads into a plist`
 * landed concurrently and moved four keys to a *different* rendering — which that draft would have stopped
 * covering, silently, which is the exact failure mode this guard exists to prevent.
 *
 * Why this rather than a note in the runbook: a skill is prose an agent may follow, may skim, or may — as
 * here — follow a version of it that was true six weeks ago. This fires at the moment a key moves, which
 * is the only moment the mistake is cheap.
 */
class DeploymentKeyProvenanceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /**
     * The settings `Config.xcconfig` actually assigns — read from the file itself, so it cannot go stale.
     *
     * Line-anchored on purpose: the file's header comment *names* every key the generated fragment owns,
     * deliberately, and that documentation must stay passable. A comment line begins with `/`, so it never
     * matches an assignment.
     */
    private val assignedByCommitted: Set<String> = run {
        val committed = File(repoRoot, COMMITTED_PATH)
        assertTrue(committed.isFile, "guard is scanning nothing — $COMMITTED_PATH not found from $repoRoot")
        Regex("""^([A-Z][A-Z0-9_]*) = """, RegexOption.MULTILINE)
            .findAll(committed.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * The text surfaces a reader can live in — source, build files, workflows, and `.claude/skills/`,
     * because the reader that motivated this guard was a skill rather than code.
     *
     * `openspec/` is excluded: it is where the split is *documented*, and it contains no reader. The
     * resolver is excluded because it WRITES the renderings. The generated OpenSpec skills and commands
     * are excluded because `openspec update` rewrites them, and a gate that can fire on regenerated output
     * is the trap CLAUDE.md warns about.
     */
    private val scanned: List<File> = run {
        val skipDirs = setOf(".git", ".gradle", ".idea", "build", "node_modules", "openspec")
        repoRoot.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && it.extension in SCANNED_EXTENSIONS }
            .filterNot { it.relativeTo(repoRoot).path == RESOLVER }
            .filterNot { it.relativeTo(repoRoot).path.startsWith(".claude/commands/") }
            .filterNot { it.relativeTo(repoRoot).path.startsWith(".claude/skills/openspec-") }
            .toList()
    }

    @Test
    fun `the guard is not vacuous`() {
        assertTrue(
            assignedByCommitted.isNotEmpty(),
            "parsed no assignments out of $COMMITTED_PATH — the extraction is broken, so every read would " +
                "look like a violation, or none would.",
        )
        assertTrue(
            scanned.size >= 50,
            "the scan resolved only ${scanned.size} file(s) — it is broken. The passing condition here is " +
                "an empty RESULT over a non-empty SCAN; an empty scan is not.",
        )
        assertTrue(
            scanned.any { it.relativeTo(repoRoot).path == RESIGN_SKILL },
            "$RESIGN_SKILL is outside the scan. It is the reader this guard exists for; if it is not " +
                "covered, the guard proves nothing about the case that motivated it.",
        )
    }

    @Test
    fun `nothing extracts from the committed xcconfig a setting it does not assign`() {
        val violations = scanned.flatMap { file ->
            val lines = file.readLines()
            lines.withIndex().flatMap { (index, line) ->
                extractedSettings(line)
                    .filterNot { it in assignedByCommitted }
                    .filter { sourceRead(lines, index) == COMMITTED }
                    .map { "${file.relativeTo(repoRoot).path}:${index + 1}  extracts `$it`" }
            }
        }
        if (violations.isEmpty()) return
        fail(
            buildString {
                appendLine("${violations.size} reader(s) extract a setting $COMMITTED does not assign:")
                violations.forEach { appendLine("  $it") }
                appendLine()
                appendLine(
                    "$COMMITTED assigns only ${assignedByCommitted.sorted()}. Everything else it once " +
                        "carried now lives in a GENERATED rendering it `#include`s, or in the bundled " +
                        "property list. Reading the committed file for one of those matches nothing and " +
                        "yields the EMPTY STRING, silently: the read succeeds and the caller proceeds.",
                )
                appendLine(
                    "Point the reader at the rendering that owns the key, and make it refuse to proceed on " +
                        "an empty value rather than substituting one — an empty deployment key composes a " +
                        "well-formed artifact making a false claim, which nothing downstream can detect.",
                )
            },
        )
    }

    /**
     * The xcconfig settings [line] uses as a PATTERN — the shape of a read — rather than merely naming.
     *
     * This distinction is the whole guard. `Config.xcconfig`'s own header comment lists every key the
     * generated fragment owns, on purpose, and must stay passable; so must a spec or a design record
     * discussing the split. Co-occurrence is not a read. A setting handed to an extraction tool, or
     * anchored in a regex, is.
     *
     * The underscore is required: every real xcconfig setting has one, and demanding it keeps ordinary
     * shell shouting (`HOME`, `EOF`, `SIGN`) out of the result.
     */
    private fun extractedSettings(line: String): List<String> {
        val byTool = EXTRACTION_TOOLS.any { line.contains(it) }
        return SETTING_TOKEN.findAll(line)
            .filter { byTool || line.substring(maxOf(0, it.range.first - ANCHOR_LOOKBEHIND), it.range.first).contains('^') }
            .map { it.value }
            .toList()
    }

    /**
     * Which xcconfig the extraction at [siteIndex] reads, or `null` if it reads neither.
     *
     * Resolved from the extraction LINE — the file it names, or the shell variable it reads, whose
     * assignment is then searched backwards (`CFG="…/Deployment.xcconfig"` … `awk … "$CFG"`).
     *
     * An earlier draft took the nearest xcconfig mentioned anywhere above instead, and that is not the
     * same question. It mis-attributed `grep '^MARKETING_VERSION_OUT=' "$GITHUB_ENV"` in `ios.yml` to
     * `Config.xcconfig` merely because a comment upstream mentioned it — a real extraction, of a real
     * setting, from an entirely different file. Proximity is not provenance.
     */
    private fun sourceRead(lines: List<String>, siteIndex: Int): String? {
        val line = lines[siteIndex]
        if (line.contains(FRAGMENT_NAME)) return FRAGMENT_NAME
        if (line.contains(COMMITTED)) return COMMITTED
        for (v in SHELL_VAR.findAll(line).map { it.groupValues[1] }.toSet()) {
            for (i in siteIndex downTo maxOf(0, siteIndex - SOURCE_LOOKBEHIND)) {
                val assignment = Regex("""\b${Regex.escape(v)}=(.*)""").find(lines[i]) ?: continue
                val rhs = assignment.groupValues[1]
                if (rhs.contains(FRAGMENT_NAME)) return FRAGMENT_NAME
                if (rhs.contains(COMMITTED)) return COMMITTED
                break
            }
        }
        return null
    }

    private companion object {
        const val COMMITTED = "Config.xcconfig"
        const val FRAGMENT_NAME = "Deployment.xcconfig"
        const val COMMITTED_PATH = "iosApp/Configuration/Config.xcconfig"
        const val RESOLVER = "scripts/resolve-deployment.py"
        const val RESIGN_SKILL = ".claude/skills/ssh-mac-build/SKILL.md"

        val SCANNED_EXTENSIONS = setOf(
            "md", "kt", "kts", "sh", "yml", "yaml", "py", "ts", "json", "plist", "xcconfig", "entitlements",
        )

        /** An xcconfig setting name. The underscore is load-bearing — see [extractedSettings]. */
        val SETTING_TOKEN = Regex("""\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b""")

        /** Tools that take a pattern. A setting passed to one of these is being extracted, not mentioned. */
        val EXTRACTION_TOOLS = listOf("awk", "sed ", "grep", "plutil", "PlistBuddy", "Regex(", "defaults read")

        /** How far back of a setting a `^` still reads as anchoring it (`/^KEY`, `"^\s*KEY`). */
        const val ANCHOR_LOOKBEHIND = 8

        /** How far back the filename bound to the variable an extraction reads may sit. */
        const val SOURCE_LOOKBEHIND = 25

        /** A shell variable read on the extraction line — `"$CFG"`, `$CFG`, `${CFG}`. */
        val SHELL_VAR = Regex("""\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?""")
    }
}
