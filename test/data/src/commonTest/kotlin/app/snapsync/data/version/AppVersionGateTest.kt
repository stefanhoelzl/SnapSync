package app.snapsync.data.version

import app.snapsync.domain.trust.BackendAnswer
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The read-model that owns "the backend is refusing this build" (capability `app-update-required`). What is worth
 * pinning is not that it stores a value but the rules around it: a refusal that names no version is still a refusal,
 * an answer from past the version check CLEARS it, and the `Error` that reaches crash reporting fires on the TRANSITION rather than on every
 * refused request.
 */
@OptIn(CompositionOnly::class)
class AppVersionGateTest {
    private val lines = mutableListOf<LogLine>()

    @BeforeTest
    fun record() = Log.install(
        listOf(object : LogWriter {
            override fun write(line: LogLine) {
                lines += line
            }
        }),
    )

    @AfterTest
    fun stop() = Log.install(emptyList())

    private fun errors() = lines.count { it.tag == "AppVersionGate" && it.severity >= Severity.Error }

    @Test
    fun a_fresh_gate_is_not_refused() {
        assertNull(AppVersionGate().refusal.value, "not-yet-called and served are the same to every reader")
    }

    @Test
    fun a_refusal_carries_the_version_the_backend_named_and_one_naming_none_is_still_a_refusal() = runTest {
        val gate = AppVersionGate()
        gate.observe(BackendAnswer.TooOld("0.4"))
        assertEquals(VersionRefusal("0.4"), gate.refusal.value)
        // Flattening "no version" into "not refused" would leave a build unable to do anything with no screen saying why.
        gate.observe(BackendAnswer.TooOld(null))
        assertEquals(VersionRefusal(null), gate.refusal.value)
    }

    @Test
    fun a_served_request_clears_the_refusal() = runTest {
        val gate = AppVersionGate()
        gate.observe(BackendAnswer.TooOld("0.4"))
        gate.observe(BackendAnswer.Served)
        assertNull(gate.refusal.value, "this is what heals the screen after the member updates")
    }

    @Test
    fun the_fault_is_reported_on_the_transition_only() = runTest {
        // A device parked on the update screen keeps calling and keeps being refused: one report, not one per request.
        val gate = AppVersionGate()
        repeat(3) { gate.observe(BackendAnswer.TooOld("0.4")) }
        assertEquals(1, errors(), "one report per transition, not one per refused request")
    }

    @Test
    fun a_changed_minimum_is_a_new_transition() = runTest {
        val gate = AppVersionGate()
        gate.observe(BackendAnswer.TooOld("0.4"))
        gate.observe(BackendAnswer.TooOld("0.5")) // the backend raised its floor while this build sat there
        assertEquals(2, errors())
        assertEquals(VersionRefusal("0.5"), gate.refusal.value)
    }

    @Test
    fun clearing_an_already_clear_gate_says_nothing() = runTest {
        val gate = AppVersionGate()
        gate.observe(BackendAnswer.Served)
        gate.observe(BackendAnswer.Served)
        assertEquals(0, lines.size, "every served request would otherwise write a line")
    }

    @Test
    fun clearing_a_refusal_says_so_once() = runTest {
        val gate = AppVersionGate()
        gate.observe(BackendAnswer.TooOld("0.4"))
        gate.observe(BackendAnswer.Served)
        gate.observe(BackendAnswer.Served)
        assertEquals(1, lines.count { it.severity == Severity.Info })
    }

    @Test
    fun a_refusal_of_the_request_clears_the_refusal_too() = runTest {
        // The backend checks the version first: a 401 or a 404 came from past that check, so this build is served.
        for (answer in listOf(BackendAnswer.Rejected, BackendAnswer.Refused)) {
            val gate = AppVersionGate()
            gate.observe(BackendAnswer.TooOld("0.4"))
            gate.observe(answer)
            assertNull(gate.refusal.value, "$answer")
        }
    }

    @Test
    fun a_server_issue_or_no_answer_says_nothing_about_this_build() = runTest {
        val gate = AppVersionGate()
        gate.observe(BackendAnswer.TooOld("0.4"))
        gate.observe(BackendAnswer.ServerIssue)
        gate.observe(BackendAnswer.NoAnswer)
        assertEquals(VersionRefusal("0.4"), gate.refusal.value, "neither need have come from past the version check")
    }
}
