package app.snapsync.data.upload

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.receiving.HandlesRead
import app.snapsync.data.receiving.ReceivedPhotosRead
import app.snapsync.data.receiving.ReceivedReadiness
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.data.sharing.Library
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.MintsRecognised
import app.snapsync.domain.receiving.Recognised
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.upload.GateRead
import app.snapsync.domain.upload.UploadAdmission
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadHost
import app.snapsync.domain.upload.UploadPause
import app.snapsync.ports.DevControls
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.UploaderPin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An upload pass's gate over the real stores: it pauses until the shared records are copied, reads the version, the
 * membership and the device id — naming each that could not be read — and runs only for an admitted process whose echo
 * store answers and whose event's received photos are recognised.
 */
class PassGateTest {

    @OptIn(MintsRecognised::class)
    private class Received(
        var readiness: ReceivedReadiness = ReceivedReadiness.Ready,
        /** What the store answers of recognition — `null` for recognised. */
        var recognition: RecognitionRead? = null,
    ) : ReceivedPhotosRead {
        val asked = mutableListOf<String>()

        override suspend fun readiness(): ReceivedReadiness = readiness.also { asked += "readiness" }

        override suspend fun recognition(event: EventId): RecognitionRead =
            (recognition ?: RecognitionRead.Recognised(Recognised(event))).also { asked += "recognised" }

        override suspend fun handles(): HandlesRead = HandlesRead.Ok(emptySet())
    }

    private class Admission(var answer: UploadAdmission = UploadAdmission.ADMIT) : UploaderAdmission {
        override fun admission(): UploadAdmission = answer
    }

    private val world = SessionWorld()
    private val received = Received()
    private val admission = Admission()

    private fun gate(records: SharedDatabase = world.shared, host: UploadHost = UploadHost.CARRIED) = UploadGateRead(
        records,
        PassEntryRead(world.reads, world.membership, world.identity, world.keys),
        host,
        admission,
        received,
    )

    @Test
    fun `a joined, admitted pass runs with the version its entry read first`() = runTest {
        world.joined()
        world.walk.discover(world.event, emptyList())

        val run = assertIs<UploadGate.Run>(gate().read())

        assertEquals(world.event, run.membership.event.id)
        assertEquals(ManifestVersion(1), run.version, "the join advanced it")
        assertEquals(listOf("readiness", "recognised"), received.asked)
    }

    @Test
    fun `records still in their legacy home pause the pass before anything is read`() = runTest {
        assertEquals(
            UploadGate.Paused(UploadPause.LEGACY_RECORDS),
            gate(SharedDatabase(world.databases.port(), null)).read(),
        )
        assertEquals(emptyList(), received.asked)
    }

    @Test
    fun `nothing joined is not joined, and a process withheld never opens the echo store`() = runTest {
        assertEquals(UploadGate.NotJoined, gate().read())

        world.joined()
        admission.answer = UploadAdmission.WITHHELD
        assertIs<UploadGate.Withheld>(gate().read())
        assertEquals(emptyList(), received.asked)
    }

    @Test
    fun `an echo store not ready, or photos not yet recognised, keep the pass from running`() = runTest {
        world.joined()
        received.readiness = ReceivedReadiness.OldSchema
        assertEquals(UploadGate.Paused(UploadPause.OLD_SCHEMA), gate().read())
        received.readiness = ReceivedReadiness.Unavailable("locked")
        assertIs<UploadGate.Unreadable>(gate().read())
        received.readiness = ReceivedReadiness.Ready
        received.recognition = RecognitionRead.NotRecognised
        assertIs<UploadGate.Withheld>(gate().read())
        // A recognition the store could not read is none: never a "yes" from a store it could not read.
        received.recognition = RecognitionRead.Unavailable("locked")
        assertIs<UploadGate.Withheld>(gate().read())
    }

    @Test
    fun `an entry that cannot read names what it could not, and is never a leave`() = runTest {
        world.joined()
        world.identity.read = DeviceIdRead.Absent
        assertTrue(assertIs<UploadGate.Unreadable>(gate().read()).detail.contains("deviceId absent"))
        world.identity.read = DeviceIdRead.Unavailable("locked")
        assertTrue(assertIs<UploadGate.Unreadable>(gate().read()).detail.contains("deviceId unreadable (locked)"))

        world.databases.lock()
        val locked = assertIs<UploadGate.Unreadable>(gate().read()).detail
        assertTrue(locked.contains("manifest version unreadable") && locked.contains("config:"), locked)
    }

    @Test
    fun `a membership unreadable while the version reads is named alone`() = runTest {
        world.joined()
        val other = SessionWorld()
        other.databases.lock()
        val entry = PassEntryRead(world.reads, other.membership, world.identity, world.keys).read()
        val detail = assertIs<GateRead.Unreadable>(entry.read).detail
        assertTrue(detail.contains("config:") && !detail.contains("manifest version"), detail)
    }

    @Test
    fun `a version unreadable while the membership reads is named alone`() = runTest {
        val locked = SessionWorld()
        locked.databases.lock()
        listOf(false, true).forEach { joined ->
            if (joined) world.joined()
            val entry = PassEntryRead(locked.reads, world.membership, world.identity, world.keys).read()
            val detail = assertIs<GateRead.Unreadable>(entry.read).detail
            assertTrue(detail.contains("manifest version") && !detail.contains("config:"), detail)
        }
    }

    @Test
    fun `a build that carries no upload host uploads nothing`() = runTest {
        world.joined()
        assertEquals(UploadGate.NotJoined, gate(host = UploadHost.NONE).read())
    }

    @Test
    fun `each uploader states its own admission`() = runTest {
        val library = Library(grant = LibraryGrant.GRANTED)
        assertEquals(UploadAdmission.ADMIT, AppUploaderAdmission(library.own, NO_PIN).admission())
        library.grant.value = LibraryGrant.DENIED
        assertEquals(UploadAdmission.WITHHELD, AppUploaderAdmission(library.own, NO_PIN).admission())
        library.grant.value = LibraryGrant.GRANTED
        val pinnedOff = AppUploaderAdmission(library.own, switches(UploaderPin(app = false)))
        assertEquals(UploadAdmission.WITHHELD, pinnedOff.admission(), "the rig switched the app off")

        library.grant.value = LibraryGrant.GRANTED
        assertEquals(UploadAdmission.ADMIT, ExtensionUploaderAdmission(library.reader).admission())
        library.grant.value = LibraryGrant.LIMITED
        assertEquals(UploadAdmission.WITHHELD, ExtensionUploaderAdmission(library.reader).admission())
    }
}

/** The rig's switches under [pin] — none, as a production build reads them, unless a test pins one. */
private fun switches(pin: UploaderPin?) = UploaderSwitches(
    object : DevControls {
        override fun uploaderPin(): UploaderPin? = pin

        override fun createsPlainEvents(): Boolean = false
    },
)

private val NO_PIN = switches(pin = null)
