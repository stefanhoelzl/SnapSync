package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The registration fact over every input cell (capability `upload-lifecycle`). */
class ExtensionRegistrableTest {

    private val pins: List<UploaderPin?> = listOf(
        null,
        UploaderPin(),
        UploaderPin(app = false),
        UploaderPin(extension = false),
        UploaderPin(app = false, extension = false),
    )

    @Test
    fun `never registrable below 26_1 whatever the grant or pin`() {
        for (permission in PermissionStatus.entries) for (pin in pins) {
            assertFalse(extensionRegistrable(false, permission, pin), "$permission / $pin")
        }
    }

    @Test
    fun `registrable exactly under a full grant on 26_1 or later`() {
        for (permission in PermissionStatus.entries) {
            assertEquals(permission == PermissionStatus.GRANTED, extensionRegistrable(true, permission), "$permission")
        }
    }

    @Test
    fun `the extension switch turns it off and nothing else does`() {
        assertFalse(extensionRegistrable(true, PermissionStatus.GRANTED, UploaderPin(extension = false)))
        assertTrue(extensionRegistrable(true, PermissionStatus.GRANTED, UploaderPin(app = false)))
        assertTrue(extensionRegistrable(true, PermissionStatus.GRANTED, UploaderPin()))
    }
}
