package app.snapsync.domain.event

import app.snapsync.domain.membership.DevReset
import app.snapsync.domain.membership.KeptKey
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsDevReset
import app.snapsync.domain.membership.MintsKeptKey
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An encrypted event's key and what it decides (capability `invite-link`): only an event's own key opens it, a lost one
 * stops both directions until its invite gives it back, and a locked phone is never mistaken for a lost key.
 */
@OptIn(MakesEventKey::class, KeyMaterial::class, MintsIdentifiedKey::class)
class EventKeyTest {

    private fun key(fill: Int) = EventKey.of(ByteArray(EventKey.LENGTH) { fill.toByte() })!!

    private val ownId = EventKeyId("0011223344556677")
    private val own = IdentifiedKey(key(1), ownId)
    private val other = IdentifiedKey(key(2), EventKeyId("8899aabbccddeeff"))

    private val share = Share(
        assertIs<CaptureRangeVerdict.Ok>(
            CaptureRange.of(
                CaptureCutoff(anEvent().range.start.at),
                CaptureCeiling(anEvent().range.end.at),
                anEvent().range.start,
                anEvent().range.end,
            ),
        ).range,
        Direction.Both,
        saveToAlbum = false,
    )
    private val encrypted = Membership.Active(anEvent().copy(encryption = Encryption.Encrypted(ownId)), share)
    private val plain = Membership.Active(anEvent(), share)

    @Test
    fun `a key is exactly 32 bytes, kept as a copy, compared by its bytes and never rendered`() {
        assertNull(EventKey.of(ByteArray(16)), "a 16-byte key would run AES-128")
        assertNull(EventKey.of(ByteArray(33)))
        val bytes = ByteArray(EventKey.LENGTH) { 5 }
        val key = EventKey.of(bytes)!!
        bytes[0] = 9
        assertContentEquals(ByteArray(EventKey.LENGTH) { 5 }, key.bytes(), "a key keeps its own copy")
        assertEquals(key(5), key)
        assertEquals(key(5).hashCode(), key.hashCode())
        assertNotEquals(key(6), key)
        assertNotEquals<Any>(key, "a key")
        assertEquals("EventKey(***)", key.toString())
        assertEquals("IdentifiedKey(0011223344556677)", own.toString(), "a key with its id renders the id alone")
    }

    @Test
    fun `a plain event wants no key, an encrypted one exactly its own`() {
        assertSame(KeyToKeep.None, assertIs<KeyVerdict.Opens>(Encryption.Plain.openedBy(null)).keep)
        assertEquals(KeyVerdict.WrongKey, Encryption.Plain.openedBy(own), "a key never opens a plain event")
        val opened = assertIs<KeyVerdict.Opens>(Encryption.Encrypted(ownId).openedBy(own))
        assertSame(own, assertIs<KeyToKeep.Key>(opened.keep).key)
        assertEquals(KeyVerdict.WrongKey, Encryption.Encrypted(ownId).openedBy(other))
        assertEquals(KeyVerdict.WrongKey, Encryption.Encrypted(ownId).openedBy(null), "an invite cut short")
    }

    @Test
    fun `only an encrypted event whose own key is not kept has lost it`() {
        assertSame(own, assertIs<KeyPresence.Held>(KeyPresence.of(encrypted, KeyFact.Kept(own))).key)
        assertEquals(KeyPresence.Lost, KeyPresence.of(encrypted, KeyFact.Kept(other)), "another event's key")
        assertEquals(KeyPresence.Lost, KeyPresence.of(encrypted, KeyFact.Absent))
        assertEquals(KeyPresence.Lost, KeyPresence.of(encrypted, KeyFact.Invalid), "a slot holding no key")
        assertEquals(
            KeyPresence.Unknown,
            KeyPresence.of(encrypted, KeyFact.Unreadable),
            "a locked phone is no lost key",
        )
        assertEquals(KeyPresence.NotNeeded, KeyPresence.of(plain, KeyFact.Absent))
        assertEquals(KeyPresence.NotNeeded, KeyPresence.of(null, KeyFact.Kept(own)), "no membership")
        val deleted = Membership.Deleted(encrypted.event, share)
        assertEquals(KeyPresence.Lost, KeyPresence.of(deleted, KeyFact.Absent), "any membership's event")
    }

    @Test
    fun `a reopened invite restores only a lost key, and only with the event's own`() {
        val restored = assertIs<RestoreVerdict.Restore>(RestoreVerdict.of(encrypted, KeyPresence.Lost, own))
        assertSame(own, restored.keep.key)
        assertEquals(encrypted.event.id, restored.event)
        assertEquals(RestoreVerdict.NotTheEventsKey, RestoreVerdict.of(encrypted, KeyPresence.Lost, other))
        assertEquals(RestoreVerdict.NotTheEventsKey, RestoreVerdict.of(null, KeyPresence.Lost, own))
        assertEquals(RestoreVerdict.NotLost, RestoreVerdict.of(encrypted, KeyPresence.Held(own), own))
        assertEquals(RestoreVerdict.NotLost, RestoreVerdict.of(encrypted, KeyPresence.Unknown, own))
        assertEquals(RestoreVerdict.NotLost, RestoreVerdict.of(plain, KeyPresence.NotNeeded, own))
    }

    @OptIn(MintsKeptKey::class, MintsDevReset::class)
    @Test
    fun `the key's evidence names the event it was kept for`() {
        assertEquals(encrypted.event.id, KeptKey(encrypted.event.id).event)
        assertTrue(DevReset() !== DevReset())
        assertFalse(Encryption.Encrypted(ownId) == Encryption.Encrypted(other.id))
    }
}
