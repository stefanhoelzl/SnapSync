package app.snapsync.services.crypto

import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyMaterial

/** What the key slot held at the entry's read, for the sealing and the opening that still frame raw bytes here. */
internal sealed interface KeptBytes {
    class Kept(val bytes: ByteArray, val id: String) : KeptBytes

    data object Unreadable : KeptBytes

    data object None : KeptBytes
}

/** The kept key's bytes and id — MIGRATING: the sealing and the opening move into the data layer with the cipher. */
@OptIn(KeyMaterial::class)
internal fun EventKeyStore.keptBytes(): KeptBytes = when (val fact = state.value) {
    is KeyFact.Kept -> KeptBytes.Kept(fact.key.key.bytes(), fact.key.id.value)
    KeyFact.Unreadable -> KeptBytes.Unreadable
    KeyFact.Absent, KeyFact.Invalid -> KeptBytes.None
}
