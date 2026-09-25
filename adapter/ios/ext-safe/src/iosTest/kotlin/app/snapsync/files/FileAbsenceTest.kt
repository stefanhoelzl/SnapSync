package app.snapsync.files

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
 * `photo-sharing`).
 *
 * These assertions used to live in `:domain`'s `commonTest`, where they compared integer literals
 * against integer literals and could not fail — a JVM run has no `NSCocoaErrorDomain` to disagree
 * with. Here they name Apple's own constants, so the suite fails if a value ever moves under us.
 */
class FileAbsenceTest {

    @Test
    fun `not-found errors are the only absence`() {
        assertTrue(isFileAbsence(NSCocoaErrorDomain, NSFileReadNoSuchFileError))
        assertTrue(isFileAbsence(NSCocoaErrorDomain, NSFileNoSuchFileError)) // the delete path
        assertTrue(isFileAbsence(NSPOSIXErrorDomain, 2L)) // ENOENT — Foundation exposes no constant
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
        assertFalse(isFileAbsence(NSCocoaErrorDomain, NSFileReadNoPermissionError))
        assertFalse(isFileAbsence(NSPOSIXErrorDomain, 1L)) // EPERM
    }

    @Test
    fun `any unknown error stays on the unreadable side`() {
        assertFalse(isFileAbsence(NSCocoaErrorDomain, NSFileReadUnknownError))
        assertFalse(isFileAbsence("SomeOtherDomain", NSFileReadNoSuchFileError)) // code alone is not enough
        assertFalse(isFileAbsence(null, 2L))
    }

    @Test
    fun `the permission class is denied and denied is never absent`() {
        assertTrue(isFileDenied(NSCocoaErrorDomain, NSFileReadNoPermissionError))
        assertTrue(isFileDenied(NSPOSIXErrorDomain, 1L)) // EPERM
        assertTrue(isFileDenied(NSPOSIXErrorDomain, 13L)) // EACCES
        assertFalse(isFileDenied(NSCocoaErrorDomain, NSFileReadNoSuchFileError))
        assertFalse(isFileDenied(NSCocoaErrorDomain, NSFileReadUnknownError))
    }
}
