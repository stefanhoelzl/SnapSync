package app.snapsync.presentation

import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfig
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The forge factory behind the `SNAPSYNC_FORGE_STATE` developer launch trigger (capability
 * `ios-app-shell`): map a recognized state name to a [StatusContainerHost] assembled over **forged
 * sources**, for capturing marketing screenshots of the shared `StatusScreen` in a simulator with no
 * backend, attestation, or photo-library access.
 *
 * Like the desktop forge harness's `PanelController`, each preset forges the container's **inputs**
 * (permission, config, sync-status) and lets the real reduction produce the frame — the screen still
 * renders the live `container.stateFlow`, never a static `UiState`. Forging inputs, not outputs, is
 * what keeps a shot honest: a preset can only reach a frame the production reduction can itself emit,
 * so an impossible state is unrepresentable here (the App-Store-honesty constraint).
 *
 * The container's benign defaults carry the rest: `AlwaysAttested` clears the attestation gate and
 * `InMemoryDownloadStatusSource` (0/0) hides the download arm — so a settled `Joined(InSync)` is
 * reached with only permission, config, and sync-status forged.
 *
 * Returns `null` for an unrecognized name, the signal the app shell falls back on to render the live
 * production stack.
 */
fun forgeStatusHost(state: String, scope: CoroutineScope): StatusContainerHost? {
    val preset = ForgePreset.byId(state) ?: return null
    return StatusContainerHost(
        syncSource = ConstSyncStatusSource(preset.sync),
        permissionSource = ConstPermissionStatusSource(preset.permission),
        requester = NoOpPermissionRequester,
        configSource = ConstConfigSource(preset.config),
        store = NoOpConfigStore,
        scope = scope,
    )
}

/**
 * The recognized forge states and the inputs each forges. Adding a marketing screen is adding an
 * entry here; the state name is the value passed as `SNAPSYNC_FORGE_STATE`.
 */
private enum class ForgePreset(
    val id: String,
    val permission: PermissionStatus,
    val config: EventConfig?,
    val sync: SyncStatus,
) {
    /** The create/landing screen. Config absent is the create layer's only precondition; permission
     *  and sync are irrelevant behind it. */
    CREATE("create", PermissionStatus.GRANTED, null, ready(completed = 0, total = 0)),

    /** Joined with the invite QR up and photos queued to share — a **STATIC** (deliberately
     *  non-pulsing, so capture is deterministic) upload arrow: `synced < total` with nothing in
     *  flight → "Synchronization pending…". */
    JOINING("joining", PermissionStatus.GRANTED, EVENT, ready(completed = 12, total = 47)),

    /** Joined and settled — everything shared and received, so both arrows collapse to `InSync`. */
    IN_SYNC("in_sync", PermissionStatus.GRANTED, EVENT, ready(completed = 34, total = 34)),
    ;

    companion object {
        fun byId(id: String): ForgePreset? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The canned joined event. Its `startsAt` defaults to `minPhotoDate` (both in the past), so the event
 * has begun (no `NotStarted` clock line) and the membership carries a cutoff exactly as production
 * requires — a config that could arise in production, never one the real reduction never sees.
 */
private val EVENT = EventConfig(
    eventId = "00000000-0000-4000-8000-000000000000",
    name = "Anna's Birthday",
    minPhotoDate = "2026-01-01T00:00:00Z",
)

private fun ready(completed: Int, total: Int, pending: Int = 0): SyncStatus =
    SyncStatus.Ready(
        SyncProgress(
            pending = pending, completed = completed, total = total, failed = 0,
            active = true, estimatedRemaining = null,
        ),
    )

private class ConstSyncStatusSource(status: SyncStatus) : SyncStatusSource {
    override val status = MutableStateFlow(status)
}

private class ConstPermissionStatusSource(permission: PermissionStatus) : PermissionStatusSource {
    override val permission = MutableStateFlow(permission)
}

private class ConstConfigSource(config: EventConfig?) : ConfigSource {
    override val config = MutableStateFlow(config)
}

private object NoOpPermissionRequester : PermissionRequester {
    override fun request() {}
    override fun openSettings() {}
}

private object NoOpConfigStore : ConfigStore {
    override suspend fun save(config: EventConfig) {}
    override suspend fun clear() {}
}
