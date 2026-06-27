package app.snapsync.config

import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for the active config payload: a level-triggered holder whose current value is
 * always available synchronously — the persisted [EventConfigPayload], or `null` when none has been
 * provisioned yet. The setup gate observes this to decide whether the "joined an event" step is
 * satisfied. Like the permission seam, truth arrives here and nowhere else. Combining the `eventId`
 * with the compile-time upload host and the device id into the edge upload URL is the consuming
 * composition root's job, not this seam's.
 */
interface ConfigSource {
    val config: StateFlow<EventConfigPayload?>
}

/**
 * The command port for provisioning config: persist [config] and update the [ConfigSource].
 * Saving a payload equal (field-for-field) to the current one is an idempotent no-op; saving a
 * different one replaces it silently (the ledger is deliberately left untouched — see design.md
 * D6). [clear] is the inverse: it removes the persisted payload and updates the [ConfigSource] to
 * `null` (an idempotent no-op when none is persisted), and — like [save] — leaves the ledger
 * untouched (the caller orchestrates any ledger reset; see the `leave-event` capability).
 * Implementations typically also implement [ConfigSource] as one platform adapter; consumers
 * depend on each port separately.
 */
interface ConfigStore {
    suspend fun save(config: EventConfigPayload)

    suspend fun clear()
}
