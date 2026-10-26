package app.snapsync.query.encryption

import app.snapsync.application.membership.Device
import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.crypto.EncryptionSlots
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.Membership
import app.snapsync.format.EventKeyText
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.testids.testEventId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the screen reads of the joined event's key (capabilities `sync-status`, `invite-link`): whether it is held, and
 * the key its invite carries — following the membership and the slot as each entry re-reads them, and never offering
 * an encrypted event's invite without its key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventKeyQueryTest {

    private val device = Device()
    private val ids = EventKeyIds(fakeCrypto())
    private val secure = RecordingSecureStore()
    private val keys = EventKeyStore(secure, ids)
    private val key = ids.mint()

    private fun encrypted(): Membership.Active = active().let {
        Membership.Active(it.event.copy(encryption = Encryption.Encrypted(key.id)), it.share)
    }

    @Test
    fun `no membership, or one that cannot be read, needs no key and offers none`() = runTest {
        val query = EventKeyQuery(device.membership, keys, backgroundScope)
        assertEquals(KeyPresence.NotNeeded, query.presence.value)
        val unreadable = MembershipRepository(SharedDatabase(DatabasesMock().port(), legacy = null))
        assertIs<MembershipRead.Unavailable>(unreadable.state.value)
        assertEquals(KeyPresence.NotNeeded, EventKeyQuery(unreadable, keys, backgroundScope).presence.value)
    }

    @Test
    fun `an encrypted event's held key is its invite's, and a lost one offers none`() = runTest {
        device.membership.enter(enrolled(encrypted()))
        device.membership.reload()
        keys.keep(KeyToKeep.Key(key), testEventId(EVENT))
        val query = EventKeyQuery(device.membership, keys, backgroundScope)
        assertEquals(key.key, assertIs<KeyPresence.Held>(query.presence.value).key.key)

        secure.answers.remove(EncryptionSlots.EVENT_KEY)
        keys.reload()
        runCurrent()
        assertEquals(KeyPresence.Lost, query.presence.value)

        secure.answers[EncryptionSlots.EVENT_KEY] = SecureStoreRead.Unavailable("locked")
        keys.reload()
        runCurrent()
        assertEquals(KeyPresence.Unknown, query.presence.value, "a locked phone is no lost key")
    }

    @Test
    fun `a plain event's invite carries no key`() = runTest {
        device.membership.enter(enrolled())
        device.membership.reload()
        secure.answers[EncryptionSlots.EVENT_KEY] =
            SecureStoreRead.Found(EventKeyText.encode(key.key), StoredProtection.BACKGROUND_READABLE)
        val query = EventKeyQuery(device.membership, keys, backgroundScope)
        assertEquals(KeyPresence.NotNeeded, query.presence.value)
    }
}
