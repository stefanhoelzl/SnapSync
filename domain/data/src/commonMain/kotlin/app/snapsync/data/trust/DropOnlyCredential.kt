package app.snapsync.data.trust

import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.TokenRead
import app.snapsync.domain.trust.BearerToken
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

/**
 * The upload extension's credential: it reads the token the app stored in the shared store, and when the backend
 * rejects one it DROPS it — and nothing else. The extension cannot attest, so it cannot recover; dropping the rejected
 * token is still what heals the device: the app's next wake finds no token and obtains one, where a rejected-but-
 * unexpired token would have looked fine forever. It never offers a token to retry with, so the extension never
 * retries a rejected call: its cycle defers and the OS invokes it again.
 */
class DropOnlyCredential(private val store: CachedCredentialStore) : Credential {
    private val log = Log("DropOnlyCredential")

    override fun token(): TokenRead = store.token()

    override suspend fun rejected(sent: BearerToken): TokenRead {
        dropped(store.clearTokenIf(sent))
        return TokenRead.None
    }

    /** Nothing: this process cannot attest, so a missing token stays missing. */
    override suspend fun missing(): TokenRead = TokenRead.None

    private suspend fun dropped(cleared: ClearRead) = when (cleared) {
        ClearRead.Cleared, ClearRead.NotHeld -> Unit
        is ClearRead.Unavailable -> log.w(
            "the rejected token could not be compared and cleared",
            "detail" of cleared.detail,
        )
    }
}
