package app.snapsync.data.upload

import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.sharing.Library
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.EchoReadiness
import app.snapsync.domain.upload.GateRead
import app.snapsync.domain.upload.UploadAdmission
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadHost
import app.snapsync.domain.upload.UploadPause
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class Received(
        var readiness: EchoReadiness = EchoReadiness.Ready,
        var recognised: Boolean = true,
    ) : ReceivedPhotos {
        val asked = mutableListOf<String>()

        override suspend fun readiness(): EchoReadiness = readiness.also { asked += "readiness" }

        override suspend fun recognised(event: EventId): Boolean = recognised.also { asked += "recognised" }

        override suspend fun suppressed(): Set<AssetId> = emptySet()
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
        assertEquals(0, run.version)
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
        received.readiness = EchoReadiness.OldSchema
        assertEquals(UploadGate.Paused(UploadPause.OLD_SCHEMA), gate().read())
        received.readiness = EchoReadiness.Ready
        received.recognised = false
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
        assertEquals(UploadAdmission.ADMIT, AppUploaderAdmission(library.own).admission())
        library.grant.value = LibraryGrant.DENIED
        assertEquals(UploadAdmission.WITHHELD, AppUploaderAdmission(library.own).admission())

        val grant = MutableStateFlow(LibraryGrant.GRANTED)
        assertEquals(UploadAdmission.ADMIT, ExtensionUploaderAdmission(GrantRead(grant)).admission())
        grant.value = LibraryGrant.LIMITED
        assertEquals(UploadAdmission.WITHHELD, ExtensionUploaderAdmission(GrantRead(grant)).admission())
    }
}
