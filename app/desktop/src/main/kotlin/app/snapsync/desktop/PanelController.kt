package app.snapsync.desktop

import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.config.EventConfigPayload
import app.snapsync.eventcreation.CreationFailureReason
import app.snapsync.eventcreation.CreationStatus
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.NoOpEventCreator
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single mutation path for the harness's stand-in state: every display-override
 * button goes through a named method here, never an inline mutation in a composable.
 * Holds three cells (permission, config, sync) plus the armed request outcome, and implements
 * the stand-in sources and the fake [PermissionRequester].
 */
class PanelController {
    // The harness knows its truth synchronously, so it seeds Ready and never shows Loading.
    private val syncState = MutableStateFlow<SyncStatus>(
        SyncStatus.Ready(
            SyncProgress(
                pending = 0, completed = 0, total = 0, failed = 0,
                active = true, estimatedRemaining = null,
            ),
        ),
    )
    private val permissionState = MutableStateFlow(PermissionStatus.NOT_DETERMINED)
    private val configState = MutableStateFlow<EventConfigPayload?>(null)
    private val creationState = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
    private val armedGrants = MutableStateFlow(true)

    val syncSource: SyncStatusSource = object : SyncStatusSource {
        override val status = syncState
    }

    val permissionSource: PermissionStatusSource = object : PermissionStatusSource {
        override val permission = permissionState
    }

    // The config seam + store. The toggle drives the cell directly; the store exists only to
    // satisfy the container's constructor (the harness never decodes a real deeplink).
    val configSource: ConfigSource = object : ConfigSource {
        override val config = configState
    }

    val configStore: ConfigStore = object : ConfigStore {
        override suspend fun save(config: EventConfigPayload) {
            configState.value = config
        }

        override suspend fun clear() {
            configState.value = null
        }
    }

    // The create-status cell, injected so the create presets can forge the create layer (shown only
    // while config is absent). The creator is a no-op — the harness forges states, it never mints.
    val creationStatusSource: CreationStatusSource = object : CreationStatusSource {
        override val creationStatus = creationState
    }
    val creator: EventCreator = NoOpEventCreator

    /** The current config cell, so the toggle can reflect whether storage is connected. */
    val currentConfig = configState.asStateFlow()

    fun setConfigPresent(present: Boolean) {
        configState.value = if (present) CANNED_CONFIG else null
    }

    val requester: PermissionRequester = object : PermissionRequester {
        override fun request() {
            permissionState.value =
                if (armedGrants.value) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }

        override fun openSettings() {
            // The fake can't open anything: play "the user in Settings" with the
            // permission presets instead.
            println("openSettings() — simulate the Settings visit via the Permission presets")
        }
    }

    /** What the next gate-driven request() resolves to. */
    val armedRequestGrants = armedGrants.asStateFlow()

    fun armNextRequest(grants: Boolean) {
        armedGrants.value = grants
    }

    // Permission presets write the permission cell only: the sync cell is invisible behind
    // the gate, and an untouched forged sync state is what makes the revoked-and-restored
    // walk possible.

    fun showPermissionNotDetermined() {
        permissionState.value = PermissionStatus.NOT_DETERMINED
    }

    fun showPermissionDenied() {
        permissionState.value = PermissionStatus.DENIED
    }

    fun showPermissionGranted() {
        permissionState.value = PermissionStatus.GRANTED
    }

    // PermissionBlocked presets: an event is connected (config present) but permission is not
    // granted, so the status screen hosts the permission affordance instead of the sync hero. These
    // force config present (unlike the bare permission presets above) to land on PermissionBlocked
    // in one click — the not-determined priming and the revoked/denied settings path.
    fun showPermissionBlockedNotDetermined() {
        configState.value = CANNED_CONFIG
        permissionState.value = PermissionStatus.NOT_DETERMINED
    }

    fun showPermissionBlockedDenied() {
        configState.value = CANNED_CONFIG
        permissionState.value = PermissionStatus.DENIED
    }

    // Loading has no SyncProgress payload, so it bypasses forgeSync; like the others it forces
    // both gates (Granted + config present), since the reducer only surfaces Loading once both pass.
    fun showLoading() {
        permissionState.value = PermissionStatus.GRANTED
        configState.value = CANNED_CONFIG
        syncState.value = SyncStatus.Loading
    }

    // Create presets: force config absent (the create layer's only precondition), then forge the
    // creation cell. Permission is irrelevant to the create layer, so it is left untouched.
    fun showCreateInput() = forgeCreate(CreationStatus.Idle)

    fun showCreating() = forgeCreate(CreationStatus.InFlight)

    fun showCreateFailedInvalidName() =
        forgeCreate(CreationStatus.Failed(CreationFailureReason.INVALID_NAME))

    fun showCreateFailedServer() =
        forgeCreate(CreationStatus.Failed(CreationFailureReason.SERVER))

    private fun forgeCreate(status: CreationStatus) {
        configState.value = null
        creationState.value = status
    }

    fun showNothingToSync() {
        forgeSync(progress(completed = 0, total = 0))
    }

    // In progress with a REAL in-flight count: 12 synced, 8 uploading now, the remaining 35 awaiting
    // discovery — so `pending` (8) is distinct from `total − synced` (35), the whole point of the
    // ledger-peek. Nudge it with [adjustInFlightBy].
    fun showInProgress() {
        forgeSync(progress(pending = 8, completed = 12, total = 47))
    }

    fun showComplete() {
        forgeSync(progress(completed = 34, total = 34))
    }

    // A deleted-but-unpruned photo: completed overshoots the live total; the screen clamps to N.
    fun showOvershoot() {
        forgeSync(progress(completed = 6, total = 5))
    }

    // The settable gallery size (N): nudge the live total of whatever sync state is showing, so the
    // discovery-lag (N > n) → completed (N == n) → overshoot (N < n) walk is forgeable by hand.
    fun adjustGalleryBy(delta: Int) {
        val current = (syncState.value as? SyncStatus.Ready)?.progress ?: return
        forgeSync(current.copy(total = (current.total + delta).coerceAtLeast(0)))
    }

    // The in-flight ("uploading now") count: nudge the forged `pending` of whatever sync state is
    // showing, so the "{n} in progress" caption (hidden at 0) is reviewable at every value, independent
    // of remaining (`total − completed`).
    fun adjustInFlightBy(delta: Int) {
        val current = (syncState.value as? SyncStatus.Ready)?.progress ?: return
        forgeSync(current.copy(pending = (current.pending + delta).coerceAtLeast(0)))
    }

    // A sync preset's intent is "show me this screen" — impossible while the setup gate is up, so
    // it forces both preconditions (permission granted AND config present).
    private fun forgeSync(status: SyncProgress) {
        permissionState.value = PermissionStatus.GRANTED
        configState.value = CANNED_CONFIG
        syncState.value = SyncStatus.Ready(status)
    }

    private fun progress(pending: Int = 0, completed: Int, total: Int) = SyncProgress(
        pending = pending, completed = completed, total = total, failed = 0,
        active = true, estimatedRemaining = null,
    )

    private companion object {
        // A stand-in config so the "joined an event" step shows connected; never used to upload.
        val CANNED_CONFIG = EventConfigPayload(
            eventId = "00000000-0000-4000-8000-000000000000",
        )
    }
}
