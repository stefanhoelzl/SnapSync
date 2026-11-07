package app.snapsync.domain.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What a log line becomes on the channel: always a breadcrumb, an event at Error — titled by what happened. */
class LineReportTest {

    private val id = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `a line below error is a breadcrumb only, the whole line with its trigger`() {
        val line = LineReport.of(CrashLevel.WARNING, "download", "retrying", listOf("event" to id), null, "OnPush")
        assertEquals(Crumb(CrashLevel.WARNING, "[OnPush] retrying event=$id", "download"), line.crumb)
        assertNull(line.event)
    }

    @Test
    fun `a line with no trigger is its text alone`() {
        assertEquals("retrying", LineReport.of(CrashLevel.INFO, "t", "retrying", emptyList(), null, null).crumb.message)
    }

    @Test
    fun `an error is an event titled by its event, its fields and trigger in its own section`() {
        val event = LineReport.of(
            CrashLevel.ERROR,
            "Importing",
            "could not import",
            listOf("asset" to id),
            null,
            "OnPush",
        )
            .event!!
        assertEquals("could not import", event.message, "no value in the title")
        assertEquals(mapOf("asset" to id, "trigger" to "OnPush"), event.contexts[CrashSections.LINE])
        assertEquals("OnPush", event.tags[CrashTags.ENTRY_POINT])
        assertEquals("Importing: could not import", event.tags[CrashTags.FINGERPRINT])
        assertNull(event.throwable)
    }

    @Test
    fun `an error with a cause is a reported failure titled the same, the cause beneath it`() {
        val cause = IllegalStateException("database version 15 newer than 13")
        val event = LineReport.of(CrashLevel.ERROR, "engine", "run failed", emptyList(), cause, null).event!!
        val failure = assertIs<ReportedFailure>(event.throwable)
        assertEquals("run failed", failure.message)
        assertSame(cause, failure.cause)
        assertNull(event.message)
        assertEquals(emptyMap(), event.contexts, "no fields and no trigger: no section")
        assertNull(event.tags[CrashTags.ENTRY_POINT])
    }
}
