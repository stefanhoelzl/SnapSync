package app.snapsync.model

import co.touchlab.kermit.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contained steps a composition hands its best-effort work to (`docs/architecture.md`, "Absence is never silent";
 * law "Catch sites keep cancellation"): a failure is logged and swallowed, a success logs nothing, and a cancellation
 * is never mistaken for a failure.
 */
class ContainedStepTest {

    @Test
    fun `a contained step that succeeds runs and logs nothing`() = runTest {
        val captured = CapturingLogWriter()
        var ran = false
        captured.logger().contained("it failed") { ran = true }
        assertTrue(ran)
        assertEquals(emptyList(), captured.lines)
    }

    @Test
    fun `a contained step that fails is logged at Warn and swallowed`() = runTest {
        val captured = CapturingLogWriter()
        captured.logger().contained("the foreground flow failed") { error("boom") }
        assertEquals(listOf(Severity.Warn to "the foreground flow failed"), captured.lines)
    }

    @Test
    fun `a contained step rethrows cancellation`() = runTest {
        val captured = CapturingLogWriter()
        assertFailsWith<CancellationException> {
            captured.logger().contained("never logged") { throw CancellationException("gave up") }
        }
        assertEquals(emptyList(), captured.lines)
    }

    @Test
    fun `orNullLogged answers the value or null with the failure logged`() = runTest {
        val captured = CapturingLogWriter()
        val log = captured.logger()
        assertEquals("token", log.orNullLogged("unreadable") { "token" })
        assertNull(log.orNullLogged<String>("unreadable") { error("locked") })
        assertEquals(listOf(Severity.Warn to "unreadable"), captured.lines)
    }

    @Test
    fun `a refused hand-off is recorded at Error and an accepted one is not`() {
        val captured = CapturingLogWriter()
        val log = captured.logger()
        assertEquals(Handoff.Accepted, Handoff.Accepted.recordingRefusal(log, "tap.share"))
        val refused = Handoff.Refused("no window")
        assertEquals(refused, refused.recordingRefusal(log, "tap.openLink"))
        assertEquals(
            listOf(Severity.Error to "tap.openLink: nothing was handed off — no window"),
            captured.lines,
        )
    }

    @Test
    fun `a platform error is described as the platform said it and an absent one as null`() {
        assertEquals("denied", PlatformError("denied").describe())
        assertEquals("null", (null as PlatformError?).describe())
    }
}
