package app.snapsync.buildlogic

/**
 * The root build file's `coverage { }` block (`docs/architecture.md`, "Coverage"): every module's coverage, described
 * in one place. An entry names a module and settles each measurement — a target, or a [Measurement.skipped] with its
 * reason:
 *
 * ```
 * coverage {
 *     integrationGoal(instructions = 95, branches = 85)
 *     ":domain:services" {
 *         skipped("SQLDelight's generated code; …", packages = listOf("app.snapsync.services.ledger.db"))
 *         unit(alsoCoveredBy = listOf(":adapter:generic:mock"))
 *         integration(instructions = 80, branches = 58)
 *     }
 *     ":adapter:generic:mock" { skipped("test equipment") }
 * }
 * ```
 *
 * WHERE a `skipped` stands decides what it skips: in the entry it skips the module from every measurement, inside a
 * measurement's block only that measurement; with `packages` only those packages, without them everything at its
 * level. A reason is required, so what the block leaves out is read in review.
 */
abstract class CoverageExtension {
    internal val entries = mutableListOf<ModuleCoverage>()
    internal var goal: Pair<Int, Int>? = null

    /**
     * The GOAL of the integration measurement: what `:test:integration` alone covers of each module, in whole
     * percentages. An `integration(…)` below it is an EXCEPTION — today's measurement, which only rises and fails the
     * build once the module no longer needs it.
     */
    fun integrationGoal(instructions: Int, branches: Int) {
        goal = instructions to branches
    }

    /** The entry for the module at this path. */
    operator fun String.invoke(configure: ModuleCoverage.() -> Unit) {
        entries += ModuleCoverage(this).apply(configure)
    }
}

/** What a [Measurement.skipped] call left out: [packages], or everything at its level when empty. */
data class Skip(val reason: String, val packages: List<String>) {
    val whole: Boolean get() = packages.isEmpty()
}

/** A measurement's block; also the entry itself, where a skip applies to every measurement. */
open class Measurement {
    internal val skips = mutableListOf<Skip>()

    /** Leaves [packages] — or, with none, everything at this level — out, for [reason]. */
    fun skipped(reason: String, packages: List<String> = emptyList()) {
        skips += Skip(reason, packages)
    }

    internal val wholeSkip: Skip? get() = skips.firstOrNull { it.whole }

    internal val skippedPackages: List<String> get() = skips.flatMap { it.packages }
}

/** The UNIT measurement: the module's own tests, plus [alsoCoveredBy]'s, held at zero missed. */
class UnitCoverage(val alsoCoveredBy: List<String>) : Measurement()

/** The INTEGRATION measurement's targets; null is the goal. */
class IntegrationCoverage(val instructions: Int?, val branches: Int?, val targeted: Boolean) : Measurement()

/** One module's entry. */
class ModuleCoverage(val path: String) : Measurement() {
    internal var unit: UnitCoverage? = null
    internal var units = 0
    internal var integration: IntegrationCoverage? = null
    internal var integrations = 0

    /**
     * What `:test:integration` ALONE covers of this module, in whole percentages: the goal when a target is left out,
     * below it an exception, above it a stricter target. It counts toward no unit measurement — it judges the suite.
     */
    fun integration(instructions: Int? = null, branches: Int? = null, configure: Measurement.() -> Unit = {}) {
        integrations++
        integration = IntegrationCoverage(instructions, branches, targeted = true).apply(configure)
    }

    /** The integration measurement with no target: its block must skip it, `integration { skipped("<reason>") }`. */
    fun integration(configure: Measurement.() -> Unit) {
        integrations++
        integration = IntegrationCoverage(null, null, targeted = false).apply(configure)
    }

    /**
     * The zero gate (`docs/architecture.md`, "Coverage"): no instruction and no branch missed, outside compiler glue.
     * The module's own tests count, and so do [alsoCoveredBy]'s — modules whose tests a placement rule put elsewhere.
     * No number: zero is the only state, so a miss is fixed by a test.
     */
    fun unit(alsoCoveredBy: List<String> = emptyList(), configure: Measurement.() -> Unit = {}) {
        units++
        unit = UnitCoverage(alsoCoveredBy).apply(configure)
    }

    /** Every problem with this entry, each a sentence naming the module; empty when it is well-formed. */
    internal fun problems(exists: (String) -> Boolean): List<String> = buildList {
        if (!exists(path)) add("$path is not a module of this build")
        (skips + unit?.skips.orEmpty() + integration?.skips.orEmpty()).filter { it.reason.isBlank() }.forEach {
            add("$path skips ${it.packages.ifEmpty { listOf("everything") }} with no reason")
        }
        if (units > 1) add("$path declares unit() $units times")
        if (integrations > 1) add("$path declares integration() $integrations times")
        val u = unit
        val i = integration
        if (wholeSkip != null) {
            if (u != null) add("$path is skipped as a whole, yet declares unit()")
            if (i != null) add("$path is skipped as a whole, yet declares integration()")
            return@buildList
        }
        when {
            i == null -> add(
                "$path does not settle its integration measurement: integration(…), or " +
                    "integration { skipped(\"<reason>\") }",
            )
            i.targeted && i.wholeSkip != null -> add("$path sets integration targets, yet skips the measurement")
            !i.targeted && i.wholeSkip == null ->
                add("$path declares integration { } with no target: skip it with a reason, or set one")
        }
        listOfNotNull(i?.instructions, i?.branches).filter { it !in 0..100 }.forEach {
            add("$path sets an integration target of $it %, outside 0..100")
        }
        if (u == null) add("$path does not settle its unit measurement: unit(…), or unit { skipped(\"<reason>\") }")
        if (u != null && u.wholeSkip != null && u.alsoCoveredBy.isNotEmpty()) {
            add("$path skips its unit measurement, yet names modules that also cover it")
        }
        u?.alsoCoveredBy?.filterNot(exists)?.forEach { add("$path is also covered by $it, which is not a module of this build") }
    }
}
