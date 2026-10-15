package app.snapsync.feature.support

import app.snapsync.model.EventConfig
import app.snapsync.ports.Clock
import app.snapsync.services.membership.MembershipProjection

/**
 * What a membership feature wrote, read at the port: the membership row's saves and clears, over [store]. [saved] is
 * the membership the last save persisted — `null` until one lands, whatever the store was seeded with.
 */
class ConfigWrites(val store: SharedStore = SharedStore()) {
    val saved: EventConfig? get() = if (store.configSaves > 0) store.persistedConfig() else null
    val saveCount: Int get() = store.configSaves
    val cleared: Boolean get() = store.configCleared

    /** The real membership service over this store, seeded with [initial]. */
    fun service(
        initial: EventConfig?,
        clock: Clock = testClock(),
    ): MembershipProjection = configService(initial, store, clock)
}
