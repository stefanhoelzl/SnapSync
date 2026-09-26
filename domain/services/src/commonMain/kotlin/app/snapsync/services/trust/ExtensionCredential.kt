package app.snapsync.services.trust

import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.backend.Credential
import co.touchlab.kermit.Logger

/**
 * The upload extension's [Credential] (capability `privacy-security`): it reads the token the app stored in the
 * shared store, and when the backend rejects one it DROPS it — and nothing else.
 *
 * The extension cannot attest (App Attest is unavailable in an app extension — measured), so it cannot recover on
 * its own. Dropping the rejected token is still what heals the device: the app's next wake finds no token, which its
 * staleness check reads as "obtain one", while a rejected-but-unexpired token would have looked perfectly fine
 * forever. Only if the shared item still holds the token that was refused — the app may have renewed it meanwhile.
 *
 * It never offers a token to retry with, so the extension never retries a rejected call: its cycle defers and the
 * OS invokes it again.
 */
class ExtensionCredential(
    private val store: CachedAttestStore,
    private val log: Logger = Logger.withTag("ExtensionCredential"),
) : Credential {

    /** The token the app last stored, or `null` when it cannot be read — the call then goes out unauthenticated. */
    override fun token(): String? = runCatchingCancellable { store.token() }
        .onFailure { log.w(it) { "attest token unreadable — proceeding unauthenticated (expect 401)" } }
        .getOrNull()

    override suspend fun rejected(sent: String): String? {
        runCatchingCancellable { store.clearTokenIf(sent) }
            .onFailure { log.w(it) { "the rejected token could not be compared and cleared — the app's next wake retries" } }
        return null
    }
}
