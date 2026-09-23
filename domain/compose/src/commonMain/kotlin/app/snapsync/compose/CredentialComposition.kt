package app.snapsync.compose

import app.snapsync.ports.BackendVerdicts
import kotlinx.coroutines.launch

/**
 * The shared HTTP client's rejection route (capability `device-attestation`, "Only a rejected credential is
 * invalidated, and only that one"; decision record `harden-seam-bug-classes`, D10).
 *
 * The interceptor reports [sentToken] only for a token-bearing `401` from a gated route. The trust feature
 * compare-and-clears it, and only a rejection that actually cleared the stored token asks for a new one — so a
 * burst of refused requests carrying one token costs one refresh, and a late refusal of a token a renewal
 * already replaced costs nothing (B3).
 *
 * The refresh is launched, not awaited: this runs inside the client's own response interceptor, and awaiting a
 * refresh there would re-enter the interceptor from within itself. We are demonstrably online — the backend just
 * answered — so now is the best moment to recover. It lives here rather than in the shell because the rule is the
 * core's, and the world binds the very same function.
 */
suspend fun AppCore.onCredentialRejected(sentToken: String) {
    if (attestation.onRejected(sentToken)) scope.launch { attestation.refresh() }
}

/**
 * The core's answer to every backend verdict, as one object ([BackendVerdicts]) the credential-carrying client is
 * handed whole: the rejection route above and the version gate's two writes.
 */
internal fun AppCore.backendVerdictsOf(): BackendVerdicts = object : BackendVerdicts {
    override suspend fun credentialRejected(sentToken: String) = onCredentialRejected(sentToken)
    override fun versionRefused(minimumVersion: String?) = versionGate.refused(minimumVersion)
    override fun served() = versionGate.served()
}
