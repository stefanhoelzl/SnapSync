package app.snapsync.data.upload

import app.snapsync.domain.upload.AppUploaderSwitch
import app.snapsync.domain.upload.ExtensionSwitch
import app.snapsync.domain.upload.OsUploaderAdmission
import app.snapsync.domain.upload.OsUploaderStanding
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.osUploaderOver
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.RegistrationState
import app.snapsync.ports.UploaderPin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** The OS's own uploader as the app asks about it: may it be registered now, and does the OS confirm it is. */
class OsUploaderTest {

    private val grant = MutableStateFlow(LibraryGrant.GRANTED)
    private var state = RegistrationState.REGISTERED
    private var asked = 0
    private var pin: UploaderPin? = null

    private fun uploader(supported: Boolean = true) = osUploaderOver(
        supported,
        grant,
        registered = { state.also { asked++ } },
        pin = { pin },
    )

    @Test
    fun `registrable under a full grant on an OS that carries it, read fresh at every ask`() {
        assertEquals(OsUploaderAdmission.REGISTRABLE, uploader().admission())
        grant.value = LibraryGrant.LIMITED
        assertEquals(OsUploaderAdmission.NOT_REGISTRABLE, uploader().admission())
        grant.value = LibraryGrant.GRANTED
        assertEquals(OsUploaderAdmission.NOT_REGISTRABLE, uploader(supported = false).admission())
        pin = UploaderPin(extension = false)
        assertEquals(OsUploaderAdmission.NOT_REGISTRABLE, uploader().admission())
        assertEquals(0, asked, "whether it may be registered asks the OS nothing")
    }

    @Test
    fun `the OS is asked whether it is registered only while it may be`() {
        assertEquals(OsUploaderStanding.CONFIRMED, uploader().standing())
        state = RegistrationState.NOT_REGISTERED
        assertEquals(OsUploaderStanding.NOT_CONFIRMED, uploader().standing())
        state = RegistrationState.UNSUPPORTED
        assertEquals(OsUploaderStanding.NOT_CONFIRMED, uploader().standing())
        assertEquals(3, asked)

        grant.value = LibraryGrant.DENIED
        state = RegistrationState.REGISTERED
        assertEquals(OsUploaderStanding.NOT_CONFIRMED, uploader().standing())
        assertEquals(3, asked, "not asked under a grant that makes its answer meaningless")
    }

    /** The rig's switches and nothing else. */
    private class PinOnly(private val pin: UploaderPin?) : DevControls {
        @CompositionOnly
        override fun inbound(handlers: DevHandlers) = Unit

        override fun uploaderPin(): UploaderPin? = pin

        override fun createsPlainEvents(): Boolean = false
    }

    @Test
    fun `the rig's switches are on unless it turned one off`() {
        val app = { p: UploaderPin? -> UploaderSwitches(PinOnly(p)).app() }
        val extension = { p: UploaderPin? -> UploaderSwitches(PinOnly(p)).extension() }
        assertEquals(AppUploaderSwitch.ON, app(null))
        assertEquals(AppUploaderSwitch.ON, app(UploaderPin(extension = false)))
        assertEquals(AppUploaderSwitch.OFF, app(UploaderPin(app = false)))
        assertEquals(ExtensionSwitch.ON, extension(null))
        assertEquals(ExtensionSwitch.ON, extension(UploaderPin(app = false)))
        assertEquals(ExtensionSwitch.OFF, extension(UploaderPin(extension = false)))
    }
}
