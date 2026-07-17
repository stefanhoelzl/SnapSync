package app.snapsync.tools.diagrams

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The diagram freshness gate (capability `architecture-diagrams`): regenerate in-process and
 * compare against the committed files, so `./gradlew build` — the canonical check — fails on a
 * structural change that skipped `./gradlew architectureDiagrams`. Deliberately NOT `git diff`
 * (fails open on untracked files, lives outside the build, assumes a clean tree) and deliberately
 * NOT in `:test:architecture` (that module depends on no project modules so no dependency edge can
 * defeat a guard; this gate needs the generator library, so it lives with it).
 *
 * The module graph is the one diagram a test cannot regenerate (only Gradle sees the project
 * model), so `architectureModulesDiagram` dumps its model into the committed sidecar
 * `architecture/.modules-inputs.txt` and this test re-renders `modules.md` from it — which gates
 * the renderer and the sidecar↔diagram consistency; the sidecar's own staleness is healed by the
 * regeneration workflow on main.
 */
class DiagramFreshnessTest {

    private val root = repoRoot()
    private val regenerate = "stale diagrams — run `./gradlew architectureDiagrams` and commit the result"

    @Test
    fun committedDiagramsMatchRegeneration() {
        val expected = generateSourceDiagrams(root).toMutableMap()
        val sidecar = File(root, "architecture/.modules-inputs.txt")
        assertTrue(sidecar.isFile, "architecture/.modules-inputs.txt is missing — $regenerate")
        val (modules, edges) = parseModulesSidecar(sidecar.readText(Charsets.UTF_8))
        expected["architecture/modules.md"] = renderModulesMarkdown(modules, edges)

        val problems = mutableListOf<String>()
        for ((rel, want) in expected.toSortedMap(compareBy { it })) {
            val file = File(root, rel)
            if (!file.isFile) {
                problems += "$rel: missing"
                continue
            }
            val got = file.readText(Charsets.UTF_8)
            if (got != want) problems += "$rel: differs from regeneration\n${firstDiff(want, got)}"
        }
        // Hand-added content SHALL NOT survive (spec): anything under architecture/ that the
        // generators did not produce is a failure, not a mystery.
        val known = expected.keys + "architecture/.modules-inputs.txt"
        File(root, "architecture").walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (rel !in known) problems += "$rel: not a generated file — hand-added content does not survive"
        }
        if (problems.isNotEmpty()) {
            fail("$regenerate\n\n${problems.joinToString("\n\n")}")
        }
    }

    /** Non-vacuity twin: the generators must actually produce the full diagram set, deterministically. */
    @Test
    fun generatorsProduceTheFullDeterministicSet() {
        val first = generateSourceDiagrams(root)
        assertTrue("architecture/zones.md" in first, "zones generator produced nothing")
        assertTrue("architecture/ports.md" in first, "ports generator produced nothing")
        assertTrue("architecture/features.md" in first, "feature-cards generator produced nothing")
        assertTrue("architecture/di.md" in first, "DI generator produced nothing")
        assertTrue(first.keys.any { it.startsWith("architecture/flows/") }, "flow transcriber produced nothing")
        // Determinism within one process is necessary (not sufficient) for the spec's
        // byte-determinism requirement; the cross-host half is carried by the fixed sorts and
        // explicit encodings in the generators.
        assertEquals(first, generateSourceDiagrams(root), "generation is not deterministic")
    }

    private fun firstDiff(want: String, got: String): String {
        val wantLines = want.split("\n")
        val gotLines = got.split("\n")
        val i = (0 until minOf(wantLines.size, gotLines.size))
            .firstOrNull { wantLines[it] != gotLines[it] }
            ?: minOf(wantLines.size, gotLines.size)
        val expectedLine = wantLines.getOrElse(i) { "<end of file>" }
        val actualLine = gotLines.getOrElse(i) { "<end of file>" }
        return "  first difference at line ${i + 1}:\n  regenerated: $expectedLine\n  committed:   $actualLine"
    }
}
