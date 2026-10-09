package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every clause declares the port-grid cells it covers, and every declared cell exists** (`docs/testing.md`, "Every
 * clause declares the cells it covers").
 *
 * The contracts are loaded as values ([ContractCatalog]), so a declaration is read as every host renders it — through
 * `:test:contracts`' common renderer — and compared with the grid [PortGrid] derives from `:domain:ports`. Fails on a
 * clause with no declared cell, and on a declared cell the grid does not hold: a stale name, or a combination no
 * adapter can answer.
 *
 * **Every grid cell is covered** (`docs/testing.md`, "Every cell is covered"): declared by a clause that runs against a
 * real implementation on some host ([ContractCoverage.coveredCells]). There is no exception: new port surface without
 * a clause fails at once, and the fix is the clause, or a port that no longer has the cell.
 *
 * REPORTS the grid cells no clause claims, and — separately — the cells claimed only by clauses that run against no
 * real implementation on any host ([ContractCoverage.isReal]), and the cells claimed only WEAKLY, inside a clause's
 * one-of groups — a weak claim does not cover a cell, since a run may see the group's other cell every time. All are
 * written to `build/reports/port-grid/clause-covers.txt`.
 *
 * Whether a declared cell really occurs is the runner's check, on every host a clause runs (`docs/testing.md`, "A declared
 * cell must occur"); this gate holds the declarations to the grid.
 */
class ClauseCoversTest {

    private val grid = PortGrid.cells.map { it.text }.toSet()

    private class Claim(val contract: String, val clause: String, val cell: String, val real: Boolean, val weak: Boolean)

    private val claims: List<Claim> = ContractCatalog.contracts.flatMap { contract ->
        contract.clauses.flatMap { clause ->
            val real = ContractCoverage.isReal(contract, clause)
            clause.covers.map { Claim(contract.name, clause.id, it, real, weak = false) } +
                clause.oneOf.flatten().map { Claim(contract.name, clause.id, it, real, weak = true) }
        }
    }

    @Test
    fun `every clause declares at least one cell`() {
        val bare = ContractCatalog.contracts.flatMap { c ->
            c.clauses.filter { it.covers.isEmpty() && it.oneOf.isEmpty() }.map { "${c.name} / ${it.id}" }
        }
        if (bare.isNotEmpty()) {
            fail(
                "these clauses declare no port-grid cell. A clause that pins no answer of a port is not a port clause — " +
                    "declare the cells it checks, or move it to a service or mock test:\n  " + bare.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `every declared cell is a cell of the grid`() {
        val unknown = claims.filter { it.cell !in grid }.map { "${it.contract} / ${it.clause}: ${it.cell}" }
        if (unknown.isNotEmpty()) {
            fail(
                "these declared cells are not in the port grid (build/reports/port-grid/port-grid.txt) — a renamed " +
                    "variant, a member counted under another port, or an answer no adapter can give:\n  " +
                    unknown.joinToString("\n  "),
            )
        }
    }

    private val covered = ContractCoverage.coveredCells

    @Test
    fun `every grid cell is covered`() {
        val uncovered = (grid - covered).sorted()
        if (uncovered.isNotEmpty()) {
            fail(
                "these port-grid cells are covered by no clause run against a real implementation. Write the clause " +
                    "that covers them, or change the port so the cell does not exist:\n  " +
                    uncovered.joinToString("\n  "),
            )
        }
    }

    /**
     * Fails closed against an exemption list's return under any name: the contracts module's top level holds its build
     * file, its sources and its recordings, and nothing else a gate could be taught to read as "not yet covered".
     */
    @Test
    fun `the contracts module holds no list beside its recordings`() {
        val dir = File(SourceScan.repoRoot, "test/contracts")
        val stray = dir.list().orEmpty().filterNot { it in CONTRACTS_TOP_LEVEL }.sorted()
        if (stray.isNotEmpty()) {
            fail(
                "test/contracts holds $stray beside ${CONTRACTS_TOP_LEVEL.sorted()}. Every grid cell is covered, " +
                    "with no list of exceptions: write the clause instead, and move anything else into src/.",
            )
        }
    }

    @Test
    fun `the claims are reported`() {
        val claimed = claims.filter { it.cell in grid && !it.weak }.groupBy { it.cell }
        val real = claimed.filterValues { cs -> cs.any { it.real } }.keys
        val mockOnly = claimed.keys - real
        val weakOnly = claims.filter { it.cell in grid && it.weak && it.cell !in claimed }.groupBy { it.cell }
        val unclaimed = grid - claimed.keys - weakOnly.keys
        val summary = "${grid.size} cells; ${claimed.size} claimed, ${real.size} via a real host, " +
            "${mockOnly.size} mock only, ${weakOnly.size} weak only, ${unclaimed.size} unclaimed"
        val out = File("build/reports/port-grid").apply { mkdirs() }
        File(out, "clause-covers.txt").writeText(
            buildString {
                appendLine("# $summary")
                appendLine()
                appendLine("# Claimed only by clauses no real implementation runs (mock only):")
                mockOnly.sorted().forEach { cell ->
                    appendLine("  $cell  ← ${claimed.getValue(cell).joinToString { "${it.contract}/${it.clause}" }}")
                }
                appendLine()
                appendLine("# Claimed only inside one-of groups (weak — not covered):")
                weakOnly.keys.sorted().forEach { cell ->
                    appendLine("  $cell  ← ${weakOnly.getValue(cell).joinToString { "${it.contract}/${it.clause}" }}")
                }
                appendLine()
                appendLine("# Claimed by no clause:")
                unclaimed.sorted().forEach { appendLine("  $it") }
            },
        )
        println("clause covers: $summary → ${out.absolutePath}/clause-covers.txt")
        assertTrue(real.isNotEmpty(), "no cell is claimed through a real host — the catalog or the coverage read broke")
    }

    private companion object {
        val CONTRACTS_TOP_LEVEL = setOf("build.gradle.kts", "src", "recordings", "build")
    }
}
