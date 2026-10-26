package app.snapsync.data.crypto

import app.snapsync.application.membership.Device
import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.event.SealVerdict
import app.snapsync.domain.membership.Membership
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryFiles
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How one upload goes up (capability `invite-link`, "Only the whole invite opens an event's photos"): sealed under the
 * event's key and bound to this resource, plain for a plain event — and nothing at all for an event this device is
 * no longer a member of, the one the cycle's requests still address.
 */
class UploadSealingTest {

    private val device = Device()
    private val crypto = fakeCrypto()
    private val ids = EventKeyIds(crypto)
    private val keys = EventKeyStore(RecordingSecureStore(), ids)
    private val shared = mutableMapOf<String, ByteArray>()
    private val sealing =
        UploadSealing(device.membership, keys, device.identity, FileCipher(crypto, inMemoryFiles(shared = shared)))
    private val event: EventId = testEventId(EVENT)
    private val asset = testAssetId("ASSET1")

    private fun joined(encryption: Encryption) {
        val plain = active()
        device.membership.enter(enrolled(Membership.Active(plain.event.copy(encryption = encryption), plain.share)))
    }

    @Test
    fun `an encrypted event's upload is sealed under its key and bound to this resource`() {
        val key = ids.mint()
        joined(Encryption.Encrypted(key.id))
        keys.keep(KeyToKeep.Key(key), event)
        val order = assertIs<UploadSeal.Sealed>(sealing.sealFor(event, asset, ResourceRole.LIVE)).order
        assertEquals(key.id, order.key.id)
        assertEquals(event, order.binding.event)
        assertEquals((device.identity.read as DeviceIdRead.Ok).value, order.binding.device)
        assertEquals(asset, order.binding.asset)
        assertEquals(ResourceRole.LIVE, order.binding.role)
        assertEquals("SealOrder(ASSET1)", order.toString(), "a seal order renders its asset, never its key")

        shared["p"] = ByteArray(10) { 3 }
        assertEquals(SealOutcome.Sealed, sealing.seal(order, "p", "s"))
        assertTrue("s" in shared)
        assertTrue(EncryptedFileFormat.FILE_KEY_HEADER in sealing.edgeSeal(order).headers())
    }

    @Test
    fun `a plain event's upload goes up as it is`() {
        joined(Encryption.Plain)
        assertEquals(UploadSeal.Plain, sealing.sealFor(event, asset, ResourceRole.PRIMARY))
    }

    @Test
    fun `nothing goes up to an event this device is not a member of, or without the key or the device`() {
        assertEquals(
            SealVerdict.Withheld.Because.NOT_JOINED,
            assertIs<UploadSeal.Withheld>(sealing.sealFor(event, asset, ResourceRole.PRIMARY)).because,
            "a leave mid-cycle: the bytes are still addressed to the event left",
        )
        val key = ids.mint()
        joined(Encryption.Encrypted(key.id))
        assertEquals(
            SealVerdict.Withheld.Because.KEY_LOST,
            assertIs<UploadSeal.Withheld>(sealing.sealFor(event, asset, ResourceRole.PRIMARY)).because,
        )
        keys.keep(KeyToKeep.Key(key), event)
        device.identity.read = DeviceIdRead.Absent
        assertEquals(
            SealVerdict.Withheld.Because.DEVICE_UNKNOWN,
            assertIs<UploadSeal.Withheld>(sealing.sealFor(event, asset, ResourceRole.PRIMARY)).because,
        )
        val unreadable = MembershipRepository(SharedDatabase(DatabasesMock().port(), legacy = null))
        assertEquals(
            SealVerdict.Withheld.Because.NOT_JOINED,
            assertIs<UploadSeal.Withheld>(
                UploadSealing(
                    unreadable,
                    keys,
                    device.identity,
                    FileCipher(crypto, inMemoryFiles()),
                ).sealFor(event, asset, ResourceRole.PRIMARY),
            ).because,
            "a membership that cannot be read is none to seal for",
        )
        device.identity.read = DeviceIdRead.Unavailable("locked")
        assertIs<UploadSeal.Withheld>(sealing.sealFor(event, asset, ResourceRole.PRIMARY))
    }
}
