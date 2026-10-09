package app.snapsync.architecture

import java.io.File

/**
 * **Which requirements a test verifies, and which none does** — `./gradlew :test:architecture:verifiesReport`,
 * written to the path in `args[0]` and its table printed.
 *
 * Non-gating: a requirement without a test is a gap to close, not a defect in what is there. The gate on the
 * claims themselves is [VerifiesGateTest]. Generated, never committed: it is the working map the per-concept
 * coverage reviews start from, and a committed copy would need a freshness gate of its own.
 *
 * A scenario-level claim counts its requirement as verified, marked "(via scenario)". Claims that do not
 * resolve are left out here; the gate names them.
 */
fun main(args: Array<String>) {
    val out = File(args.single())
    val report = VerifiesReport.render(Traceability.specs(), Traceability.claims())
    out.parentFile.mkdirs()
    out.writeText(report)
    println(report.substringBefore("\n## "))
    println("written to $out")
}

internal object VerifiesReport {

    fun render(specs: List<Traceability.Spec>, claims: List<Traceability.Claim>): String {
        val bySpec = claims.filter { it.problem == null }.groupBy { it.spec }
        val table = StringBuilder()
        val details = StringBuilder()
        var verifiedTotal = 0
        for (spec in specs) {
            val claimsOf = bySpec[spec.name].orEmpty().groupBy { it.requirement }
            val verified = spec.requirements.filter { it.title in claimsOf }
            verifiedTotal += verified.size
            val mark = if (spec.name in OUTSIDE_REACH) " ¹" else ""
            table.append("| ${spec.name}$mark | ${verified.size} | ${spec.requirements.size} |\n")
            details.append("\n## ${spec.name} — unverified\n\n")
            spec.requirements.filter { it.title !in claimsOf }.forEach { details.append("- ${it.title}\n") }
            if (verified.isNotEmpty()) details.append("\n## ${spec.name} — verified\n\n")
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
        return "# Requirements verified by a test (generated — do not commit)\n\n" +
            "| spec | verified | requirements |\n|---|---:|---:|\n" + table +
            "| **total** | **$verifiedTotal** | **$total** |\n\n" +
            "¹ served by `api/` and `site/`; verified by their own tests, which `@Verifies` cannot reach.\n" +
            details
    }

    /** Specs whose behaviour is served outside the Kotlin app, so no Kotlin test can carry their claims. */
    private val OUTSIDE_REACH = setOf("event-site", "web-site")
}
