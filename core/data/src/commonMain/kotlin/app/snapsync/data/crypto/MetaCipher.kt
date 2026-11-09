package app.snapsync.data.crypto

import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.event.KeyVerdict
import app.snapsync.domain.event.NameRead
import app.snapsync.domain.event.ServedName
import app.snapsync.format.MetaRecord
import app.snapsync.format.SealedMetaFormat
import app.snapsync.ports.AesKey
import app.snapsync.ports.Crypto

/**
 * **An encrypted event's metadata record, sealed and opened** — the sealed metadata format ([SealedMetaFormat]) under
 * the key derived from the event's own ([SealedRecords]). What a failure means to the event is its callers': this
 * answers what happened to one record.
 */
@OptIn(KeyMaterial::class)
class MetaCipher(crypto: Crypto) {
    private val records = SealedRecords(crypto)
    private val hmac = CryptoHmac(crypto)

    /** [record], sealed under [key], as the wire and the row carry it. */
    fun seal(key: IdentifiedKey, record: MetaRecord): String = records.seal(metaKey(key), record)

    /** The record [envelope] holds, opened with [key], or why it does not open. */
    fun open(key: IdentifiedKey, envelope: String): MetaOpening = records.open(metaKey(key), envelope)

    /**
     * An event's name as the server served it (decision record `changes/seal-event-name`): readable as [name] — a plain
     * event, or an encrypted one created before names were sealed — or as the sealed record [sealedMeta], opened here
     * with [key] when the event's encryption says it is the event's own (`Encryption.openedBy`). What it comes to is
     * the domain's (`NameVerdict`). Translation only: a key of another id is no key for this event, so its record is
     * not even tried; a served event with neither is a blank name, which the domain refuses.
     */
    fun nameRead(name: String?, sealedMeta: String?, encryption: Encryption, key: IdentifiedKey?): NameRead =
        when (val served = ServedName.of(name, sealedMeta)) {
            is ServedName.Readable -> NameRead.Text(served.text)
            is ServedName.Sealed -> sealed(served.record, encryption.openedBy(key))
            ServedName.Neither -> NameRead.Text("")
        }

    private fun sealed(record: String, verdict: KeyVerdict): NameRead = when (verdict) {
        is KeyVerdict.Opens -> keyed(record, verdict.keep)
        KeyVerdict.WrongKey -> NameRead.NoKey
    }

    private fun keyed(record: String, keep: KeyToKeep): NameRead = when (keep) {
        is KeyToKeep.Key -> nameOf(open(keep.key, record))
        KeyToKeep.None -> NameRead.NoKey
    }

    private fun nameOf(opening: MetaOpening): NameRead = when (opening) {
        is MetaOpening.Opened -> NameRead.Text(opening.record.name)
        is MetaOpening.Damaged -> NameRead.Damaged
    }

    private fun metaKey(key: IdentifiedKey): AesKey = records.keyOf(SealedMetaFormat.metaKeyOf(key.key.bytes(), hmac))
}
