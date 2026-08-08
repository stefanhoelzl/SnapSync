package app.snapsync.config

import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSFileNoSuchFileError
import platform.Foundation.NSFileReadNoPermissionError
import platform.Foundation.NSFileReadNoSuchFileError
import platform.Foundation.NSFileReadUnknownError
import platform.Foundation.NSPOSIXErrorDomain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ⑥ classifier: absence is the not-found error class **only** (capability
 * `event-rejoin-reconciliation`).
 *
 * These assertions used to live in `:domain`'s `commonTest`, where they compared integer literals
 * against integer literals and could not fail — a JVM run has no `NSCocoaErrorDomain` to disagree
 * with. Here they name Apple's own constants, so the suite fails if a value ever moves under us.
 */
class ConfigFileAbsenceTest {

    @Test
    fun `not-found errors are the only absence`() {
        assertTrue(isConfigFileAbsence(NSCocoaErrorDomain, NSFileReadNoSuchFileError))
        assertTrue(isConfigFileAbsence(NSCocoaErrorDomain, NSFileNoSuchFileError)) // the delete path
        assertTrue(isConfigFileAbsence(NSPOSIXErrorDomain, 2L)) // ENOENT — Foundation exposes no constant
    }

    @Test
    fun `the not-found codes are still the values this classifier was written against`() {
        // The pin the commonTest version was really making, now against the real symbols: if Apple
        // renumbers these, the classifier's `else -> false` would start reading a genuinely missing
        // file as unreadable, and the device would defer forever instead of leaving.
        assertTrue(NSFileReadNoSuchFileError == 260L)
        assertTrue(NSFileNoSuchFileError == 4L)
    }

    @Test
    fun `a pre-first-unlock protected read is NOT absence`() {
        // Apple's data-protection contract: a protected file read before first unlock fails
        // permission-class, never not-found — mapping it to absence would turn every locked
        // background wake into a false leave.
        assertFalse(isConfigFileAbsence(NSCocoaErrorDomain, NSFileReadNoPermissionError))
        assertFalse(isConfigFileAbsence(NSPOSIXErrorDomain, 1L)) // EPERM
    }

    @Test
    fun `any unknown error stays on the unreadable side`() {
        assertFalse(isConfigFileAbsence(NSCocoaErrorDomain, NSFileReadUnknownError))
        assertFalse(isConfigFileAbsence("SomeOtherDomain", NSFileReadNoSuchFileError)) // code alone is not enough
        assertFalse(isConfigFileAbsence(null, 2L))
    }
}
