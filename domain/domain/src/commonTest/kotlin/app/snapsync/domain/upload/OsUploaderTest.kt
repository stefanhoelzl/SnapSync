package app.snapsync.domain.upload

import app.snapsync.domain.sharing.PhotoAccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** Whether the upload extension may be registered, and whether the OS confirms it is. */
class OsUploaderTest {

    @Test
    fun `never registrable below 26_1 whatever the grant or switch`() {
        for (access in PhotoAccess.entries) for (switch in ExtensionSwitch.entries) {
            assertEquals(
                OsUploaderAdmission.NOT_REGISTRABLE,
                OsUploaderAdmission.of(OsSupport.NOT_CARRIED, access, switch),
                "$access / $switch",
            )
        }
    }

    @Test
    fun `registrable exactly under a full grant with the switch on`() {
        for (access in PhotoAccess.entries) {
            val expected = if (access == PhotoAccess.FULL) OsUploaderAdmission.REGISTRABLE else OsUploaderAdmission.NOT_REGISTRABLE
            assertEquals(expected, OsUploaderAdmission.of(OsSupport.CARRIED, access, ExtensionSwitch.ON), "$access")
        }
        assertEquals(
            OsUploaderAdmission.NOT_REGISTRABLE,
            OsUploaderAdmission.of(OsSupport.CARRIED, PhotoAccess.FULL, ExtensionSwitch.OFF),
        )
    }

    @Test
    fun `each uploader has its own switch, on or off`() {
        assertEquals(listOf(AppUploaderSwitch.ON, AppUploaderSwitch.OFF), AppUploaderSwitch.entries)
        assertEquals(listOf(ExtensionSwitch.ON, ExtensionSwitch.OFF), ExtensionSwitch.entries)
    }

    @Test
    fun `only a registrable extension the OS reads as registered is confirmed`() {
        assertEquals(
            OsUploaderStanding.CONFIRMED,
            OsUploaderStanding.of(OsUploaderAdmission.REGISTRABLE, OsRegistration.REGISTERED),
        )
        for (answer in listOf(OsRegistration.NOT_REGISTERED, OsRegistration.UNSUPPORTED, OsRegistration.NOT_ASKED)) {
            assertEquals(
                OsUploaderStanding.NOT_CONFIRMED,
                OsUploaderStanding.of(OsUploaderAdmission.REGISTRABLE, answer),
                "$answer",
            )
        }
        assertEquals(
            OsUploaderStanding.NOT_CONFIRMED,
            OsUploaderStanding.of(OsUploaderAdmission.NOT_REGISTRABLE, OsRegistration.REGISTERED),
        )
    }
}
