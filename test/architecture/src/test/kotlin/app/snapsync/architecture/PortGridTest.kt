package app.snapsync.architecture

import app.snapsync.ports.Listenable
import java.io.File
import kotlin.reflect.full.isSuperclassOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The port grid: every answer an adapter can give, as cells** (`docs/testing.md`, "The port grid"). REPORT ONLY —
 * it fails nothing but its own scan breaking.
 *
 * The contract-coverage gate ([ContractCoverageTest]) asks whether every clause runs against something real. This asks
 * the other question: of everything a port's adapter can answer, which cells exist at all. A cell is one of
 *  - `Port.member → Variant` — a member the adapter implements × each variant of what it returns;
 *  - `Port.handlers.field(Arg, …)` — for an event port ([Listenable]), a handler the adapter CALLS × each variant of
 *    every argument it passes (crossed). What the handler returns is the core's answer, not the adapter's, so out;
 *  - `Port.member.param(Arg, …)` — the same for a callback the core hands a member (`BackgroundTime.begin`'s expiry);
 *  - `Port.Handle.member → Variant` — a handle a port hands over ([PortBundleTest]'s not-ports `Completion`,
 *    `BackgroundTimeHold`, `LibraryChangeToken`), counted under each port that hands it out, since each is a
 *    different adapter's.
 *
 * Variants: a sealed type → each leaf subtype (`Reply.Ok`; the OUTER type of a generic only); an enum → each entry;
 * `Boolean` → `true`/`false`; a `Flow<T>` → T's; anything else → one `returns` cell, or its type name as an argument.
 * A nullable type adds `null`. Errors are reduced into values (`docs/architecture.md`), so a throw is a cell only where
 * the member DECLARES it (`@Throws`) — one `throws` cell, whatever it throws. A member
 * the interface itself implements (a default body) is not the adapter's answer and is not a cell; a member inherited
 * from another port is counted under the port declaring it, except a generic base's ([Listenable.listen]), counted
 * under every port that binds it.
 *
 * Read by REFLECTION over the compiled `:domain:ports`, not by text: a regex over the source counts the generic
 * `Reply<T>` as having no subtypes at all. Ports are found as [PortBundleTest] finds them — every interface declared
 * in the zone, filtered to those extending [Port] — so a new port is in the grid by construction.
 *
 * Writes `build/reports/port-grid/port-grid.txt` (sorted, one cell per line — the denominator) and
 * `port-grid-estimate.txt` (each cell marked `~` where a PORT contract's source mentions its member and variant —
 * a size estimate of the missing clauses, NOT coverage). Contracts whose subject is a service are left out of the
 * estimate and listed at its foot: they are not port contracts.
 */
class PortGridTest {

    private val ports = PortGrid.ports
    private val cells = PortGrid.cells

    @Test
    fun `the port grid is written`() {
        val out = File("build/reports/port-grid").apply { mkdirs() }
        File(out, "port-grid.txt").writeText(cells.joinToString("\n", postfix = "\n") { it.text })

        val contracts = contractSources()
        val (service, port) = contracts.partition { it.second }
        val portText = port.joinToString("\n") { it.first.text }
        val looked = cells.associateWith { looksCovered(it, portText) }
        val byPort = cells.groupBy { it.port }.toSortedMap()
        val summary = byPort.map { (p, cs) ->
            "%-24s %4d cells  %4d ~".format(
                p,
                cs.size,
                cs.count { looked.getValue(it) },
            )
        }
        File(out, "port-grid-estimate.txt").writeText(
            buildString {
                appendLine(
                    "# ${cells.size} cells over ${byPort.size} ports; ${looked.values.count { it }} mentioned by a port contract's source (~).",
                )
                appendLine("# An ESTIMATE of the gap, not coverage: a mention is not a clause asserting the cell.")
                summary.forEach { appendLine("# $it") }
                appendLine()
                cells.forEach { appendLine((if (looked.getValue(it)) "~ " else "  ") + it.text) }
                appendLine()
                appendLine("# Service-subject contracts, outside the estimate (not port contracts):")
                service.map { it.first.path }.sorted().forEach { appendLine("#   $it") }
            },
        )
        println("port grid: ${cells.size} cells over ${byPort.size} ports → ${out.absolutePath}")
        summary.forEach { println("  $it") }
    }

    // ---- non-vacuity: the derivation still reads what it was built against ------------------------------------

    @Test
    fun `the grid finds the ports`() {
        assertTrue(
            ports.size >= 30,
            "port grid: only ${ports.size} ports found — the reflection over :domain:ports broke",
        )
    }

    @Test
    fun `the grid expands a generic sealed return, an enum, a nullable, a handle and a declared throw`() {
        val texts = cells.map { it.text }.toSet()
        val canaries = listOf(
            "Backend.challenge → Reply.Unreachable",
            "SecureStore.read → SecureStoreRead.Unavailable",
            "LibraryChangeTokenRead.changeToken → null",
            "Wake.ExpiringCompletion.complete → returns",
            "AttestStore.token → throws",
        )
        val missing = canaries - texts
        assertTrue(missing.isEmpty(), "port grid: expected cells are missing, so the variant expansion broke: $missing")
    }

    @Test
    fun `every event port has handler cells`() {
        val bare = ports.filter { Listenable::class.isSuperclassOf(it) }
            .map { it.simpleName!! }
            .filter { p -> cells.none { it.port == p && ".handlers." in it.text } }
        assertTrue(bare.isEmpty(), "port grid: event ports with no handler cell — the handler bundle read broke: $bare")
    }

    // ---- estimate -------------------------------------------------------------------------------------------

    private fun looksCovered(cell: PortGrid.Cell, text: String): Boolean {
        if (!Regex("""\b${Regex.escape(cell.member)}\b""").containsMatchIn(text)) return false
        return cell.variants.all { v ->
            v == PortGrid.RETURNS || v == PortGrid.THROWS || v == "true" || v == "false" || v == "null" || v.firstOrNull()?.isUpperCase() != true ||
                v.substringAfterLast('.').let { leaf -> Regex("""\b${Regex.escape(leaf)}\b""").containsMatchIn(text) }
        }
    }

    /** Every contract source, paired with whether its subject is a service (`Contract<S, X>`/`ClauseList<S, X>`, X from services). */
    private fun contractSources(): List<Pair<SourceScan.Source, Boolean>> {
        val dir = File(SourceScan.repoRoot, CONTRACTS_DIR)
        assertTrue(dir.isDirectory, "port grid: $dir is gone — re-point the estimate")
        return dir.walk().filter { it.extension == "kt" }.map(SourceScan::Source).map { src ->
            val subjects = SUBJECT.findAll(src.text).map { it.groupValues[1] }.toList()
            val services = SERVICE_IMPORT.findAll(src.text).map { it.groupValues[1] }.toSet()
            src to (subjects.isNotEmpty() && subjects.all { it in services })
        }.toList()
    }

    private companion object {
        const val CONTRACTS_DIR = "test/contracts/src/commonMain/kotlin/app/snapsync/contracts"
        val SUBJECT = Regex("""(?:Contract|ClauseList)<\w+,\s*(\w+)""")
        val SERVICE_IMPORT = Regex("""^import app\.snapsync\.services\.[\w.]*\.(\w+)$""", RegexOption.MULTILINE)
    }
}
