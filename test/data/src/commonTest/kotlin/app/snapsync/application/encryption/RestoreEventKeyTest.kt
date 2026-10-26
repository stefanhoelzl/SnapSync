package app.snapsync.application.encryption

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.Device
import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.crypto.EncryptionSlots
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.RestoreAnswer
import app.snapsync.format.EventKeyText
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A reopened invite of the joined event gives a LOST key back — only the event's own, only while it is lost; anything
 * else changes nothing (capability `invite-link`, "Reopening the current event's invite changes nothing").
 */
@OptIn(EntryPointCall::class)
class RestoreEventKeyTest {

    private val device = Device()
    private val ids = EventKeyIds(fakeCrypto())
    private val secure = RecordingSecureStore()
    private val keys = EventKeyStore(secure, ids)
    private val restore = RestoreEventKey(device.membership, keys, ids)
    private val own = ids.mint()

    private fun joinedEncrypted() {
        val plain = active()
        device.membership.enter(
            enrolled(Membership.Active(plain.event.copy(encryption = Encryption.Encrypted(own.id)), plain.share)),
        )
        device.membership.reload()
    }

    @Test
    fun `the event's own key gives a lost one back`() = runTest {
        joinedEncrypted()
        assertEquals(RestoreOutcome.Restored, restore(own.key))
        assertEquals(RestoreAnswer.Restored, RestoreOutcome.Restored.answer)
        assertEquals(true, RestoreOutcome.Restored.resumes)
        assertEquals(own.id, assertIs<KeyFact.Kept>(keys.state.value).key.id)
        assertEquals(listOf(EventKeyText.encode(own.key)), secure.writesTo(EncryptionSlots.EVENT_KEY))
    }

    @Test
    fun `another key, a held key, a plain event or none joined change nothing`() = runTest {
        assertEquals(RestoreOutcome.NothingChanged, restore(own.key), "none joined")
        device.membership.enter(enrolled())
        device.membership.reload()
        assertEquals(RestoreOutcome.NothingChanged, restore(own.key), "a plain event")
        device.membership.leave()
        joinedEncrypted()
        assertEquals(RestoreOutcome.NothingChanged, restore(ids.mint().key), "another key")
        secure.answers[EncryptionSlots.EVENT_KEY] =
            SecureStoreRead.Found(EventKeyText.encode(own.key), StoredProtection.BACKGROUND_READABLE)
        keys.reload()
        assertEquals(RestoreOutcome.NothingChanged, restore(own.key), "held already")
        assertEquals(RestoreAnswer.NothingChanged, RestoreOutcome.NothingChanged.answer)
        assertEquals(false, RestoreOutcome.NothingChanged.resumes)
        assertEquals(emptyList(), secure.writesTo(EncryptionSlots.EVENT_KEY))
    }

    @Test
    fun `a membership that cannot be read restores nothing`() = runTest {
        val unreadable = MembershipRepository(SharedDatabase(DatabasesMock().port(), legacy = null))
        assertIs<MembershipRead.Unavailable>(unreadable.state.value)
        assertEquals(RestoreOutcome.NothingChanged, RestoreEventKey(unreadable, keys, ids)(own.key))
    }

    @Test
    fun `a key the store refuses is answered as failed`() = runTest {
        joinedEncrypted()
        secure.refuseWrites = true
        val refused = assertIs<RestoreOutcome.Refused>(restore(own.key))
        assertEquals(RestoreAnswer.Failed, refused.answer)
        assertEquals(false, refused.resumes)
        assertEquals("OSStatus -25308", refused.detail)
    }
}
