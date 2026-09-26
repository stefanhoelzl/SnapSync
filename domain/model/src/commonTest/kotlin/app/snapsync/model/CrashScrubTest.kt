package app.snapsync.model

import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The channel's rules, as pure functions (capability `privacy-security`): what leaves bounded and redacted, what the
 * dump keeps verbatim, and what a log line becomes. What the caps sum to on the wire is the `CrashReporter`
 * contract's worst-case clause, which runs these same functions over the real SDK.
 */
class CrashScrubTest {

    private val String.bytes get() = encodeToByteArray().size

    private val id = "550e8400-e29b-41d4-a716-446655440000"

    // ---- breadcrumbs -------------------------------------------------------------------------------------------

    @Test
    fun an_over_long_breadcrumb_message_is_capped_and_marked() {
        val message = scrubbedCrumb(Crumb(CrashLevel.INFO, "x".repeat(5_000))).message.orEmpty()
        assertTrue(message.bytes <= BREADCRUMB_TEXT_BYTES, "got ${message.bytes}")
        assertTrue(message.contains("…[+"), "a cut crumb must say so: $message")
    }

    @Test
    fun a_breadcrumbs_message_and_data_share_one_cap_message_first() {
        // SDK auto-breadcrumbs carry several data keys: a per-field cap would leave the total open.
        val crumb = Crumb(CrashLevel.INFO, "m".repeat(300), "http", mapOf("url" to "u".repeat(900), "method" to "GET"))
        val scrubbed = scrubbedCrumb(crumb)
        val texts = listOf(scrubbed.message.orEmpty()) + scrubbed.data.values
        assertEquals("m".repeat(300), scrubbed.message, "the message is kept whole while it fits")
        assertTrue(texts.sumOf { it.bytes } <= BREADCRUMB_TEXT_BYTES, "total ${texts.sumOf { it.bytes }}")
        assertEquals(setOf("url", "method"), scrubbed.data.keys, "no data key is lost to the cap")
    }

    @Test
    fun a_short_breadcrumb_is_untouched_apart_from_redaction() {
        assertEquals("join ‹uuid› ok", scrubbedCrumb(Crumb(CrashLevel.INFO, "join $id ok")).message)
    }

    // ---- automatic events --------------------------------------------------------------------------------------

    @Test
    fun an_automatic_events_message_and_exception_values_are_capped() {
        val event = CrashEvent(
            message = "e".repeat(20_000),
            formatted = "f".repeat(20_000),
            exceptionValues = listOf("v".repeat(20_000)),
        )
        val scrubbed = scrubbedEvent(event)
        listOf(scrubbed.message, scrubbed.formatted, scrubbed.exceptionValues.single()).forEach { text ->
            val t = text.orEmpty()
            assertTrue(t.bytes <= EVENT_TEXT_BYTES && t.contains("…[+"), "got ${t.bytes} bytes")
        }
    }

    @Test
    fun an_automatic_event_is_redacted_everywhere_it_can_carry_text() {
        val scrubbed = scrubbedEvent(
            CrashEvent(
                message = "reconcile($id)",
                formatted = "reconcile($id)",
                params = listOf(id),
                exceptionValues = listOf("no row $id", null),
                breadcrumbs = listOf(Crumb(CrashLevel.INFO, "enumerating $id")),
            ),
        )
        val texts = listOfNotNull(scrubbed.message, scrubbed.formatted) + scrubbed.params.orEmpty() +
            scrubbed.exceptionValues.filterNotNull() + scrubbed.breadcrumbs.mapNotNull { it.message }
        assertTrue(texts.none { id in it }, "an eventId IS the upload capability: $texts")
        assertNull(scrubbed.exceptionValues[1], "an absent exception value stays absent, in its place")
    }

    // ---- the dump's exemption: both halves of one wire ---------------------------------------------------------

