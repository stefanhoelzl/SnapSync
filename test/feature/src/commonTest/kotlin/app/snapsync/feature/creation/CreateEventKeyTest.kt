package app.snapsync.feature.creation

import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.mock.DevControlsMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.CreateOutcome
import app.snapsync.services.backend.EventCreation
import app.snapsync.services.crypto.EventKeyMinting
import app.snapsync.services.crypto.EventKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The create's key (the encrypted file format, `docs/architecture.md`): over the REAL key minting, a create is
 * encrypted unless the build's control says to create a plain event — the key is minted on the creating device,
 * only its id is sent, and the key itself goes to the creator's own join.
 */
class CreateEventKeyTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    private class World {
        val controls = DevControlsMock()
        val keys = EventKeys(fakeCrypto(), inMemorySecureStore())
        var sentKeyId: String? = "unsent"
        var mintedLink: String? = "unrouted"
        val status = MutableStateFlow<CreationStatus>(CreationStatus.Idle)

        fun create(eventId: String) = CreateEvent(
            client = object : EventCreation {
                override suspend fun create(
                    name: String,
                    startsAt: String,
                    endsAt: String?,
                    keyId: String?,
                ): CreateOutcome {
                    sentKeyId = keyId
                    return CreateOutcome.Created(eventId)
                }
            },
            status = status,
            onMinted = { _, linkKey -> mintedLink = linkKey },
            minting = EventKeyMinting(keys, controls.port()),
        )
    }

    @Test
    fun `an encrypted create sends only the key id and routes the key to the creator's join`() = runTest {
        val w = World()

        w.create(eventId).create("Party", "2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z")

        val keyId = assertNotNull(w.sentKeyId)
        val linkKey = assertNotNull(w.mintedLink)
        assertTrue(w.keys.opens(linkKey, keyId), "the routed key is the one whose id was sent")
        assertTrue(linkKey != keyId, "the key itself never reaches the backend")
        assertEquals(CreationStatus.Idle, w.status.value)
    }

    @Test
    fun `a build that creates plain events mints no key and sends no key id`() = runTest {
        val w = World().apply { controls.operator.encryptsNewEvents = false }

        w.create(eventId).create("Party", "2026-07-14T18:00:00Z", "2026-07-21T18:00:00Z")

        assertNull(w.sentKeyId)
        assertNull(w.mintedLink)
    }
}
