package app.snapsync.mock

import app.snapsync.levers.ReceivedEvent
import app.snapsync.levers.WorldEvent
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.ports.CrashHandlers
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
            listen(CrashHandlers(onEvent = { it.copy(tags = it.tags + ("shaped" to "yes")) }, onBreadcrumb = { it }))
            start(CrashOptions("dsn"))
            setContext("process_metrics", mapOf("generation" to "first"))
            setContext("process_metrics", mapOf("generation" to "second"))
            capture(CrashEvent(message = "crossed", contexts = mapOf("own" to mapOf("k" to "v"))))
        }

        val expected = ReceivedEvent(
            message = "crossed",
            tags = mapOf("shaped" to "yes"),
            contexts = mapOf("process_metrics" to mapOf("generation" to "second"), "own" to mapOf("k" to "v")),
        )
        assertEquals(listOf(expected), mock.operator.received())
        assertEquals(listOf<WorldEvent>(WorldEvent.EventReceived(expected)), told)
    }

    @Test
    fun nothing_is_received_before_the_channel_starts_or_when_the_handler_drops_it() = runTest {
        val mock = CrashReporterMock()
        mock.port().apply {
            listen(CrashHandlers(onEvent = { e -> e.takeIf { it.message != "dropped" } }, onBreadcrumb = { it }))
            setContext("early", mapOf("k" to "v"))
            capture(CrashEvent(message = "before start"))
            start(CrashOptions("dsn"))
            capture(CrashEvent(message = "dropped"))
            capture(CrashEvent(message = "kept"))
        }

        val received = mock.operator.received()
        assertEquals(listOf("kept"), received.map { it.message })
        assertTrue(received.single().contexts.isEmpty(), "a context set before the start stands nowhere")
    }
}
