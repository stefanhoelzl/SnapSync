package app.snapsync.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The diagnostic dump's scrub exemption is a decision, not an accident** (capability
 * `architecture-guards`; contracts: `diagnostic-logging`, `crash-reporting`).
 *
 * An operator-initiated dump travels **verbatim** — it is confirmed by the operator and worthless
 * without its event, asset and device ids — while automatic events and breadcrumbs, sent without
 * anyone's knowledge, stay redacted. Today the dump survives only because `scrubbedEvent` reaches
 * message text, exception values and breadcrumbs, and the dump's payload rides in **contexts**.
 *
 * That is a property of where the scrub happens to stop. Widen it to contexts — an entirely
 * reasonable-looking "we missed a field" change — and every future dump arrives with its log emptied
 * to `‹uuid›` markers: no failing test, no error, no signal of any kind, and the failure is invisible
 * until someone reads a dump and finds it useless. This guard makes that change fail loudly instead.
 *
 * Why a source guard and not a unit test: `scrubbedEvent` lives in an `iosMain` source set, whose
 * tests run only on macOS CI. The mistake it guards is a source-shape change, and a Konsist scan
 * catches it on Linux, in the canonical `./gradlew build`, the moment it is written.
 */
class DumpScrubExemptionTest {

    private val scrubFile = "SentryDiagnosticsReporter.kt"

    /** Tokens that would mean the scrub had been widened to reach a dump's payload. */
    private val contextReachingTokens = listOf("contexts", "getContexts", "setContext")

    private fun scrubSource(): String = Konsist
        .scopeFromProject()
        .files
        .filterNot { it.path.contains("/build/") }
        .firstOrNull { it.path.endsWith("/$scrubFile") }
        ?.text
        ?: fail("$scrubFile not found — it moved; re-point this guard rather than deleting it")

    @Test
    fun `the scrub never reaches a dump's context sections`() {
        val text = scrubSource()
        val scrubBody = text.substringAfter("internal fun scrubbedEvent(", missingDelimiterValue = "")
        assertTrue(
            scrubBody.isNotEmpty(),
            "scrubbedEvent has been renamed or removed — re-point this guard rather than deleting it",
        )
        val offenders = contextReachingTokens.filter { it in scrubBody }
        assertTrue(
            offenders.isEmpty(),
            "scrubbedEvent now reaches $offenders. The operator-initiated diagnostic dump carries its " +
                "payload in CONTEXT sections and is deliberately exempt from redaction " +
                "(capability `diagnostic-logging`): scrubbing them empties every future dump silently — " +
                "no failing test, no error, just useless dumps. If the exemption is being revoked, change " +
                "the spec first.",
        )
    }

    @Test
    fun `the dump send is the only context writer, and it does not scrub`() {
        val text = scrubSource()
        val sendBody = text.substringAfter("override fun send(", missingDelimiterValue = "")
            .substringBefore("override fun start(")
        assertTrue(
            "setContext" in sendBody,
            "the dump no longer writes contexts — if the payload moved to another carrier, re-point " +
                "this guard: the exemption it protects moved with it",
        )
        assertTrue(
            "redactUuids" !in sendBody,
            "the dump send now redacts its own payload. That is the exemption, undone at the other end " +
                "(capability `diagnostic-logging`): a dump without ids cannot answer which photo, which " +
                "event, or which device.",
        )
    }
}
