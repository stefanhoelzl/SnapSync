package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every `@Verifies` names a spec, a requirement and (if given) a scenario that exist**
 * (`docs/architecture.md`, "Tests verify requirements").
 *
 * A test's `@Verifies(spec, requirement[, scenario])` is the only link from the contract of record to the
 * code. It is worth something only while it points at a heading that is still there. Requirements have no
 * ids, so a claim is keyed on the spec's directory name and the requirement's TITLE. When an OpenSpec change
 * renames or retires a requirement, this gate fails on purpose and names every test that claimed it, so
 * someone decides what those tests verify now.
 *
 * Scope is derived: every test source set in the repository (see [Traceability.testSourceSet]), so the
 * journeys — which run only in CI — are read like any other test.
 */
class VerifiesGateTest {

    private val specs = Traceability.specs().associateBy { it.name }
    private val claims = Traceability.claims()

    @Test
    fun `the claim scan is not vacuous`() {
        assertTrue(
            claims.isNotEmpty(),
            "no `@Verifies` found in any test source set — the annotation moved, or it is written in a shape " +
                "this gate cannot see. A gate that reads nothing fails open.",
        )
    }

    @Test
    fun `every claim names a requirement that exists`() {
        val broken = claims.mapNotNull { claim ->
            problemOf(claim)?.let { "  ${claim.path}:${claim.line} (${claim.target})\n$it" }
        }
        if (broken.isEmpty()) return
        fail(
            "@Verifies names something that does not exist (${broken.size}):\n" + broken.joinToString("\n") +
                "\nA requirement renamed or retired by an OpenSpec change? Re-point every test that claimed it.",
        )
    }

    private fun problemOf(claim: Traceability.Claim): String? {
        claim.problem?.let { return "    unreadable: $it" }
        val spec = specs[claim.spec]
            ?: return "    spec        ${claim.spec} — no openspec/specs/${claim.spec}/spec.md\n" +
                "  Specs are:\n" + specs.keys.joinToString("\n") { "    - $it" }
        val requirement = spec.requirement(claim.requirement)
            ?: return "    spec        ${claim.spec}   (openspec/specs/${claim.spec}/spec.md)\n" +
                "    requirement \"${claim.requirement}\"\n" +
                "  Requirements of ${claim.spec} are:\n" + spec.requirements.joinToString("\n") { "    - ${it.title}" }
        if (claim.scenario.isEmpty() || claim.scenario in requirement.scenarios) return null
        return "    spec        ${claim.spec}\n" +
            "    requirement \"${claim.requirement}\"\n" +
            "    scenario    \"${claim.scenario}\"\n" +
            "  Its scenarios are:\n" + requirement.scenarios.joinToString("\n") { "    - $it" }
    }
}
