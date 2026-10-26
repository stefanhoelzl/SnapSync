package app.snapsync.feature.support

import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.format.EventKeyText
import app.snapsync.ports.Crypto
import app.snapsync.ports.SecureStore
import app.snapsync.testids.testEventId

/**
 * A key store with keys a test mints and keeps by their invite text — the sealing and the opening the old zones still
 * frame are tested over it until they move into the data layer with the cipher.
 */
@OptIn(KeyMaterial::class)
internal class TestEventKeys(crypto: Crypto, secure: SecureStore) {
    private val ids = EventKeyIds(crypto)
    val store = EventKeyStore(secure, ids)

    /** A minted key: [linkKey] as an invite carries it, [keyId] as the event row holds it. */
    class Minted(val linkKey: String, val keyId: String)

    fun mint(): Minted = ids.mint().let { Minted(EventKeyText.encode(it.key), it.id.value) }

    fun keep(linkKey: String) {
        store.keep(KeyToKeep.Key(ids.identify(EventKeyText.decode(linkKey)!!)), testEventId("kept"))
    }

    /** The kept key's bytes. */
    fun current(): ByteArray? = (store.state.value as? KeyFact.Kept)?.key?.key?.bytes()
}
