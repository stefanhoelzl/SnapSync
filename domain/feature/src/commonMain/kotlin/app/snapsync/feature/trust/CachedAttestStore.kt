package app.snapsync.feature.trust

import app.snapsync.ports.AttestStore
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * An [AttestStore] that keeps the last token it read in memory (capability `privacy-security`), so a
 * process does not pay a Keychain read (`SecItemCopyMatching`) on every request it authenticates — the
 * credential interceptor reads the token for every HTTP request, and the upload request provider for every
 * upload request it builds.
 *
 * **In-process writes can never leave it stale**: every [setToken], [clearToken] and [clearTokenIf] drops the
 * copy, so the next [token] reads the store of record. The copy is dropped AFTER the write, and a read that
 * raced the write cannot install what it read once the copy has been dropped (the install is a
 * compare-and-set against the exact empty slot the read started from), so the cache holds either nothing or
 * what the store held at some point after the last in-process write.
 *
 * **Another process's writes are seen at the points that re-read.** The app and the upload extension share
 * the token through the Keychain access group, so a renewal in the app, or the extension's compare-and-clear
 * of a rejected token, is invisible to the other's copy until it [reread]s. The owners bound that staleness by
 * re-reading where it matters: the app at every attestation decision ([DeviceAttestation.ensureFresh], which
 * every wake's refresh runs), the extension at every OS invocation, and both on a credential rejection —
 * [clearTokenIf] compares against the store of record, never against the copy. A stale copy between those
 * points is a token the backend minted for this device; at worst it `401`s, which is the retryable failure
 * every path already handles.
 *
 * Absence is cached like any value (a read answering "not attested yet" is as expensive as one answering a
 * token). A read that THROWS is not cached: an unreadable store is retried on the next call, exactly as
 * without the copy. The `keyId` is not cached — it is read only by a refresh, never per request.
 */
@OptIn(ExperimentalAtomicApi::class)
class CachedAttestStore(private val store: AttestStore) : AttestStore {

    private sealed interface Slot

    /** What the store answered, absence included. */
    private class Held(val token: String?) : Slot

    /** Nothing held. A new instance per drop, so a read that began before a drop cannot install over it. */
    private class Unread : Slot

    private val slot = AtomicReference<Slot>(Unread())

    override fun token(): String? {
        val seen = slot.load()
        if (seen is Held) return seen.token
        val read = store.token()
        slot.compareAndSet(seen, Held(read))
        return read
    }

    /** Drop the copy, so the next [token] reads the store of record — where another process may have written. */
    fun reread() {
        slot.store(Unread())
    }

    override fun setToken(token: String) {
        store.setToken(token)
        reread()
    }

    override fun keyId(): String? = store.keyId()

    override fun setKeyId(keyId: String) = store.setKeyId(keyId)

    override fun clearToken() {
        store.clearToken()
        reread()
    }

    /** Compare against the store of record, never the copy: the token a rejection names may be the other process's. */
    override fun clearTokenIf(expected: String): Boolean {
        reread()
        return store.clearTokenIf(expected).also { reread() }
    }
}
