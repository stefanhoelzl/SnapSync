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
 * The interesting case is not the failure, though — it is the **expected** failure, and there are two of
 * them. `start()` disables before it enables, so on any clean device the leading disable fails with 3201
 * (measured twice, SE2 / iOS 26.6): reporting that at `Error` would put a Bugsink event on the first join
 * of every fresh install. And under a partial photo grant the platform refuses the call outright with 3311
 * (measured, SE2 / iOS 26.6): reporting *that* at `Error` would put one on every member who switches to
 * Limited Access, repeatedly.
 *
 * Both split by direction, and the tests below assert the split rather than just the quiet arm — because
 * the same code arriving from the other direction is the invisible, terminal case this classifier exists
 * to surface.
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
    fun `a disable refused by a partial grant is expected rather than a failure`() {
        val outcome = registrationOutcome(
            enabling = false,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = PHOTOS_ERROR_ACCESS_USER_DENIED,
        )
        assertEquals(RegistrationOutcome.DisableRefusedByGrant, outcome)
        assertTrue(
            outcome.severity < Severity.Error,
            "switching Photos to Limited Access is a supported user action, and the arm attempts this " +
                "disable on every membership-lifecycle action taken under that grant — at Error each one " +
                "becomes a crash report on an ordinary path",
        )
        assertEquals(
            Severity.Warn,
            outcome.severity,
            "Warn, not Debug: a real dump carries thousands of Info/Debug lines and ~19 Warn, so Warn is " +
                "the one band a reader can scan when asking why a limited-grant device is not uploading",
        )
    }

    @Test
    fun `the same code on an ENABLE is terminal and stays loud`() {
        // The asymmetry again, and it matters more here than for 3201: a refused disable leaves an inert
        // record, while a refused enable means no registration is ever created — the OS never launches the
        // extension, no cycle runs, and nothing else reports it.
        val outcome = registrationOutcome(
            enabling = true,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = PHOTOS_ERROR_ACCESS_USER_DENIED,
        )
        assertEquals(RegistrationOutcome.EnableRefusedByGrant, outcome)
        assertEquals(Severity.Error, outcome.severity)
        assertTrue(
            outcome.message.contains("partial photo grant"),
            "reachable only when a development override pins this mechanism under a partial grant, which " +
                "is exactly when the operator needs the cause named rather than a generic failure",
        )
    }

    @Test
    fun `the refused enable is a distinct outcome from a generic failure`() {
        // Guards the collapse this change exists to prevent: folding 3311-on-enable into `Failed` would
        // lose the cause, and folding it into the disable's quiet arm would lose the incident entirely.
        val refused = registrationOutcome(
            enabling = true,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = PHOTOS_ERROR_ACCESS_USER_DENIED,
        )
        val generic = registrationOutcome(
            enabling = true,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = 3202,
        )
        assertIs<RegistrationOutcome.EnableRefusedByGrant>(refused)
        assertIs<RegistrationOutcome.Failed>(generic)
        assertEquals(refused.severity, generic.severity, "both are incidents; only the wording differs")
    }

    @Test
    fun `3311 splits by direction the way 3201 does`() {
        // One code, one call, two directions, two severities — stated as its own assertion so a future
        // simplification that keys on the code alone fails here rather than on a device.
        val disable = registrationOutcome(
            enabling = false,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = PHOTOS_ERROR_ACCESS_USER_DENIED,
        )
        val enable = registrationOutcome(
            enabling = true,
            ok = false,
            errorDomain = "PHPhotosErrorDomain",
            errorCode = PHOTOS_ERROR_ACCESS_USER_DENIED,
        )
        assertEquals(Severity.Warn, disable.severity)
        assertEquals(Severity.Error, enable.severity)
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
