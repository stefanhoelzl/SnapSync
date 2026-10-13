package app.snapsync.data.trust

import app.snapsync.data.backend.TokenRead
import app.snapsync.domain.trust.AttestKeyId
import app.snapsync.domain.trust.BearerToken
import app.snapsync.domain.trust.RejectionVerdict
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.WriteOutcome
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Where the device token and its key id live: a store that outlives the app install and stays readable while the
 * device is locked, addressable by **both** processes — the upload extension must read the token, and the OS invokes
 * it when the device is idle, which usually means locked. Two implementations: the store of record
 * ([SecureCredentialStore]) and the in-memory copy each process composes in front of it ([CachedCredentialStore]).
 *
 * Nothing here throws: an unreadable store is `Unavailable`, never mistaken for absence — minting on a forged "absent"
 * would burn a fresh attestation.
 */
interface CredentialStore {
    fun token(): TokenRead

    fun setToken(token: BearerToken): WriteOutcome

    fun keyId(): KeyIdRead

    fun setKeyId(keyId: AttestKeyId): WriteOutcome

    /**
     * Drop the stored token only if it is still [expected] — compare-and-clear ([RejectionVerdict]): a rejection
     * names the token the refused request carried, and a late one must not erase a successor this process or the
     * other already stored. Not atomic ACROSS processes; that window is microseconds wide and costs one extra
     * refresh, never a lost photo (decision record `harden-seam-bug-classes`, D10). The key id is kept: the key is
     * still good, so the next refresh renews with a cheap assertion.
     */
    fun clearTokenIf(expected: BearerToken): ClearRead
}

/** What reading the key id found. */
sealed interface KeyIdRead {
    class Held(val keyId: AttestKeyId) : KeyIdRead

    data object None : KeyIdRead

    data class Unavailable(val detail: String) : KeyIdRead
}

/** What a compare-and-clear did. */
sealed interface ClearRead {
    /** The token was still held, and is gone. */
    data object Cleared : ClearRead

    /** The token held is another one, or none: nothing to drop. */
    data object NotHeld : ClearRead

    data class Unavailable(val detail: String) : ClearRead
}

/** The store of record: two slots of the platform's `SecureStore` ([TrustSlots]). */
class SecureCredentialStore(private val store: SecureStore) : CredentialStore {

    override fun token(): TokenRead = when (val read = store.read(TrustSlots.ATTEST_TOKEN)) {
        is SecureStoreRead.Found -> TokenRead.Held(TokenText.parse(read.value))
        SecureStoreRead.Absent -> TokenRead.None
        is SecureStoreRead.Unavailable -> TokenRead.Unavailable(read.detail)
    }

    override fun setToken(token: BearerToken): WriteOutcome = store.write(TrustSlots.ATTEST_TOKEN, token.secret)

    override fun keyId(): KeyIdRead = when (val read = store.read(TrustSlots.ATTEST_KEY_ID)) {
        is SecureStoreRead.Found -> KeyIdRead.Held(AttestKeyId(read.value))
        SecureStoreRead.Absent -> KeyIdRead.None
        is SecureStoreRead.Unavailable -> KeyIdRead.Unavailable(read.detail)
    }

    override fun setKeyId(keyId: AttestKeyId): WriteOutcome = store.write(TrustSlots.ATTEST_KEY_ID, keyId.value)

    /** Compared against the slot itself. A delete's own failure is not reported: the next rejection clears it again. */
    override fun clearTokenIf(expected: BearerToken): ClearRead = when (val read = token()) {
        is TokenRead.Held -> clear(RejectionVerdict.of(expected, read.token))
        TokenRead.None -> ClearRead.NotHeld
        is TokenRead.Unavailable -> ClearRead.Unavailable(read.detail)
    }

    private fun clear(verdict: RejectionVerdict): ClearRead = when (verdict) {
        RejectionVerdict.Drop -> store.delete(TrustSlots.ATTEST_TOKEN).let { ClearRead.Cleared }
        RejectionVerdict.AlreadyReplaced -> ClearRead.NotHeld
    }
}

/**
 * A [CredentialStore] that keeps the last token it read in memory, so a process does not pay a Keychain read on every
 * request it authenticates.
 *
 * **In-process writes never leave it stale**: every write drops the copy AFTER writing, and a read that raced the write
 * cannot install what it read once the copy has been dropped (the install is a compare-and-set against the exact empty
 * slot the read started from). **Another process's writes are seen at the points that [reread]**: the app at every
 * attestation decision, the extension at every OS invocation, and both on a rejection — [clearTokenIf] compares
 * against the store of record, never the copy. A read the store could not answer is not kept; the next one retries.
 * The key id is not cached: only a refresh reads it.
 */
@OptIn(ExperimentalAtomicApi::class)
class CachedCredentialStore(private val store: CredentialStore) : CredentialStore {

    private sealed interface Slot

    /** What the store answered, absence included. */
    private class Kept(val read: TokenRead) : Slot

    /** Nothing kept. A new instance per drop, so a read that began before a drop cannot install over it. */
    private class Unread : Slot

    private val slot = AtomicReference<Slot>(Unread())

    override fun token(): TokenRead = when (val seen = slot.load()) {
        is Kept -> seen.read
        is Unread -> store.token().also { keep(seen, it) }
    }

    /** Drop the copy, so the next [token] reads the store of record — where another process may have written. */
    fun reread() {
        slot.store(Unread())
    }

    override fun setToken(token: BearerToken): WriteOutcome = store.setToken(token).also { reread() }

    override fun keyId(): KeyIdRead = store.keyId()

    override fun setKeyId(keyId: AttestKeyId): WriteOutcome = store.setKeyId(keyId)

    override fun clearTokenIf(expected: BearerToken): ClearRead {
        reread()
        return store.clearTokenIf(expected).also { reread() }
    }

    private fun keep(seen: Unread, read: TokenRead) = when (read) {
        is TokenRead.Held, TokenRead.None -> slot.compareAndSet(seen, Kept(read))
        is TokenRead.Unavailable -> false
    }
}
