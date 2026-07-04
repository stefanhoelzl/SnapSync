package app.snapsync.config

import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for the active config: a level-triggered holder whose current value is always
 * available synchronously — the persisted [EventConfig] (the joined `eventId` plus an optional
 * fetched `name`), or `null` when none has been provisioned yet. The setup gate observes this to
 * decide whether the "joined an event" step is satisfied. Like the permission seam, truth arrives
 * here and nowhere else. Combining the `eventId` with the compile-time upload host into the edge
 * upload URL is the consuming composition root's job, not this seam's.
 */
interface ConfigSource {
    val config: StateFlow<EventConfig?>
}

/**
 * The command port for provisioning config: persist [config] and update the [ConfigSource].
 * Saving a config equal (field-for-field, incl. `name`) to the current one is an idempotent no-op;
 * saving a config differing in `eventId` **or** `name` replaces it and emits (a name-only change
 * updates the title without any ledger effect — the switch-reset on an `eventId` change is
 * orchestrated by the provision path, not this seam; see design.md D5/D6). [clear] is the inverse:
 * it removes the persisted config and updates the [ConfigSource] to `null` (an idempotent no-op when
 * none is persisted), and — like [save] — leaves the ledger untouched (the caller orchestrates any
 * ledger reset; see the `leave-event` capability). Implementations typically also implement
 * [ConfigSource] as one platform adapter; consumers depend on each port separately.
 */
interface ConfigStore {
    suspend fun save(config: EventConfig)

    suspend fun clear()
}
