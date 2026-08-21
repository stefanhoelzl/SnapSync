package app.snapsync.model

import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The registration classifier (capability `ios-photokit-upload`).
 *
 * Its whole reason for existing is that the platform call used to discard both its `Boolean` and its
 * `NSError**`, making a failed registration invisible: the extension is never registered, the OS never
 * launches it, and the screen sits at "Synchronization pending…" forever with nothing anywhere saying why.
 *
 * The interesting case is not the failure, though — it is the **expected** failure. `start()` disables
 * before it enables, so on any clean device the leading disable fails with 3201 (measured twice, SE2 /
 * iOS 26.6). Reporting that at `Error` would put a Bugsink event on the first join of every fresh install.
 */
class RegistrationOutcomeTest {

    @Test
    fun `a successful enable is applied`() {
        val outcome = registrationOutcome(enabling = true, ok = true, errorDomain = null, errorCode = null)
        assertIs<RegistrationOutcome.Applied>(outcome)
        assertEquals(Severity.Info, outcome.severity)
    }

    @Test
    fun `a successful disable is evidence a record existed`() {
        val outcome = registrationOutcome(enabling = false, ok = true, errorDomain = null, errorCode = null)
        assertIs<RegistrationOutcome.Applied>(outcome)
        assertTrue(
            outcome.message.contains("existed"),
            "a disable that succeeds is the one reliable way to learn this device WAS registered — the " +
                "read-back is grant-dependent — so the line must say so rather than just 'ok'",
        )
    }

    @Test
    fun `a disable finding no record is expected rather than a failure`() {
        val outcome = registrationOutcome(
            enabling = false,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = PHOTOS_ERROR_IDENTIFIER_NOT_FOUND,
        )
        assertEquals(RegistrationOutcome.NothingToDisable, outcome)
        assertEquals(
            Severity.Debug,
            outcome.severity,
            "3201 on a disable happens on every fresh install; at Error it would bury the real signal",
        )
    }

    @Test
    fun `the same code on an ENABLE is a real failure`() {
        // The asymmetry is the point: 3201 from an enable is not "nothing to do", it is an enable that did
        // not happen — which is precisely the invisible, terminal failure this classifier exists to surface.
        val outcome = registrationOutcome(
            enabling = true,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = PHOTOS_ERROR_IDENTIFIER_NOT_FOUND,
        )
        assertIs<RegistrationOutcome.Failed>(outcome)
        assertEquals(Severity.Error, outcome.severity)
    }

    @Test
    fun `any other failing code is reported with its domain and code`() {
        val outcome = registrationOutcome(
            enabling = false,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = 3202,
        )
        assertIs<RegistrationOutcome.Failed>(outcome)
        assertTrue(outcome.message.contains("PHPhotosErrorDomain:3202"), outcome.message)
    }

    @Test
    fun `a failure the platform did not explain still reports`() {
        val outcome = registrationOutcome(enabling = true, ok = false, errorDomain = null, errorCode = null)
        assertIs<RegistrationOutcome.Failed>(outcome)
        assertEquals(Severity.Error, outcome.severity)
        assertTrue(
            outcome.message.contains("no domain") && outcome.message.contains("no code"),
            "an unexplained failure must still be distinguishable from a success — 'couldn't tell' and " +
                "'nothing wrong' are different answers",
        )
    }
}
