package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every adapter is covered** (`docs/testing.md`): [ClauseCoversTest] holds every grid cell to some real host; this
 * holds every ADAPTER to its own — a port's second adapter can no longer lean on the first one's proof. There is no
 * exception: an owed cell is proven by a real binding of the code that answers it, or claimed in a `@NeverAnswers` the
 * compiled code is checked to support, or the adapter or the port changes so the cell is not owed.
 *
 * Writes the per-adapter reading to `build/reports/port-grid/adapters.txt` — output only: nothing reads it back.
 */
class AdapterCoverageTest {

    private val adapters = AdapterCoverage.adapters.values.sortedBy { it.fqn }

    @Test
    fun `the adapter set is derived from the compiled code`() {
        // Non-vacuity: the readers broke if the set lost the adapters every platform has.
        val expected =
            setOf(
                "app.snapsync.files.IosFiles",
                "app.snapsync.android.storage.AndroidFiles",
                "app.snapsync.files.JvmFiles",
            )
        assertTrue(
            adapters.map {
                it.fqn
            }.containsAll(expected),
            "the adapter set misses ${expected - adapters.map { it.fqn }.toSet()}",
        )
    }

    @Test
    fun `every binding names a class its target links`() {
        val wrong = ContractCoverage.bindings.mapNotNull { b ->
            val adapter = b.adapter ?: return@mapNotNull "${b.file}: names no adapter (`override val adapter = X::class`)"
            if (AdapterCoverage.isMockBinding(b)) return@mapNotNull null
            val base = CodeBase.all.getValue(AdapterCoverage.targetOf(b.host))
            when {
                adapter !in AdapterCoverage.adapters -> "${b.file}: $adapter is no production class implementing a grid port"
                adapter !in base.classes -> "${b.file}: $adapter is not linked on ${base.target.label}, which ${b.host} runs"
                else -> null
            }
        }
        if (wrong.isNotEmpty()) {
            fail(
                "bindings whose adapter is not a production adapter of their host:\n  " + wrong.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `every adapter has a real binding`() {
        val bound = AdapterCoverage.realCredits.map { it.binding.adapter }.toSet()
        val bare = adapters.filter { it.concrete && it.fqn !in bound }.map { it.fqn }
        if (bare.isNotEmpty()) {
            fail(
                "these adapters run under no real binding — nothing proves them. Bind each on a host CI runs or records:\n  " + bare.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `every claim holds in the compiled code`() {
        val bad = AdapterCoverage.claims.filter { it.problem != null }
        if (bad.isNotEmpty()) {
            fail(
                "these @NeverAnswers claims are refused — the code could give the answer, or the claim names no owed cell:\n  " +
                    bad.joinToString("\n  ") { "${it.adapter} ${it.written}: ${it.problem}" },
            )
        }
    }

    @Test
    fun `every owed cell is covered`() {
        val gaps = adapters.associate { it.fqn to AdapterCoverage.uncovered(it) }.filterValues { it.isNotEmpty() }
        if (gaps.isNotEmpty()) {
            fail(
                "these adapters owe cells no real binding of their code proves and no @NeverAnswers claims. Write the " +
                    "clause or the binding, claim what the code cannot answer, or change the port:\n" +
                    gaps.entries.joinToString("\n") { (a, cells) -> "  $a\n" + cells.sorted().joinToString("\n") { "      $it" } },
            )
        }
    }

    @Test
    fun `every substitute answers what its counterpart is proven to answer`() {
        val gaps = AdapterCoverage.substitutes.mapValues { (s, reals) ->
            AdapterCoverage.standInGap(
                s,
                reals,
            )
        }.filterValues {
            it.isNotEmpty()
        }
        if (gaps.isNotEmpty()) {
            fail(
                "these simulator substitutes never answered cells their iPhone counterparts are proven to answer:\n" +
                    gaps.entries.joinToString("\n") { (s, cells) ->
                        "  $s (for ${AdapterCoverage.substitutes[s]})\n" + cells.sorted().joinToString("\n") {
                            "      $it"
                        }
                    },
            )
        }
    }

    @Test
    fun `every mock has a binding`() {
        val bound = ContractCoverage.bindings.filter(AdapterCoverage::isMockBinding).mapNotNull { it.adapter }.toSet()
        val bare = AdapterCoverage.mocks.keys.filter { it !in bound }.sorted()
        if (bare.isNotEmpty()) {
            fail(
                "these mocks are bound to no contract, so nothing holds them to what they stand in for:\n  " + bare.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `the mocks answer what the real adapters are proven to answer`() {
        val gaps = AdapterCoverage.mockGaps
        if (gaps.isNotEmpty()) {
            fail(
                "no mock binding runs these cells, which a real adapter of the port is proven to answer:\n" +
                    gaps.toSortedMap().entries.joinToString("\n") { (p, cells) ->
                        "  $p\n" + cells.sorted().joinToString("\n") {
                            "      $it"
                        }
                    },
            )
        }
    }

    @Test
    fun `the per-adapter reading is reported`() {
        val out = File("build/reports/port-grid").apply { mkdirs() }
        File(out, "adapters.txt").writeText(
            buildString {
                appendLine("# ${adapters.size} adapters")
                adapters.forEach { a ->
                    val owed = AdapterCoverage.owed(a).map { it.text }.toSet()
                    val gaps = AdapterCoverage.uncovered(a)
                    appendLine()
                    appendLine(
                        "${a.fqn}  [${a.targets.joinToString { it.label }}]  owes ${owed.size}, uncovered ${gaps.size}",
                    )
                    AdapterCoverage.claims.filter { it.adapter == a.fqn }.forEach { c ->
                        appendLine("  claim ${c.written}: ${c.problem ?: "holds"}")
                    }
                    gaps.sorted().forEach { appendLine("  uncovered $it") }
                }
                appendLine()
                appendLine("# substitutes")
                AdapterCoverage.substitutes.forEach { (s, r) ->
                    appendLine(
                        "  $s for $r: missing ${AdapterCoverage.standInGap(s, r).sorted()}",
                    )
                }
                appendLine()
                appendLine("# mocks")
                AdapterCoverage.mockGaps.toSortedMap().forEach { (p, cells) ->
                    appendLine(
                        "  $p: missing ${cells.sorted()}",
                    )
                }
            },
        )
        assertTrue(adapters.isNotEmpty())
    }
}
