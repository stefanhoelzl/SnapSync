package app.snapsync.feature.crypto

import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.EventConfig
import app.snapsync.model.KeyPresence
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.services.crypto.EventKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Whether this device holds the joined event's key (capability `sync-status`): a plain event needs none, a kept key of
 * the event's id is held, an absent or foreign one is LOST, and a store that cannot be read says nothing about it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeyPresenceTest {

    private val crypto = fakeCrypto()

    private fun config(keyId: String?) = EventConfig(
        "11111111-1111-4111-8111-111111111111", "Party", captureCutoff("2026-07-06T00:00:00Z"),
        maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"), keyId = keyId,
    )

    @Test
    fun no_membership_or_a_plain_event_needs_no_key() {
        val keys = EventKeys(crypto, inMemorySecureStore())
        assertEquals(KeyPresence.NotNeeded, keys.presenceFor(null))
        assertEquals(KeyPresence.NotNeeded, keys.presenceFor(config(keyId = null)))
    }

    @Test
    fun the_events_own_key_is_held_and_its_absence_is_lost() {
        val keys = EventKeys(crypto, inMemorySecureStore())
        val minted = keys.mint()
        assertEquals(KeyPresence.Lost, keys.presenceFor(config(minted.keyId)), "a joined encrypted event with no key kept")
        keys.keep(minted.linkKey)
        assertEquals(KeyPresence.Held, keys.presenceFor(config(minted.keyId)))
    }

    @Test
    fun another_events_key_is_lost() {
        val keys = EventKeys(crypto, inMemorySecureStore())
        keys.keep(keys.mint().linkKey)
        assertEquals(KeyPresence.Lost, keys.presenceFor(config(keys.mint().keyId)))
    }

    @Test
    fun a_store_that_cannot_be_read_is_unknown_never_lost() {
        val keys = EventKeys(crypto, inMemorySecureStore(unavailable = true))
        assertEquals(KeyPresence.Unknown, keys.presenceFor(config("0123456789abcdef")))
    }

    @Test
    fun lost_answers_only_a_lost_key_and_names_its_id() {
        val keys = EventKeys(crypto, inMemorySecureStore())
        val minted = keys.mint()
        assertTrue(keys.lostFor(config(minted.keyId)))
        assertEquals(minted.keyId, keys.lostKeyIdOf(config(minted.keyId)))
        keys.keep(minted.linkKey)
        assertFalse(keys.lostFor(config(minted.keyId)))
        assertNull(keys.lostKeyIdOf(config(minted.keyId)))
        assertNull(keys.lostKeyIdOf(config(keyId = null)), "a plain event has no key to lose")
    }

    @Test
    fun the_presence_follows_the_membership_and_each_reread() = runTest {
        val keys = EventKeys(crypto, inMemorySecureStore())
        val minted = keys.mint()
        val membership = MutableStateFlow<EventConfig?>(null)
        val rereads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val presence = keys.presenceOf(membership, rereads, backgroundScope)
        runCurrent()
        assertEquals(KeyPresence.NotNeeded, presence.value)
        membership.value = config(minted.keyId)
        runCurrent()
        assertEquals(KeyPresence.Lost, presence.value)
        keys.keep(minted.linkKey)
        rereads.tryEmit(Unit)
        runCurrent()
        assertEquals(KeyPresence.Held, presence.value, "a reread sees the kept key")
    }
}
