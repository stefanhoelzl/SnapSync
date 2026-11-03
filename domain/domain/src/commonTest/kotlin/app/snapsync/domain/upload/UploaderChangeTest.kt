package app.snapsync.domain.upload

import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.upload.OsUploaderAdmission.NOT_REGISTRABLE
import app.snapsync.domain.upload.OsUploaderAdmission.REGISTRABLE
import app.snapsync.domain.upload.OsUploaderStanding.CONFIRMED
import app.snapsync.domain.upload.OsUploaderStanding.NOT_CONFIRMED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploaderChangeTest {

    @Test
    fun `a join forces the registration wherever the extension is registrable`() {
        assertEquals(Registration.REGISTER, UploaderChange.JOIN.registration(REGISTRABLE, CONFIRMED))
        assertEquals(Registration.LEAVE, UploaderChange.JOIN.registration(NOT_REGISTRABLE, NOT_CONFIRMED))
    }

    @Test
    fun `a launch or grant change registers only a record the OS reads absent, a reconfigure never`() {
        listOf(UploaderChange.LAUNCH, UploaderChange.GRANT_CHANGED).forEach { change ->
            assertEquals(Registration.REGISTER, change.registration(REGISTRABLE, NOT_CONFIRMED))
            assertEquals(Registration.LEAVE, change.registration(REGISTRABLE, CONFIRMED))
            assertEquals(Registration.LEAVE, change.registration(NOT_REGISTRABLE, NOT_CONFIRMED))
        }
        assertEquals(Registration.LEAVE, UploaderChange.RECONFIGURE.registration(REGISTRABLE, NOT_CONFIRMED))
    }

    @Test
    fun `every change but a join needs a membership held`() {
        assertFalse(UploaderChange.JOIN.needsMembership)
        listOf(UploaderChange.RECONFIGURE, UploaderChange.LAUNCH, UploaderChange.GRANT_CHANGED)
            .forEach { assertTrue(it.needsMembership) }
    }

    @Test
    fun `the app's uploader is armed under any usable grant`() {
        assertEquals(UploaderArming.ARM, UploaderArming.of(PhotoAccess.FULL))
        assertEquals(UploaderArming.ARM, UploaderArming.of(PhotoAccess.LIMITED))
        assertEquals(UploaderArming.DISARM, UploaderArming.of(PhotoAccess.NONE))
    }
}