    @Test
    fun the_dump_declares_itself_exempt_and_carries_its_sections_as_contexts() {
        val dump = DiagnosticDump("stuck on $id", mapOf("screen" to "Joined"), mapOf("pending" to "1"), "app\n", "ext\n")
        val event = diagnosticDumpEvent(dump)
        assertEquals("1", event.tags[NON_REDACTED_TAG], "without the tag every report is redacted, silently")
        assertEquals("$DIAGNOSTIC_DUMP_MESSAGE_PREFIX stuck on $id", event.message)
        assertEquals(
            mapOf(
                "note" to mapOf("text" to "stuck on $id"),
                "state" to mapOf("screen" to "Joined"),
                "ledger" to mapOf("pending" to "1"),
                "app_log" to mapOf("text" to "app\n"),
                "ext_log" to mapOf("text" to "ext\n"),
            ),
            event.contexts,
        )
    }

    @Test
    fun the_scrub_leaves_an_exempt_event_whole() {
        val dump = diagnosticDumpEvent(DiagnosticDump("stuck on $id " + "e".repeat(20_000), emptyMap(), emptyMap(), "", ""))
        val scrubbed = scrubbedEvent(dump.copy(breadcrumbs = listOf(Crumb(CrashLevel.INFO, "x".repeat(5_000)))))
        assertEquals(dump.message, scrubbed.message, "the operator's note — and the id it quotes — arrives whole")
        assertEquals("x".repeat(5_000), scrubbed.breadcrumbs.single().message)
    }

    // ---- what a log line becomes -------------------------------------------------------------------------------

    @Test
    fun each_severity_maps_onto_the_level_of_the_same_meaning() {
        assertEquals(CrashLevel.DEBUG, Severity.Verbose.crashLevel())
        assertEquals(CrashLevel.DEBUG, Severity.Debug.crashLevel())
        assertEquals(CrashLevel.INFO, Severity.Info.crashLevel())
        assertEquals(CrashLevel.WARNING, Severity.Warn.crashLevel())
        assertEquals(CrashLevel.ERROR, Severity.Error.crashLevel())
        assertEquals(CrashLevel.ERROR, Severity.Assert.crashLevel())
    }

    @Test
    fun an_error_and_an_assertion_become_events() {
        assertNotNull(loggedCrash(Severity.Error, "the cycle aborted", "engine", null, null).event)
        assertNotNull(loggedCrash(Severity.Assert, "invariant broken", "engine", null, null).event)
    }

    @Test
    fun a_warning_and_below_stay_breadcrumbs() {
        listOf(Severity.Warn, Severity.Info, Severity.Debug, Severity.Verbose).forEach { severity ->
            assertNull(loggedCrash(severity, "retrying", "engine", null, null).event, "$severity promoted")
        }
    }

    @Test
    fun a_throwable_is_captured_as_the_exception_rather_than_as_its_message() {
        val boom = IllegalStateException("boom")
        val event = assertNotNull(loggedCrash(Severity.Error, "upload failed", "engine", boom, null).event)
        assertEquals(boom, event.throwable)
        assertNull(event.message)
    }

    @Test
    fun the_entry_point_rides_as_a_tag_and_prefixes_only_the_breadcrumb() {
        val logged = loggedCrash(Severity.Error, "the cycle aborted", "engine", null, "url-session.onForeground")
        val event = assertNotNull(logged.event)
        assertEquals("the cycle aborted", event.message, "the prefix in the message grouped one cause as four issues")
        assertEquals("url-session.onForeground", event.tags["entry_point"])
        assertEquals("[url-session.onForeground] the cycle aborted", logged.crumb.message)
        assertEquals(CrashLevel.ERROR, logged.crumb.level)
    }

    @Test
    fun a_captured_throwable_carries_the_entry_point_tag_too() {
        val event = loggedCrash(Severity.Error, "failed", "engine", IllegalStateException("boom"), "process").event
        assertEquals("process", assertNotNull(event).tags["entry_point"])
    }

    @Test
    fun a_logged_uuid_is_redacted_before_it_reaches_the_channel() {
        val logged = loggedCrash(Severity.Error, "reconcile($id) failed", "engine", null, "process")
        assertFalse(id in logged.event?.message.orEmpty())
        assertFalse(id in logged.crumb.message.orEmpty())
        assertTrue("reconcile" in logged.event?.message.orEmpty(), "and the message did arrive")
    }
}
