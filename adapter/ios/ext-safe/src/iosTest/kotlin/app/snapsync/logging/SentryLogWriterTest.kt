package app.snapsync.logging

import co.touchlab.kermit.Severity
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The capture seam between Kermit and Sentry (capability `crash-reporting`).
 *
 * Two claims are asserted here against the **real SDK**, both of which fail silently in production if
 * they break — the send still succeeds, and the operator's instance simply shows something other than
 * the truth.
 *
 * **What becomes an event.** `Error`/`Assert` are captured; everything below rides as a breadcrumb. A
 * breadcrumb attached to no event is never transmitted anywhere, so a mapping that quietly demoted
 * `Error` would leave the fleet failing and the dashboard empty — the exact silence this capability
 * exists to break.
 *
 * **What leaves the device.** An `eventId` *is* the upload capability for that event, so a UUID in a
 * log message is a credential in a bug tracker. This writer redacts before handing anything to the
 * SDK, which matters because it is the only redaction in play for a message captured this way when
 * the reporter's own `beforeSend` is not the one installed.
 *
 * Nothing leaves the machine: `beforeSend` returns `null`, which drops the event before transport,
 * and the DSN points at an unroutable host. (This is the same arrangement `ScrubExemptionSdkTest`
 * uses, for the same reason — the SDK is a dependency whose behaviour can only be measured, and only
 * on this target.)
 */
class SentryLogWriterTest {

    private val unroutableDsn = "https://cafebabecafebabecafebabecafebabe@127.0.0.1:1/1"

    private val writer = SentryLogWriter()

    // ---- the level mapping ----------------------------------------------------------------------

    @Test
    fun `each severity maps onto the Sentry level of the same meaning`() {
        assertEquals(SentryLevel.DEBUG, Severity.Verbose.toSentryLevel())
        assertEquals(SentryLevel.DEBUG, Severity.Debug.toSentryLevel())
        assertEquals(SentryLevel.INFO, Severity.Info.toSentryLevel())
        assertEquals(SentryLevel.WARNING, Severity.Warn.toSentryLevel())
        assertEquals(SentryLevel.ERROR, Severity.Error.toSentryLevel())
        assertEquals(SentryLevel.ERROR, Severity.Assert.toSentryLevel())
    }

    // ---- what is transmitted --------------------------------------------------------------------

    @Test
    fun `an error becomes an event`() {
        val captured = captureWith { writer.log(Severity.Error, "the cycle aborted", "engine", null) }

        val event = assertNotNull(captured, "an Error that reaches nobody is the fault this capability closes")
        assertTrue(
            "the cycle aborted" in event.text(),
            "the log line that explains the error must travel with it: ${event.text()}",
        )
    }

    @Test
    fun `an assertion becomes an event too`() {
        assertNotNull(captureWith { writer.log(Severity.Assert, "invariant broken", "engine", null) })
    }

    @Test
    fun `a warning stays a breadcrumb and is not transmitted on its own`() {
        assertNull(
            captureWith { writer.log(Severity.Warn, "retrying", "engine", null) },
            "promoting warnings would bury the errors under the routine",
        )
    }

    @Test
    fun `an info line stays a breadcrumb`() {
        assertNull(captureWith { writer.log(Severity.Info, "enumerated 3 resources", "gallery", null) })
    }

    @Test
    fun `a throwable is captured as the exception rather than as its message`() {
        val captured = captureWith {
            writer.log(Severity.Error, "upload failed", "engine", IllegalStateException("boom"))
        }

        val event = assertNotNull(captured)
        assertTrue(
            event.exceptions.isNotEmpty(),
            "a stack trace is the whole reason to report a throwable rather than a line of text",
        )
    }

    // ---- what groups with what ------------------------------------------------------------------

    /**
     * The ambient `[entryPoint]` prefix is context about WHICH TRIGGER was running, not about what went
     * wrong — and the backend groups by message text. Leaving it in the message split one cause into one
     * issue per trigger: a single wrong `Error` in the upload cycle arrived as `SNAPSYNC-27/28/29/30`,
     * one each for `upload.didComplete`, `pump.onUploadCompleted`, `pump.onSessionEvents` and
     * `url-session.onForeground`. So the event carries the bare message and the entry point rides as a
     * tag.
     */
    @Test
    fun `the entry point rides as a tag rather than splitting the message`() {
        val owned = LogContext.enter("url-session.onForeground")
        val captured = try {
            captureWith { writer.log(Severity.Error, "the cycle aborted", "engine", null) }
        } finally {
            LogContext.exit(owned)
        }

        val event = assertNotNull(captured)
        val text = event.text()
        assertTrue("the cycle aborted" in text, "the message must still arrive: $text")
        assertTrue(
            "url-session.onForeground" !in text,
            "the prefix in the message is what grouped one cause as four issues: $text",
        )
        assertEquals(
            "url-session.onForeground", event.tags["entry_point"],
            "and it must remain recoverable — dropping it from the message may not lose it",
        )
    }

    /** Same rule on the exception path: one rule is easier to keep true than two. */
    @Test
    fun `a captured throwable carries the entry point tag too`() {
        val owned = LogContext.enter("process")
        val captured = try {
            captureWith { writer.log(Severity.Error, "process cycle failed", "engine", IllegalStateException("boom")) }
        } finally {
            LogContext.exit(owned)
        }

        assertEquals("process", assertNotNull(captured).tags["entry_point"])
    }

    // ---- what must not leave ---------------------------------------------------------------------

    @Test
    fun `a UUID in an error message is redacted before it reaches the SDK`() {
        val eventId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        val captured = captureWith { writer.log(Severity.Error, "reconcile($eventId) failed", "engine", null) }

        val text = assertNotNull(captured).text()
        assertTrue(
            "reconcile" in text,
            "the message must actually have arrived, or the next assertion passes on an empty string: $text",
        )
        assertTrue(
            eventId !in text,
            "an eventId IS the upload capability for that event — this one reached a bug tracker: $text",
        )
    }

    /**
     * Every field the message could have arrived in. The SDK fills `message` or `formatted` depending
     * on how a capture was made, and reading only one of them is how an assertion about redaction
     * ends up passing against an empty string.
     */
    private fun SentryEvent.text(): String =
        listOfNotNull(message?.message, message?.formatted).joinToString(" | ")

    /**
     * Runs [emit] against a freshly-initialised SDK and returns the event `beforeSend` saw, or `null`
     * when nothing was captured. The negative direction needs a wait too: "no event" and "the event
     * has not arrived yet" are the same observation until the deadline passes.
     */
    private fun captureWith(emit: () -> Unit): SentryEvent? {
        var captured: SentryEvent? = null
        Sentry.init { options ->
            options.dsn = unroutableDsn
            options.beforeSend = { event ->
                captured = event
                null // dropped before transport: this test observes the SDK, it never transmits
            }
        }

        emit()

        val deadline = Clock.System.now() + 5.seconds
        while (Clock.System.now() < deadline) {
            captured?.let { return it }
        }
        return captured
    }
}
