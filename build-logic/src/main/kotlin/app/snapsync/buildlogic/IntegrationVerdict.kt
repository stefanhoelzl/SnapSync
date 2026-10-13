package app.snapsync.buildlogic

import java.math.BigDecimal
import java.math.RoundingMode

/** One module's measurement: [counters] by unit, covered of total, and its targets. */
data class Measured(
    val path: String,
    val counters: Map<String, Pair<Long, Long>>,
    val instructions: Int,
    val branches: Int,
)

/** The integration measurement's judgement, pure so it is tested without Gradle. */
object IntegrationVerdict {
    private val UNITS = listOf("INSTRUCTION" to "instructions", "BRANCH" to "branches")

    /** Every target missed, every exception no longer needed, every module measured empty — one sentence each. */
    fun problems(measured: List<Measured>, goal: Pair<Int, Int>): List<String> = measured.flatMap { m ->
        if ((m.counters["INSTRUCTION"]?.second ?: 0L) == 0L) {
            return@flatMap listOf("${m.path}: the report holds no instruction of it — are its JVM classes the ones measured?")
        }
        UNITS.mapNotNull { (unit, name) ->
            val target = if (unit == "INSTRUCTION") m.instructions else m.branches
            val wanted = if (unit == "INSTRUCTION") goal.first else goal.second
            val (covered, total) = m.counters[unit] ?: (0L to 0L)
            val shown = percent(covered, total)
            when {
                !meets(covered, total, target) -> "${m.path}: integration $name $shown < $target %"
                target < wanted && meets(covered, total, wanted) ->
                    "${m.path}: integration $name $shown ≥ the goal's $wanted %: its exception of $target % is no " +
                        "longer needed — remove it"
                else -> null
            }
        }
    }

    /** The table every run writes: measured, target, and the gap to the goal. */
    fun summary(measured: List<Measured>, goal: Pair<Int, Int>): String = buildString {
        append("# What the integration suite alone covers\n\n")
        append("Goal: ${goal.first} % instructions, ${goal.second} % branches. Targets from the root build file's ")
        append("`coverage { }`.\n\n")
        append("| module | instructions | target | branches | target |\n|---|---|---|---|---|\n")
        measured.sortedBy { it.path }.forEach { m ->
            val (ic, it) = m.counters["INSTRUCTION"] ?: (0L to 0L)
            val (bc, bt) = m.counters["BRANCH"] ?: (0L to 0L)
            append("| `${m.path}` | ${percent(ic, it)} ($ic/$it) | ${m.instructions} % | ")
            append("${percent(bc, bt)} ($bc/$bt) | ${m.branches} % |\n")
        }
    }

    /** No branches (or no instructions) at all meets any target: there is nothing to miss. */
    private fun meets(covered: Long, total: Long, target: Int): Boolean =
        total == 0L || BigDecimal(covered).multiply(BigDecimal(100)) >= BigDecimal(total).multiply(BigDecimal(target))

    private fun percent(covered: Long, total: Long): String =
        if (total == 0L) "–" else BigDecimal(covered * 100).divide(BigDecimal(total), 2, RoundingMode.DOWN).toPlainString() + " %"
}
