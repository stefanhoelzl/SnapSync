package app.snapsync.feature.push

import app.snapsync.model.ApnsPushToken
import app.snapsync.ports.PushTokenPublisher
import app.snapsync.ports.PushTokenSource

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Registers the device's APNs token with the backend (capability `push-registration`). On each token —
 * launch delivery and every rotation — it publishes the token through the [publisher] port, whose adapter
 * owns the address and the body. **No event id** (the token is device-scoped, event-independent). A failed
 * publish is absorbed (logged) and retried on the next token, so registration never blocks
 * join/upload/download. Idempotent: re-publishing the same token overwrites an identical config
 * (last-write-wins at the endpoint), so repeated launches with an unchanged token are harmless.
 */
class PushRegistration(
    private val publisher: PushTokenPublisher,
    private val log: Logger = Logger.withTag("PushRegistration"),
) {
    /** Publish [token] now. Absorbs any failure (never throws to the caller). */
    suspend fun register(token: ApnsPushToken) {
        publisher.publish(token)
            .onSuccess { log.i { "push token registered" } }
            .onFailure { log.w(it) { "push registration failed (will retry on next token)" } }
    }

    /**
     * Register on every delivered/rotated token — and again whenever [credentialChanged] fires. Suspends
     * for the caller scope's lifetime (launched once from the composition root).
     *
     * **[credentialChanged] is not an optimization; without it a failed registration is permanent.** The
     * OS delivers an APNs token *once* and does not re-deliver it, so a `PUT` refused because the device
     * had no valid attestation token yet (a fresh install races attestation) would never be retried — the
     * device would sit permanently unregistered, receiving no silent pushes, no download wakes, and none
     * of the wake-driven token renewals. Re-registering when a new credential arrives closes exactly that
     * hole. The `PUT` is idempotent (last-write-wins), so a redundant one costs nothing.
     */
    suspend fun run(source: PushTokenSource, credentialChanged: Flow<Unit> = emptyFlow()) {
        merge(
            source.token.filterNotNull().map { },
            credentialChanged,
        ).collect {
            val token = source.token.value ?: return@collect
            register(ApnsPushToken(token, source.env))
        }
    }
}
