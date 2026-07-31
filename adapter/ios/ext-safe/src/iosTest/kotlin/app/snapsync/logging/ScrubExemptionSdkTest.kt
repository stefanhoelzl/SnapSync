package app.snapsync.logging

import app.snapsync.model.NON_REDACTED_TAG
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * **The SDK half of the dump's redaction exemption** (capability `diagnostic-logging`).
 *
 * `scrubbedEvent` skips redaction when the event carries [NON_REDACTED_TAG], and the dump's `send`
 * sets that tag on the **scope** it captures with. Between those two facts sits an assumption about
 * somebody else's SDK: that a scope tag is applied to the event *before* `beforeSend` runs. Nothing on
 * Linux can check that — and if it is false, every report arrives with its identifiers replaced by
 * `‹uuid›` markers while the send still succeeds, which is the exact silent failure the whole
 * exemption exists to prevent.
 *
 * So it is measured here rather than assumed, on the one platform that can: `iosSimulatorArm64Test`,
 * run by `ios-test` on macOS CI. This is the forcing proof behind the exemption's design decision
 * (`changes/…/design.md`, D2) — if a future SDK upgrade changes the ordering, this fails loudly
 * instead of quietly degrading every future report.
 *
 * Nothing leaves the machine: `beforeSend` returns `null`, which drops the event before transport, and
 * the DSN points at an unroutable host.
 */
class ScrubExemptionSdkTest {

    private val unroutableDsn = "https://cafebabecafebabecafebabecafebabe@127.0.0.1:1/1"

    @Test
    fun a_scope_tag_is_applied_before_beforeSend_runs() {
        var captured: SentryEvent? = null
        Sentry.init { options ->
            options.dsn = unroutableDsn
            // Returning null drops the event: this test observes the SDK, it never transmits.
            options.beforeSend = { event ->
                captured = event
                null
            }
        }

        Sentry.captureMessage("exemption probe") { scope -> scope.setTag(NON_REDACTED_TAG, "1") }

        val event = awaitCaptured { captured }
        assertEquals(
            "1",
            event.tags[NON_REDACTED_TAG],
            "a scope tag did NOT reach beforeSend. The dump's exemption is wired through exactly this " +
                "path, so every operator report would be redacted — silently, since the send still " +
                "succeeds. Switch the exemption to the message-prefix fallback (design D2).",
        )
    }

    @Test
    fun an_untagged_capture_carries_no_exemption() {
        // The other direction: automatic events must NOT come out exempt, or the scrub is off for
        // everything. A tag left behind on a shared scope would do exactly that.
        var captured: SentryEvent? = null
        Sentry.init { options ->
            options.dsn = unroutableDsn
            options.beforeSend = { event ->
                captured = event
                null
            }
        }

        Sentry.captureMessage("ordinary event")

        val event = awaitCaptured { captured }
        assertTrue(
            event.tags[NON_REDACTED_TAG] == null,
            "an ordinary capture arrived carrying the exemption tag — the scrub would be disabled for " +
                "automatically captured events, which are sent without anyone's knowledge",
        )
    }

    /** `beforeSend` may run off the calling thread, so poll rather than assume it already ran. */
    private fun awaitCaptured(read: () -> SentryEvent?): SentryEvent {
        val deadline = Clock.System.now() + 10.seconds
        while (Clock.System.now() < deadline) {
            read()?.let { return it }
        }
        return assertNotNull(read(), "beforeSend never ran within 10s — the capture did not reach it")
    }
}
