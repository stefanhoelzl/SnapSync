package app.snapsync.mock

import app.snapsync.levers.PhotoGrant
import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether the operating system carries the upload extension is a durable fact of the device that a launch reads when it
 * builds its registry, and the registration judges a change by the photo library's grant at the moment of the change.
 */
class ExtensionRegistryMockTest {

    @Test
    fun a_launch_keeps_the_fact_it_was_built_with_and_the_next_launch_reads_the_new_one() = runTest {
        val device = MockDevice()
        val before = device.extensionRegistry.port()
        device.extensionRegistry.operator.carried(true)
        val after = device.extensionRegistry.port()

        assertFalse(before.supported, "the running launch keeps the answer it composed with")
        assertEquals(RegistrationAnswer.Unsupported, before.setEnabled(true))
        assertTrue(after.supported, "the next launch reads the fact")
        assertEquals(RegistrationAnswer.Answered(ok = true, domain = null, code = null), after.setEnabled(true))
    }

    @Test
    fun a_registration_outlives_the_fact_being_toggled_off_and_on() = runTest {
        val device = MockDevice(osDrivenUpload = true)
        device.extensionRegistry.port().setEnabled(true)

        device.extensionRegistry.operator.carried(false)
        assertNull(
            device.extensionRegistry.operator.registered(),
            "an OS without the extension holds no record to read",
        )
        assertEquals(RegistrationState.UNSUPPORTED, device.extensionRegistry.port().isEnabled())

        device.extensionRegistry.operator.carried(true)
        assertEquals(true, device.extensionRegistry.operator.registered())
        assertEquals(RegistrationState.REGISTERED, device.extensionRegistry.port().isEnabled())
    }

    @Test
    fun narrowing_the_library_grant_refuses_every_change_and_hides_the_kept_registration() = runTest {
        val device = MockDevice(osDrivenUpload = true)
        val registry = device.extensionRegistry.port()
        registry.setEnabled(true)

        device.library.operator.permission(PhotoGrant.LIMITED)
        val refused = RegistrationAnswer.Answered(ok = false, domain = "PHPhotosErrorDomain", code = 3311L)
        assertEquals(refused, registry.setEnabled(false), "the grant is read at the change, not at the launch")
        assertEquals(refused, registry.setEnabled(true))
        assertEquals(RegistrationState.NOT_REGISTERED, registry.isEnabled(), "a partial grant cannot see the record")
        assertEquals(true, device.extensionRegistry.operator.registered(), "which the OS still holds")

        device.library.operator.permission(PhotoGrant.GRANTED)
        assertEquals(RegistrationState.REGISTERED, registry.isEnabled(), "and reads back once access is full")
        assertEquals(RegistrationAnswer.Answered(ok = true, domain = null, code = null), registry.setEnabled(false))
        assertEquals(RegistrationState.NOT_REGISTERED, registry.isEnabled())
    }
}
