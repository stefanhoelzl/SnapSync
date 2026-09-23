package app.snapsync.logging

import app.snapsync.model.BREADCRUMB_TEXT_BYTES
import app.snapsync.model.EVENT_TEXT_BYTES
import app.snapsync.model.NON_REDACTED_TAG
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.Message
import io.sentry.kotlin.multiplatform.protocol.SentryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scrub functions apply the event-size caps (capability `crash-reporting`: "Every outgoing event is bounded below
 * the ingest's maximum event size"). What the caps sum to on the wire is the `DiagnosticsReporter` contract's
 * worst-case clause; this pins where each cap is applied and what it leaves alone.
 */
class EventBoundsScrubTest {

    private val String.bytes get() = encodeToByteArray().size

    @Test
    fun an_over_long_breadcrumb_message_is_capped_and_marked() {
        val crumb = scrubbedBreadcrumb(Breadcrumb(level = SentryLevel.INFO, message = "x".repeat(5_000)))
        val message = crumb.message.orEmpty()
        assertTrue(message.bytes <= BREADCRUMB_TEXT_BYTES, "got ${message.bytes}")
        assertTrue(message.contains("…[+"), "a cut crumb must say so: $message")
    }

    @Test
    fun a_breadcrumbs_message_and_data_share_one_cap_message_first() {
        // SDK auto-breadcrumbs carry several data keys: a per-field cap would leave the total open.
        val crumb = Breadcrumb(level = SentryLevel.INFO, message = "m".repeat(300), category = "http")
        crumb.setData("url", "u".repeat(900))
        crumb.setData("method", "GET")
        crumb.setData("status_code", 200)
        val scrubbed = scrubbedBreadcrumb(crumb)
        val data = scrubbed.getData().orEmpty()
        val texts = listOf(scrubbed.message.orEmpty()) + data.values.filterIsInstance<String>()
        assertEquals("m".repeat(300), scrubbed.message, "the message is kept whole while it fits")
        assertTrue(texts.sumOf { it.bytes } <= BREADCRUMB_TEXT_BYTES, "total ${texts.sumOf { it.bytes }}")
        assertEquals(200, data["status_code"], "a non-string value is left alone")
    }

    @Test
    fun a_short_breadcrumb_is_untouched_apart_from_redaction() {
        val crumb = scrubbedBreadcrumb(
            Breadcrumb(level = SentryLevel.INFO, message = "join 550e8400-e29b-41d4-a716-446655440000 ok"),
        )
        assertEquals("join ‹uuid› ok", crumb.message)
    }

    @Test
    fun an_automatic_events_message_and_exception_values_are_capped() {
        val event = SentryEvent()
        event.message = Message(message = "e".repeat(20_000), formatted = "f".repeat(20_000))
        event.exceptions = mutableListOf(SentryException(type = "IllegalStateException", value = "v".repeat(20_000)))
        val scrubbed = scrubbedEvent(event)
        listOf(scrubbed.message?.message, scrubbed.message?.formatted, scrubbed.exceptions.single().value)
            .forEach { text ->
                val t = text.orEmpty()
                assertTrue(t.bytes <= EVENT_TEXT_BYTES && t.contains("…[+"), "got ${t.bytes} bytes")
            }
    }

    @Test
    fun an_exempt_event_is_not_capped() {
        // The dump is bounded upstream by its sections' budgets; the scrub leaves an exempt event whole.
        val event = SentryEvent()
        event.setTag(NON_REDACTED_TAG, "1")
        event.message = Message(message = "e".repeat(20_000))
        assertEquals("e".repeat(20_000), scrubbedEvent(event).message?.message)
    }
}
