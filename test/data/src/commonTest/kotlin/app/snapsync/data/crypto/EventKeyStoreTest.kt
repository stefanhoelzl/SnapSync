package app.snapsync.data.crypto

import app.snapsync.data.RecordingSecureStore
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.DevReset
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.MintsConfirmedNotJoined
import app.snapsync.domain.membership.MintsDevReset
import app.snapsync.domain.membership.MintsLeftMembership
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.format.EventKeyText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.mock.DevControlsMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.ports.Crypto
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.testids.testEventId
import co.touchlab.kermit.Severity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val SLOT = EncryptionSlots.EVENT_KEY
private const val EVENT = "11111111-1111-4111-8111-111111111111"

/**
 * The joined event's key on this device (capabilities `invite-link`, `sync-status`): kept in the one shared slot, read
 * again at every entry, and what it holds told apart — a key, nothing, something that is no key, or a store that does
 * not answer. A locked phone is never "no key", and the slot's content is never logged.
 */
@OptIn(CompositionOnly::class, KeyMaterial::class)
class EventKeyStoreTest {

    private val lines = mutableListOf<LogLine>()

    @BeforeTest
    fun record() = Log.install(
        listOf(object : LogWriter {
            override fun write(line: LogLine) {
                lines += line
            }
        }),
    )

    @AfterTest
    fun stop() = Log.install(emptyList())

    private fun warnings() = lines.filter { it.tag == "EventKeyStore" && it.severity == Severity.Warn }

    private val ids = EventKeyIds(fakeCrypto())
    private val secure = RecordingSecureStore()
    private val keys = EventKeyStore(secure, ids)

    private fun found(text: String) = SecureStoreRead.Found(text, StoredProtection.BACKGROUND_READABLE)

    @Test
    fun `a kept key reads back with its id, and nothing kept reads as none`() {
        assertEquals(KeyFact.Absent, keys.state.value, "seeded at the first read")
        val key = ids.mint()
        secure.answers[SLOT] = found(EventKeyText.encode(key.key))
        val kept = assertIs<KeyFact.Kept>(keys.reload())
        assertEquals(key.key, kept.key.key)
        assertEquals(key.id, kept.key.id)
        assertSame(kept, keys.state.value)
    }

    @Test
    fun `a locked phone is unreadable, never no key`() {
        secure.answers[SLOT] = SecureStoreRead.Unavailable("OSStatus -25308")
        assertEquals(KeyFact.Unreadable, keys.state.value)
        assertEquals(KeyFact.Unreadable, keys.reload())
    }

    @Test
    fun `a slot holding no key is invalid, said once and naming nothing of it`() {
        secure.answers[SLOT] = found("not-a-key")
        assertEquals(KeyFact.Invalid, keys.state.value)
        assertEquals(KeyFact.Invalid, keys.reload())
        assertEquals(1, warnings().size, "once, when the slot turns to it")
        assertTrue(warnings().none { line -> "not-a-key" in line.toString() })

        secure.answers.remove(SLOT)
        keys.reload()
        secure.answers[SLOT] = found("still-not-a-key")
        assertEquals(KeyFact.Invalid, keys.reload())
        assertEquals(2, warnings().size, "again when it turns to it again, from none kept")

        secure.answers[SLOT] = found(EventKeyText.encode(ids.mint().key))
        keys.reload()
        secure.answers[SLOT] = found("not-a-key")
        keys.reload()
        secure.answers[SLOT] = SecureStoreRead.Unavailable("locked")
        keys.reload()
        secure.answers[SLOT] = found("not-a-key")
        keys.reload()
        assertEquals(4, warnings().size, "from a key, and from a locked store")
    }

    @Test
    fun `a join keeps its event's key, and its evidence names the event`() {
        val key = ids.mint()
        val kept = assertIs<KeepOutcome.Kept>(keys.keep(KeyToKeep.Key(key), testEventId(EVENT)))
        assertEquals(testEventId(EVENT), kept.evidence.event)
        assertEquals(listOf(EventKeyText.encode(key.key)), secure.writesTo(SLOT))
        assertEquals(key.id, assertIs<KeyFact.Kept>(keys.state.value).key.id)
    }

    @Test
    fun `a key the store refuses fails the join, and the slot is read again`() {
        secure.refuseWrites = true
        val refused = assertIs<KeepOutcome.Refused>(keys.keep(KeyToKeep.Key(ids.mint()), testEventId(EVENT)))
        assertEquals("OSStatus -25308", refused.detail)
        assertEquals(KeyFact.Absent, keys.state.value)
    }

    @Test
    fun `a plain event's join clears the slot, and a refused clear does not fail it`() {
        secure.answers[SLOT] = found(EventKeyText.encode(ids.mint().key))
        assertIs<KeepOutcome.Kept>(keys.keep(KeyToKeep.None, testEventId(EVENT)))
        assertEquals(KeyFact.Absent, keys.state.value)

        secure.answers[SLOT] = found(EventKeyText.encode(ids.mint().key))
        secure.refuseDeletes = true
        assertIs<KeepOutcome.Kept>(keys.keep(KeyToKeep.None, testEventId(EVENT)))
        assertIs<KeyFact.Kept>(keys.state.value, "what the slot still holds — inert: no membership names it")
    }

    @OptIn(MintsLeftMembership::class, MintsConfirmedNotJoined::class, MintsDevReset::class)
    @Test
    fun `a leave, a sweep and the rig's reset each forget the key`() {
        for (forget in listOf<() -> KeyFact>(
            { keys.forget(LeftMembership(testEventId(EVENT))) },
            { keys.forget(ConfirmedNotJoined()) },
            { keys.forget(DevReset()) },
        )) {
            secure.answers[SLOT] = found(EventKeyText.encode(ids.mint().key))
            keys.reload()
            assertEquals(KeyFact.Absent, forget())
            assertEquals(KeyFact.Absent, keys.state.value)
        }
        assertEquals(3, secure.deletes.count { it == SLOT })
    }

    @Test
    fun `a key's id is its derivation's, and a mint is a fresh key`() {
        val one = ids.mint()
        val other = ids.mint()
        assertNotEquals(one.key, other.key)
        assertEquals(one.id, ids.identify(one.key).id)
        assertEquals(16, one.id.value.length, "the format's 8-byte id, in hex")
        assertContentEquals(one.key.bytes(), EventKeyText.decode(EventKeyText.encode(one.key))?.bytes())
        val short = EventKeyIds(object : Crypto by fakeCrypto() {
            override fun randomBytes(count: Int): ByteArray = ByteArray(count - 1)
        })
        assertFailsWith<InvalidProgramState> { short.mint() }
    }

    @Test
    fun `a new event is encrypted under a fresh key unless the build creates plain ones`() {
        val controls = DevControlsMock()
        val keys = NewEventKeys(ids, controls.port())
        assertIs<NewEventKey.Encrypted>(keys.forNewEvent())
        controls.encrypts = false
        assertEquals(NewEventKey.Plain, keys.forNewEvent())
    }
}
