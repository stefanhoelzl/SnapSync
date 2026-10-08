package app.snapsync.model

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [bestEffort] runs a step whose failure must not stop the work around it: it answers whether the step ran
 * through, says at `Warn` which step failed, and never swallows a cancellation — that is the caller's scope
 * ending, not the step failing.
 */
class BestEffortTest {

    @Test
    fun `a step that runs through answers true and says nothing`() {
        val captured = CapturingLogWriter()
        var ran = false

        assertTrue(captured.logger().bestEffort("drop staged") { ran = true })

        assertTrue(ran)
        assertEquals(emptyList(), captured.lines)
    }

    @Test
    fun `a step that throws answers false and names itself at Warn`() {
        val captured = CapturingLogWriter()

        assertFalse(captured.logger().bestEffort("drop staged") { error("disk full") })

        assertEquals(listOf(Severity.Warn to "best-effort step failed: drop staged"), captured.lines)
    }

    @Test
    fun `below Warn the failure is still answered, only unsaid`() {
        val quiet = CapturingLogWriter()
        val logger = Logger(StaticConfig(minSeverity = Severity.Error, logWriterList = listOf(quiet)), "test")

        assertFalse(logger.bestEffort("drop staged") { error("disk full") })

        assertEquals(emptyList(), quiet.lines)
    }

    @Test
    fun `a cancellation is not a failed step and propagates`() {
        assertFailsWith<CancellationException> {
            CapturingLogWriter().logger().bestEffort("drop staged") { throw CancellationException("scope ended") }
        }
    }
}
