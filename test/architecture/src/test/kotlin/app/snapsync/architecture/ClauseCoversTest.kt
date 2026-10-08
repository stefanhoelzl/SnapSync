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
 * REPORTS, and does not fail on, the grid cells no clause claims, and — separately — the cells claimed only by clauses
 * that run against no real implementation on any host ([ContractCoverage.isReal]): those are claimed, but only a mock
 * has ever answered them. Both are written to `build/reports/port-grid/clause-covers.txt`.
 *
 * Declarations are trusted: nothing here checks a clause really drives the cell it names.
 */
class ClauseCoversTest {

    private val grid = PortGrid.cells.map { it.text }.toSet()

    private class Claim(val contract: String, val clause: String, val cell: String, val real: Boolean)

    private val claims: List<Claim> = ContractCatalog.contracts.flatMap { contract ->
        contract.clauses.flatMap { clause ->
            val real = ContractCoverage.isReal(contract, clause)
            clause.covers.map { Claim(contract.name, clause.id, it, real) }
        }
    }

    @Test
    fun `every clause declares at least one cell`() {
        val bare = ContractCatalog.contracts.flatMap { c -> c.clauses.filter { it.covers.isEmpty() }.map { "${c.name} / ${it.id}" } }
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

    @Test
    fun `the claims are reported`() {
        val claimed = claims.filter { it.cell in grid }.groupBy { it.cell }
        val real = claimed.filterValues { cs -> cs.any { it.real } }.keys
        val mockOnly = claimed.keys - real
        val unclaimed = grid - claimed.keys
        val summary = "${grid.size} cells; ${claimed.size} claimed, ${real.size} via a real host, " +
            "${mockOnly.size} mock only, ${unclaimed.size} unclaimed"
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
                appendLine("# Claimed by no clause:")
                unclaimed.sorted().forEach { appendLine("  $it") }
            },
        )
        println("clause covers: $summary → ${out.absolutePath}/clause-covers.txt")
        assertTrue(real.isNotEmpty(), "no cell is claimed through a real host — the catalog or the coverage read broke")
    }
}
