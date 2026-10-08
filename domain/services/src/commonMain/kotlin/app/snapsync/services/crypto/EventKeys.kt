package app.snapsync.services.crypto

import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.EventConfig
import app.snapsync.model.Hmac
import app.snapsync.model.KeyPresence
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.decodeEventKey
import app.snapsync.model.encodeEventKey
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Crypto
import app.snapsync.ports.DevControls
import app.snapsync.ports.SecureStore
import app.snapsync.services.secure.persist
import app.snapsync.services.secure.readExisting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * **An encrypted event's key, on this device** (the encrypted file format, `docs/architecture.md`): minted by the
 * creating device, carried to every other only inside the invite link, and kept here in the secure store's shared
 * slot ([SecureSlots.EVENT_KEY]) — never in the event config, never in a log, never sent to the backend, which holds
 * only its id.
 *
 * One slot: single active membership is the current contract, so a join writes the joined event's key over whatever
 * was there, and a leave or a reset removes it.
 */
class EventKeys(private val crypto: Crypto, private val store: SecureStore) {

    private val hmac = Hmac(crypto::hmacSha256)

    /** A fresh key, as a link carries it, and its id, as the event row holds it. */
    fun mint(): MintedKey {
        val key = crypto.randomBytes(EncryptedFileFormat.KEY_LENGTH)
        return MintedKey(encodeEventKey(key), idOf(key))
    }

    /** The id of the key [linkKey] names, or `null` when it names no key. */
    fun idOf(linkKey: String): String? = decodeEventKey(linkKey)?.let(::idOf)

    /**
     * Whether a link carrying [linkKey] (or none) opens the event whose row holds [eventKeyId] (or none). A plain
     * event wants no key, an encrypted one exactly its own — a link without the key, or with another, never joins.
     */
    fun opens(linkKey: String?, eventKeyId: String?): Boolean =
        if (linkKey == null) eventKeyId == null else eventKeyId != null && idOf(linkKey) == eventKeyId

    /** Keep [linkKey] as the joined event's key, or throw [SecureStoreUnavailable] when the store refuses it. */
    fun keep(linkKey: String) = persist(store, SecureSlots.EVENT_KEY, linkKey)

    /**
     * The joined event's key, or `null` when none is kept. Throws [SecureStoreUnavailable] when it could not be read —
     * a locked device is never mistaken for a plain event.
     */
    fun current(): ByteArray? = readExisting(store, SecureSlots.EVENT_KEY)?.let(::decodeEventKey)

    /** The joined event's key as its invite link carries it, or `null` when none is kept. Throws like [current]. */
    fun linkKey(): String? = readExisting(store, SecureSlots.EVENT_KEY)

    /**
     * The key an invite to the joined event carries, following [membership] and read again whenever [rereads] emits:
     * `null` for a plain one or while the store cannot be read (a locked device, a lost key), so an invite never
     * carries a key that is not the event's — and an encrypted event's invite is then not offered at all.
     */
    fun inviteKeyOf(
        membership: StateFlow<EventConfig?>,
        rereads: Flow<Unit>,
        scope: CoroutineScope,
    ): StateFlow<String?> =
        combine(membership, rereads.onStart { emit(Unit) }) { config, _ ->
            config?.keyId?.let { runCatchingCancellable { linkKey() }.getOrNull() }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Whether the key of [membership]'s event is kept — read fresh from the store each call. A plain event or none
     * needs none; a store that cannot be read is [KeyPresence.Unknown], never mistaken for a lost key.
     */
    fun presenceFor(membership: EventConfig?): KeyPresence {
        val keyId = membership?.keyId ?: return KeyPresence.NotNeeded
        val kept = try {
            current()
        } catch (_: SecureStoreUnavailable) {
            return KeyPresence.Unknown
        }
        return if (kept != null && idOf(kept) == keyId) KeyPresence.Held else KeyPresence.Lost
    }

    /** Whether [membership]'s event key is [KeyPresence.Lost] — what stops both directions. */
    fun lostFor(membership: EventConfig?): Boolean = presenceFor(membership) == KeyPresence.Lost

    /** [membership]'s key id while its key is lost, else `null` — what a reopened invite must name to restore it. */
    fun lostKeyIdOf(membership: EventConfig?): String? = membership?.keyId?.takeIf { lostFor(membership) }

    /**
     * [presenceFor] over [membership], re-read whenever the membership changes and whenever [rereads] emits — a
     * foreground, a key kept from a reopened invite.
     */
    fun presenceOf(
        membership: StateFlow<EventConfig?>,
        rereads: Flow<Unit>,
        scope: CoroutineScope,
    ): StateFlow<KeyPresence> =
        combine(membership, rereads.onStart { emit(Unit) }) { config, _ -> presenceFor(config) }
            .stateIn(scope, SharingStarted.Eagerly, KeyPresence.Unknown)

    /** Remove the kept key — at a leave or a reset. Deleting nothing is fine. */
    fun forget() {
        store.delete(SecureSlots.EVENT_KEY)
    }

    private fun idOf(key: ByteArray): String =
        EncryptedFileFormat.keyIdOf(key, hmac).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** A freshly minted event key: [linkKey] for the invite link, [keyId] for the event row. */
class MintedKey(val linkKey: String, val keyId: String)

/**
 * **Whether a new event is encrypted, and its key** (the encrypted file format, `docs/architecture.md`): a fresh key
 * for every event, unless the build's development control asks for a plain one ([DevControls.createsPlainEvents] —
 * a rig build's switch, inert in production). Asked once per create.
 */
class EventKeyMinting(private val keys: EventKeys, private val controls: DevControls) {
    fun forNewEvent(): MintedKey? = if (controls.createsPlainEvents()) null else keys.mint()
}
