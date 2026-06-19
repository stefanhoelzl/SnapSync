package app.snapsync.config

import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for the active config payload: a level-triggered holder whose current value is
 * always available synchronously — the persisted [S3ConfigPayload], or `null` when none has been
 * provisioned yet. The setup gate observes this to decide whether the "storage" step is satisfied.
 * Like the permission seam, truth arrives here and nowhere else. Combining the payload with the
 * compile-time upload host into the provider's `S3Config` is the consuming composition root's job,
 * not this seam's.
 */
interface ConfigSource {
    val config: StateFlow<S3ConfigPayload?>
}

/**
 * The command port for provisioning config: persist [config] and update the [ConfigSource].
 * Saving a payload equal (field-for-field) to the current one is an idempotent no-op; saving a
 * different one replaces it silently (the ledger is deliberately left untouched — see design.md
 * D6). Implementations typically also implement [ConfigSource] as one platform adapter; consumers
 * depend on each port separately.
 */
interface ConfigStore {
    suspend fun save(config: S3ConfigPayload)
}
