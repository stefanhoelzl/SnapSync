package app.snapsync.architecture

import java.io.File

/**
 * **Which requirements and scenarios a test verifies, and which none does** —
 * `./gradlew :test:architecture:verifiesReport`, written to the path in `args[0]` and its table printed.
 *
 * Non-gating: a scenario without a test is a gap to close, not a defect in what is there. The gate on the
 * claims themselves is [VerifiesGateTest]. Generated, never committed: it is the working map the per-concept
 * coverage reviews start from, and a committed copy would need a freshness gate of its own.
 *
 * Claims come from the Kotlin test source sets and the Deno suites alike ([Traceability.claims]). A scenario-level
 * claim counts its requirement as verified, marked "(via scenario)". A scenario on [AcceptedUnclaimed] is counted
 * apart and never listed as unclaimed, so the unclaimed lists hold only work still to do. Claims that do not resolve
 * are left out here; the gate names them.
 */
fun main(args: Array<String>) {
    val out = File(args.single())
    val report = VerifiesReport.render(Traceability.specs(), Traceability.claims(), AcceptedUnclaimed.entries)
    out.parentFile.mkdirs()
    out.writeText(report)
    println(report.substringBefore("\n## "))
    println("written to $out")
}

internal object VerifiesReport {

    fun render(
        specs: List<Traceability.Spec>,
        claims: List<Traceability.Claim>,
        accepted: List<AcceptedUnclaimed.Entry>,
    ): String {
        val bySpec = claims.filter { it.problem == null }.groupBy { it.spec }
        val acceptedBySpec = accepted.groupBy { it.spec }
        val table = StringBuilder()
        val details = StringBuilder()
        var verifiedTotal = 0
        var scenarioTotal = 0
        var claimedTotal = 0
        var acceptedTotal = 0
        for (spec in specs) {
            val claimsOf = bySpec[spec.name].orEmpty().groupBy { it.requirement }
            val claimedScenarios = bySpec[spec.name].orEmpty().map { it.requirement to it.scenario }.toSet()
            val acceptedScenarios = acceptedBySpec[spec.name].orEmpty().associateBy { it.requirement to it.scenario }
            val verified = spec.requirements.filter { it.title in claimsOf }
            val scenarios = spec.requirements.flatMap { r -> r.scenarios.map { r.title to it } }
            val claimed = scenarios.count { it in claimedScenarios }
            val acceptedHere = scenarios.count { it in acceptedScenarios }
            val unclaimed = scenarios.filter { it !in claimedScenarios && it !in acceptedScenarios }
            verifiedTotal += verified.size
            scenarioTotal += scenarios.size
            claimedTotal += claimed
            acceptedTotal += acceptedHere
            table.append(
                "| ${spec.name} | ${verified.size} | ${spec.requirements.size} | $claimed | $acceptedHere | " +
                    "${unclaimed.size} | ${scenarios.size} |\n",
            )
            if (unclaimed.isNotEmpty()) {
                details.append("\n## ${spec.name} — unclaimed scenarios\n\n")
                unclaimed.groupBy({ it.first }, { it.second }).forEach { (requirement, titles) ->
                    details.append("- $requirement\n")
                    titles.forEach { details.append("  - $it\n") }
                }
            }
            if (acceptedHere > 0) {
                details.append("\n## ${spec.name} — accepted unclaimed\n\n")
                acceptedScenarios.values.forEach { details.append("- ${it.scenario} — ${it.kind}: ${it.reason}\n") }
            }
            if (verified.isNotEmpty()) details.append("\n## ${spec.name} — verified requirements\n\n")
            verified.forEach { requirement ->
                val by = claimsOf.getValue(requirement.title).joinToString(", ") { claim ->
                    val name = claim.path.substringAfterLast('/').removeSuffix(".kt")
                    val via = if (claim.scenario.isEmpty()) "" else " (via scenario \"${claim.scenario}\")"
                    "$name.${claim.target.trim('`')} (${claim.sourceSet})$via"
                }
                details.append("- ${requirement.title} ← $by\n")
            }
        }
        val total = specs.sumOf { it.requirements.size }
        val unclaimedTotal = scenarioTotal - claimedTotal - acceptedTotal
        return "# Requirements and scenarios verified by a test (generated — do not commit)\n\n" +
            "| spec | requirements verified | requirements | scenarios claimed | accepted unclaimed | unclaimed | " +
            "scenarios |\n|---|---:|---:|---:|---:|---:|---:|\n" + table +
            "| **total** | **$verifiedTotal** | **$total** | **$claimedTotal** | **$acceptedTotal** | " +
            "**$unclaimedTotal** | **$scenarioTotal** |\n\n" +
            "A requirement-level claim verifies its requirement but claims none of its scenarios.\n" +
            details
    }
}
