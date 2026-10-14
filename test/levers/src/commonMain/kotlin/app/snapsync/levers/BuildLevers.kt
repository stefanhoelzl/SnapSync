package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.Serializable

/**
 * **The build's development controls** — what a rig build lets the channel choose of how it behaves, where a shipped
 * build has one fixed answer. The build's own, so every host honours them.
 */
@Rpc
interface DevControlsLevers {
    /**
     * Each uploader on or off — both run on a phone, and a test about one switches the other off — and the uploads
     * reconciled with the switch at once.
     */
    suspend fun pin(app: Boolean, extension: Boolean)

    /** Both uploaders, as a shipped build runs them, reconciled at once. */
    suspend fun unpin()

    /** How the build answers an invite link's dev/test hints: [honoured] `false` plays a shipped build, which ignores them. */
    suspend fun inviteLinkHints(honoured: Boolean)

    /**
     * Whether an event this device creates is encrypted: [on] `false` creates the plain events a shipped build creates
     * until encrypted creation is re-enabled (`PENDING_CLEANUPS.md`).
     */
    suspend fun encryptNewEvents(on: Boolean)

    /** Voids this device's durable sync state, as the development reset does; returns once it is void. */
    suspend fun reset()

    /** The controls as they stand now. */
    suspend fun controls(): DevControlsView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<DevControlsLevers>(
        DevControlsLevers::class,
        "dev-controls",
        emptySet(),
        { serviceDescriptorOf() },
    )
}

/** The development controls as they stand: which uploaders run, and how the build answers hints and creates events. */
@Serializable
data class DevControlsView(
    val appUploads: Boolean,
    val extensionUploads: Boolean,
    /** Whether the channel pinned the uploaders; unpinned, both run, as on a shipped build. */
    val pinned: Boolean,
    val inviteLinkHintsHonoured: Boolean,
    val encryptsNewEvents: Boolean,
)

/**
 * **The build itself**, where the channel may play another: the marketing version it declares on every request, and the
 * store page it names.
 */
@Rpc
interface BuildLevers {
    /** The build declares [version] on every request from now on — an old build, or an update. */
    suspend fun declare(version: String)

    /** The version the build declares now. */
    suspend fun declared(): String?

    /**
     * The build names the store called [name] at [url] — a build listed in another store — or, both omitted, carries
     * no store page at all, as an Android build does until its listing is public. A build's constant, so the app reads
     * it at its next launch (`host.relaunch`). Honoured only where the build is a mock: a device's build is its
     * bundle's.
     */
    suspend fun store(name: String? = null, url: String? = null)

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<BuildLevers>(
        BuildLevers::class,
        "build",
        setOf(LeverNeed.VERSION_SETTABLE),
        { serviceDescriptorOf() },
    )
}
