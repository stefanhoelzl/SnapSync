package app.snapsync.services.version

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import app.snapsync.model.Reply
import app.snapsync.model.VersionRefusal

/**
 * The read-model that owns "the backend is refusing this build" (capability `app-update-required`).
 *
 * It is a cell, so what is worth pinning is not that it stores a value but the three rules around it:
 * a refusal that names no version is still a refusal, a served response CLEARS it, and the `Error` that
 * reaches crash reporting fires on the TRANSITION rather than on every refused request.
 */
class AppVersionGateTest {

    private class Recorder : LogWriter() {
        val lines = mutableListOf<Pair<Severity, String>>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
        fun logger() = Logger(loggerConfigInit(this), "AppVersionGateTest")
        fun errors() = lines.count { it.first >= Severity.Error }
    }

    @Test
    fun a_fresh_gate_is_not_refused() {
        assertNull(AppVersionGate().refusal.value, "not-yet-called and served are the same to every reader")
    }

    @Test
    fun a_refusal_carries_the_version_the_backend_named() {
        val gate = AppVersionGate()
        gate.refused("0.4")
        assertEquals(VersionRefusal("0.4"), gate.refusal.value)
    }

    @Test
    fun a_refusal_naming_no_version_is_still_a_refusal() {
        // The distinction that must NOT collapse: the refusal is carried by the status, the version is a
        // courtesy. Flattening "no version" into "not refused" would leave a build unable to do anything
        // with no screen explaining why.
        val gate = AppVersionGate()
        gate.refused(null)
        assertEquals(VersionRefusal(null), gate.refusal.value)
    }

    @Test
    fun a_served_response_clears_the_refusal() {
        val gate = AppVersionGate()
        gate.refused("0.4")
        gate.served()
        assertNull(gate.refusal.value, "this is what heals the screen after the member updates")
    }

    @Test
    fun the_fault_is_reported_on_the_transition_only() {
        // A device parked on the update screen keeps calling and keeps being refused. Reporting each one
        // would file a crash-report event per request for a device that is simply out of date.
        val recorder = Recorder()
        val gate = AppVersionGate(recorder.logger())

        gate.refused("0.4")
        gate.refused("0.4")
        gate.refused("0.4")

        assertEquals(1, recorder.errors(), "one report per transition, not one per refused request")
    }

    @Test
    fun a_changed_minimum_is_a_new_transition() {
        val recorder = Recorder()
        val gate = AppVersionGate(recorder.logger())
        gate.refused("0.4")
        gate.refused("0.5") // the backend raised its floor while this build sat there
        assertEquals(2, recorder.errors())
        assertEquals(VersionRefusal("0.5"), gate.refusal.value)
    }

    @Test
    fun clearing_an_already_clear_gate_says_nothing() {
        val recorder = Recorder()
        val gate = AppVersionGate(recorder.logger())
        gate.served()
        gate.served()
        assertEquals(0, recorder.lines.size, "every served response would otherwise write a line")
    }

    @Test
    fun observing_a_426_refuses_naming_the_minimum_and_a_success_clears_it() {
        val gate = AppVersionGate()
        gate.observe(Reply.Refused(426, """{"error":"app too old","minAppVersion":"0.4"}"""))
        assertEquals(VersionRefusal("0.4"), gate.refusal.value)
        gate.observe(Reply.Refused(426, "too old"))
        assertEquals(VersionRefusal(null), gate.refusal.value, "a refusal naming no version is still a refusal")
        gate.observe(Reply.Ok(Unit))
        assertNull(gate.refusal.value)
    }

    @Test
    fun observing_any_other_answer_says_nothing_about_this_build() {
        val gate = AppVersionGate()
        gate.refused("0.4")
        gate.observe(Reply.Refused(401, "invalid token"))
        gate.observe(Reply.Malformed("x"))
        gate.observe(Reply.Unreachable(IllegalStateException("offline")))
        assertEquals(VersionRefusal("0.4"), gate.refusal.value, "only a success heals the screen")
    }
}
