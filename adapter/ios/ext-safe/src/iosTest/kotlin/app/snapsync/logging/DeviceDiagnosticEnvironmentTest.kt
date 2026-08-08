package app.snapsync.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The state section of a diagnostic dump (capability `diagnostic-logging`).
 *
 * Every field here is context an operator reads a crash *against*, and a field that quietly reports
 * the wrong thing is worse than one that is missing — it sends the reader to a build, an OS or a
 * backend that was never involved. The two worth asserting on this target are the ones that cannot be
 * checked anywhere else:
 *
 * - **the device model comes from `uname`**, not the UI framework. That is not a preference: this
 *   module is linked by the upload extension, where the extension-safety gate forbids the app-only UI
 *   framework outright — so naming it here would fail the build. `uname` also gives the answer worth
 *   having (`iPhone12,8`) where the UI framework would only ever say "iPhone".
 * - **the upload tier is passed through verbatim.** Two tiers ship (OS-driven PhotoKit on iOS ≥26.1,
 *   app-driven `URLSession` below it) and they fail in completely different ways; a dump that named
 *   the wrong one would be read against the wrong half of the system.
 */
class DeviceDiagnosticEnvironmentTest {

    @Test
    fun `the upload tier is reported exactly as the composition named it`() {
        assertEquals("photokit", deviceDiagnosticEnvironment("photokit").uploadTier)
        assertEquals("urlsession", deviceDiagnosticEnvironment("urlsession").uploadTier)
    }

    @Test
    fun `the device model is read from uname and is never a guess`() {
        val model = deviceDiagnosticEnvironment("photokit").deviceModel

        assertTrue(model.isNotBlank(), "a blank model is the one answer the fallback exists to prevent")
        assertTrue(model != "?", "uname is available on every Apple platform; a ? here means the call failed")
    }

    @Test
    fun `the OS version is read from the running system`() {
        assertTrue(deviceDiagnosticEnvironment("photokit").osVersion.isNotBlank())
    }

    /**
     * The upload base is the *baked* one — a compile-time value, because PhotoKit validates every job's
     * destination against the extension's baked host. A test binary carries no such key, and blank is
     * the documented answer for that: `buildUploadConfig` treats blank exactly as absent, so a
     * misconfigured build uploads nowhere rather than somewhere unintended.
     */
    @Test
    fun `an unconfigured upload base is blank rather than a guess`() {
        assertEquals("", deviceDiagnosticEnvironment("photokit").uploadBase)
    }

    @Test
    fun `absent bundle keys become question marks rather than nulls`() {
        val environment = deviceDiagnosticEnvironment("photokit")

        assertTrue("null" !in environment.appVersion, "unexpected: ${environment.appVersion}")
        assertTrue("null" !in environment.buildNumber, "unexpected: ${environment.buildNumber}")
        assertTrue("null" !in environment.reporterEnvironment, "unexpected: ${environment.reporterEnvironment}")
    }
}
