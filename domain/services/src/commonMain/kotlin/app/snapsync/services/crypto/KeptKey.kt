package app.snapsync.services.crypto

import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyFact

/** What the key slot held at the entry's read, for the sealing and the opening still composed here. */
internal sealed interface Kept {
    class Key(val key: IdentifiedKey) : Kept

    data object Unreadable : Kept

    data object None : Kept
}

/** The kept key — MIGRATING: the sealing and the opening move into the data layer. */
internal fun EventKeyStore.kept(): Kept = when (val fact = state.value) {
    is KeyFact.Kept -> Kept.Key(fact.key)
    KeyFact.Unreadable -> Kept.Unreadable
    KeyFact.Absent, KeyFact.Invalid -> Kept.None
}
