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
 * without its event, asset and device ids, and its message carries what the operator wrote, quoted
 * ids and all — while automatic events and breadcrumbs, sent without anyone's knowledge, stay
 * redacted.
 *
 * The exemption is carried **by the event**: `send` sets the `non-redacted` tag, and `scrubbedEvent`
 * consults the `redactsMessages` predicate before touching anything. Those are two halves of one
 * wire, and losing either one degrades every future dump **silently** — the report still sends, the
 * request still succeeds, and the payload arrives with `‹uuid›` where its identifiers were. Nobody
 * finds out until they read a report and it cannot answer which photo, which event, or which device.
 * This guard makes losing a half fail loudly instead.
 *
 * It replaces an earlier guard that pinned "the scrub never reaches context sections". That property
 * was load-bearing while the exemption rested on *where the payload sat*; the tag retires it, because
 * an exempt event is skipped whatever the scrub covers. The hazard did not disappear — it moved, and
 * so did the guard.
 *
 * Why a source guard and not a unit test: `scrubbedEvent` lives in an `iosMain` source set, whose
 * tests run only on macOS CI. The predicate's *logic* is unit-tested in `commonTest` (`RedactionTest`,
 * on JVM and simulator); what cannot be reached from Linux is the *wiring*, and a Konsist scan catches
 * that in the canonical `./gradlew build` the moment it is written.
 */
class DumpScrubExemptionTest {

    private val scrubFile = "SentryDiagnosticsReporter.kt"

    private fun scrubSource(): String = Konsist
        .scopeFromProject()
        .files
        .filterNot { it.path.contains("/build/") }
        .firstOrNull { it.path.endsWith("/$scrubFile") }
        ?.text
        ?: fail("$scrubFile not found — it moved; re-point this guard rather than deleting it")

    private fun bodyOf(text: String, signature: String, endsBefore: String): String {
        val body = text.substringAfter(signature, missingDelimiterValue = "").substringBefore(endsBefore)
        assertTrue(
            body.isNotEmpty(),
            "`$signature` has been renamed or removed — re-point this guard rather than deleting it",
        )
        return body
    }

    @Test
    fun `the dump send declares the event exempt from redaction`() {
        val sendBody = bodyOf(scrubSource(), "override fun send(", endsBefore = "override fun start(")

        assertTrue(
            "NON_REDACTED_TAG" in sendBody,
            "the dump send no longer sets the exemption tag (capability `diagnostic-logging`). Without " +
                "it `scrubbedEvent` redacts the report like any other event: the operator's description " +
                "and every id in the payload arrive as `‹uuid›`, the send still succeeds, and nothing " +
                "anywhere reports a problem. If the exemption is being revoked, change the spec first.",
        )
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

    @Test
    fun `the scrub consults the exemption before redacting anything`() {
        val scrubBody = bodyOf(scrubSource(), "internal fun scrubbedEvent(", endsBefore = "\n}")

        assertTrue(
            "redactsMessages" in scrubBody,
            "`scrubbedEvent` no longer consults the exemption predicate (capability `crash-reporting`). " +
                "Every operator-initiated dump is now redacted along with everything else — silently, " +
                "since the event still sends and the failure is visible only to whoever later reads a " +
                "report full of `‹uuid›`. Re-point this guard only if the exemption moved; do not delete it.",
        )
        assertTrue(
            scrubBody.indexOf("redactsMessages") < scrubBody.indexOf("redactUuids"),
            "`scrubbedEvent` redacts before it checks the exemption. The check must come first, or an " +
                "exempt event has already been mangled by the time it is recognised.",
        )
    }
}
