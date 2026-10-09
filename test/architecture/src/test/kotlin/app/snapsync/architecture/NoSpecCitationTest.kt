package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Production code cites no spec** (`docs/architecture.md`, "Tests verify requirements").
 *
 * Specs describe what a user observes, and they are re-cut as the product moves. A class that names a
 * capability or a requirement in its KDoc goes stale without failing anything. Measured before this gate:
 * of the requirement titles production KDoc quoted, fewer than a third still existed. So the link lives on
 * the tests that verify the behaviour (`@Verifies`, checked by [VerifiesGateTest]), and this gate keeps
 * citations from creeping back into production code. The explanation a citation sat in stays.
 *
 * Scope is derived: every `.kt` and `.kts` outside a test source set ([Traceability.testSourceSet]) — every
 * `*Main`, `main`, `rig`, `prod`, the hooks, the test-support modules' main code and every build script.
 *
 * HEURISTIC, and it says so: it matches a capability's name in backticks — every current spec directory plus the
 * retired names below — and the `capability \`…\`` shape for any name. A citation in other words ("see the
 * photo-sharing spec") is not caught.
 */
class NoSpecCitationTest {

    private val names = Traceability.specs().map { it.name } + RETIRED
    private val citation = Regex(
        """`(${names.joinToString("|") { Regex.escape(it) }})`|\bcapabilit(y|ies)\s+`""",
    )

    private val production = SourceScan.files(setOf("kt", "kts"))
        .filter { Traceability.testSourceSet(it.path) == null }

    @Test
    fun `the scan reaches every production tree`() {
        val reached = production.map { it.path.split('/')[1] }.toSet()
        val missing = TREES - reached
        assertTrue(
            missing.isEmpty(),
            "no production .kt/.kts scanned under $missing — the layout moved, and a citation there would pass unseen",
        )
    }

    @Test
    fun `no production source cites a spec`() {
        val hits = production.flatMap { source ->
            source.text.lineSequence().mapIndexedNotNull { index, line ->
                if (citation.containsMatchIn(line)) "  ${source.path}:${index + 1}  ${line.trim()}" else null
            }.toList()
        }
        if (hits.isEmpty()) return
        fail(
            "production code cites no spec (${hits.size}):\n" + hits.joinToString("\n") +
                "\nKeep the WHY in the comment, drop the pointer, and link the requirement from the test that " +
                "verifies it (`@Verifies`).",
        )
    }

    private companion object {
        /**
         * Top-level trees that hold production Kotlin. Pinned so a moved tree fails here instead of dropping out
         * of scope. A new tree joins the scan anyway; it is only missing from this check.
         */
        val TREES = setOf("domain", "adapter", "ui", "app", "test", "tools", "build-logic")

        /**
         * Capability names retired by earlier spec re-cuts (`changes/archive/2026-07-21-align-specs-with-mission`
         * and `changes/archive/2026-10-09-recut-capabilities-and-crash-ids`). A citation of a name that no longer exists is still a citation.
         */
        val RETIRED = listOf("background-upload", "event-creation", "asset-manifest")
    }
}
