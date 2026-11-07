package app.snapsync.mock

import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashLevel
import app.snapsync.domain.crash.CrashOptions
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.CrashTags
import app.snapsync.domain.crash.Crumb
import app.snapsync.levers.ReceivedEvent
import app.snapsync.levers.ReceivedException
import app.snapsync.levers.WorldEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the crash reporter's operator reads of the automatic events its backend received, and what the world is told. */
class CrashReporterMockTest {

    @Test
    fun an_automatic_event_is_received_with_the_contexts_standing_when_it_left() = runTest {
        val mock = CrashReporterMock()
        val told = mutableListOf<WorldEvent>()
        mock.watch { told += it }
        mock.port().apply {
            start(CrashOptions("dsn"), CrashShaping.STANDARD)
            setContext("process_metrics", mapOf("generation" to "first"))
            setContext("process_metrics", mapOf("generation" to "second"))
            capture(
                CrashEvent(
                    message = "crossed",
                    throwable = IllegalStateException("outer", IllegalArgumentException("inner")),
                    tags = mapOf(CrashTags.FINGERPRINT to "metrics: crossed", "kept" to "1"),
                    contexts = mapOf("own" to mapOf("k" to "v")),
                ),
            )
        }

        val expected = ReceivedEvent(
            message = "crossed",
            tags = mapOf("kept" to "1"),
            contexts = mapOf("process_metrics" to mapOf("generation" to "second"), "own" to mapOf("k" to "v")),
            exceptions = listOf(
                ReceivedException("IllegalStateException", "outer"),
                ReceivedException("IllegalArgumentException", "inner"),
            ),
            fingerprint = listOf("metrics: crossed"),
        )
        assertEquals(listOf(expected), mock.operator.received())
        assertEquals(listOf<WorldEvent>(WorldEvent.EventReceived(expected)), told)
    }

    @Test
    fun nothing_is_received_before_the_channel_starts() = runTest {
        val mock = CrashReporterMock()
        mock.port().apply {
            setContext("early", mapOf("k" to "v"))
            capture(CrashEvent(message = "before start"))
            breadcrumb(Crumb(CrashLevel.INFO, "a breadcrumb transmits nothing"))
            start(CrashOptions("dsn"), CrashShaping.STANDARD)
            capture(CrashEvent(message = "kept"))
        }

        val received = mock.operator.received()
        assertEquals(listOf("kept"), received.map { it.message })
        assertTrue(received.single().contexts.isEmpty(), "a context set before the start stands nowhere")
    }
}
