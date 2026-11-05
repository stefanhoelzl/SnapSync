package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every entry of [AcceptedUnclaimed] names a scenario that exists, that no test claims, and says why.**
 *
 * The list is only worth something while it is true. An OpenSpec change that renames or retires a listed scenario
 * fails here, as a claim on it fails [VerifiesGateTest], and a scenario that gains a claim leaves the list: it is
 * then work done, not work declined. The kind is an enum, so an unknown kind does not compile.
 */
class AcceptedUnclaimedTest {

    private val specs = Traceability.specs().associateBy { it.name }
    private val claimed = Traceability.claims().filter { it.problem == null && it.scenario.isNotEmpty() }
        .map { Triple(it.spec, it.requirement, it.scenario) }.toSet()

    @Test
    fun `every entry names a scenario that exists`() {
        val broken = AcceptedUnclaimed.entries.mapNotNull { entry ->
            val spec = specs[entry.spec] ?: return@mapNotNull "  ${entry.spec}: no openspec/specs/${entry.spec}/spec.md"
            val requirement = spec.requirement(entry.requirement)
                ?: return@mapNotNull "  ${entry.spec}: no requirement \"${entry.requirement}\""
            if (entry.scenario in requirement.scenarios) null
            else "  ${entry.spec} / \"${entry.requirement}\": no scenario \"${entry.scenario}\""
        }
        if (broken.isNotEmpty()) {
            fail(
                "AcceptedUnclaimed lists what does not exist (${broken.size}):\n" + broken.joinToString("\n") +
                    "\nRenamed or retired by an OpenSpec change? Re-point the entry, or drop it.",
            )
        }
    }

    @Test
    fun `no listed scenario is claimed`() {
        val both = AcceptedUnclaimed.entries.filter { Triple(it.spec, it.requirement, it.scenario) in claimed }
        if (both.isNotEmpty()) {
            fail(
                "a test claims a scenario AcceptedUnclaimed lists (${both.size}) — remove it from the list:\n" +
                    both.joinToString("\n") { "  ${it.spec} / \"${it.requirement}\" / \"${it.scenario}\"" },
            )
        }
    }

    @Test
    fun `every entry says why, once`() {
        val bare = AcceptedUnclaimed.entries.filter { it.reason.isBlank() }
        assertTrue(bare.isEmpty(), "an AcceptedUnclaimed entry gives no reason: ${bare.map { it.scenario }}")
        val twice = AcceptedUnclaimed.entries.groupBy { Triple(it.spec, it.requirement, it.scenario) }
            .filterValues { it.size > 1 }.keys
        assertTrue(twice.isEmpty(), "AcceptedUnclaimed lists a scenario twice: $twice")
    }
}
