package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **CLAUDE.md's laws digest names exactly the `module-architecture` spec's requirement set**
 * (capability `architecture-guards`; decision record: `establish-target-architecture` D11).
 *
 * The laws live in the spec — the contract of record. But agents write most code with CLAUDE.md in
 * context, and under the beacon-only migration posture (D8) the in-context digest is the only
 * drift defense there is: a law an agent never sees never shapes a diff. So CLAUDE.md carries a
 * one-line-per-law digest — a duplicate, which this repo forbids UNLESS it is loud-when-stale.
 * This guard is the loudness: add, rename, or remove a requirement in the spec without touching
 * the digest (or vice versa) and the build fails naming the delta. The previous CLAUDE.md module
 * graph rotted silently for months precisely because nothing held it to anything.
 *
 * The comparison is by requirement NAME only (the digest's one-liners are deliberately not the
 * spec's normative text — two authorities would be worse than one). Names are matched exactly.
 */
class LawsDigestTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue(file.isFile, "guard is scanning nothing — $relative not found from $repoRoot")
        return file.readText()
    }

    private val specNames: Set<String> =
        Regex("""^### Requirement: (.+)$""", RegexOption.MULTILINE)
            .findAll(read("openspec/specs/module-architecture/spec.md"))
            .map { it.groupValues[1].trim() }
            .toSet()

    private val digestNames: Set<String> = run {
        val claudeMd = read("CLAUDE.md")
        val start = claudeMd.indexOf("## The laws (digest)")
        assertTrue(start >= 0, "CLAUDE.md has no `## The laws (digest)` section — the in-context copy is gone")
        val body = claudeMd.substring(start).substringAfter('\n')
        val section = body.substringBefore("\n## ")
        Regex("""^- \*\*(.+?)\*\*""", RegexOption.MULTILINE)
            .findAll(section)
            .map { it.groupValues[1].trim() }
            .toSet()
    }

    @Test
    fun `the digest actually scanned both sides`() {
        // Non-vacuity twins: a rename that empties either extraction must fail here, never pass silently.
        assertTrue(specNames.size >= 5, "spec parse found only ${specNames.size} requirements — extraction is broken")
        assertTrue(digestNames.size >= 5, "digest parse found only ${digestNames.size} laws — extraction is broken")
    }

    @Test
    fun `the digest names exactly the spec's requirement set`() {
        val missing = specNames - digestNames
        val extra = digestNames - specNames
        if (missing.isEmpty() && extra.isEmpty()) return
        fail(
            buildString {
                appendLine("CLAUDE.md's laws digest has drifted from openspec/specs/module-architecture/spec.md.")
                if (missing.isNotEmpty()) appendLine("  in the spec but not the digest: $missing")
                if (extra.isNotEmpty()) appendLine("  in the digest but not the spec: $extra")
                appendLine(
                    "Fix BOTH sides in one commit: the spec is the authority (full requirement text), the " +
                        "digest is the in-context copy agents code against — under the beacon-only migration " +
                        "posture it is the only drift defense (decision D8/D11, establish-target-architecture).",
                )
            },
        )
    }
}
