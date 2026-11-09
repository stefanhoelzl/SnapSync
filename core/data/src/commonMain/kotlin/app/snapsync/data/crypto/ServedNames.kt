package app.snapsync.data.crypto

import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.SenderFilename
import app.snapsync.domain.receiving.ServedFileName

/**
 * **How the names a membership's event serves its photos under are read** (decision record
 * `changes/seal-file-names`, D6): as they are in a plain event; in an encrypted one, each opened with the event's kept
 * key for the photo it names — the event, the device that shared it, its asset and its role — exactly as
 * [DownloadOpening] opens the photo's bytes.
 * The photo list and this device's own listing both read through it, so neither ever takes a sealed text for a name.
 *
 * Asked for the membership a read is FOR, never the one stored: a join lists and recognises before it enters its
 * membership, with the event's key already kept.
 */
class ServedNames(private val keys: EventKeyStore, private val cipher: FileNameCipher) {
    /** How the names [membership]'s event serves are read now — or why they cannot be now. */
    fun of(membership: Membership): NameReading = when (val presence = KeyPresence.of(membership, keys.state.value)) {
        KeyPresence.NotNeeded -> NameReading.Readable
        is KeyPresence.Held -> NameReading.Sealed(presence.key, cipher)
        KeyPresence.Unknown -> NameReading.Unavailable("the event's key cannot be read now")
        KeyPresence.Lost -> NameReading.Unavailable("no key of the event is kept")
    }
}

/** How an event's served names are read, or that they cannot be now. */
sealed interface NameReading {
    /** The names open: each one through [nameOf]. */
    sealed interface Opens : NameReading {
        /** The name [served] holds for the photo [binding] names — or why it holds none. */
        fun nameOf(binding: FileBinding, served: String): ServedFileName
    }

    /** A plain event's: the names as served, any but the empty one. */
    data object Readable : Opens {
        override fun nameOf(binding: FileBinding, served: String): ServedFileName =
            SenderFilename.parse(served)?.let(ServedFileName::Named) ?: ServedFileName.Malformed
    }

    /** An encrypted event's: each name opened with its [key] for the photo it is sealed for. */
    class Sealed internal constructor(private val key: IdentifiedKey, private val cipher: FileNameCipher) : Opens {
        override fun nameOf(binding: FileBinding, served: String): ServedFileName =
            when (val opening = cipher.openFileName(key, binding, served)) {
                is FileNameOpening.Opened -> ServedFileName.Named(opening.name)
                is FileNameOpening.Damaged -> ServedFileName.Unopened(opening.reason)
            }
    }

    /**
     * Nothing reads them now: the event's key is not readable now, or not kept. Read again later — a member who cannot
     * name the others' photos is never told it holds them.
     */
    data class Unavailable(val detail: String) : NameReading
}
