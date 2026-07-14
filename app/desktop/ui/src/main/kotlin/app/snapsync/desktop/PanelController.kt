package app.snapsync.desktop

import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.config.EventConfig
import app.snapsync.eventcreation.CreationFailureReason
import app.snapsync.eventcreation.CreationStatus
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.NoOpEventCreator
import app.snapsync.status.DownloadProgress
import app.snapsync.status.InMemoryDownloadStatusSource
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
    private val configState = MutableStateFlow<EventConfig?>(null)
    private val creationState = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
    private val armedGrants = MutableStateFlow(true)

    val syncSource: SyncStatusSource = object : SyncStatusSource {
        override val status = syncState
    }

    // The joined-layer download line (capability `photo-download`): forge "downloaded X of Y" to
    // review the indicator without a device. 0/0 hides the line.
    val downloadStatusSource = InMemoryDownloadStatusSource()
    fun setDownload(downloaded: Int, total: Int, inFlight: Int = 0) =
        downloadStatusSource.set(DownloadProgress(downloaded, total, inFlight))

    val permissionSource: PermissionStatusSource = object : PermissionStatusSource {
        override val permission = permissionState
    }

    // The config seam + store. The toggle drives the cell directly; the store exists only to
    // satisfy the container's constructor (the harness never decodes a real deeplink).
    val configSource: ConfigSource = object : ConfigSource {
        override val config = configState
    }

    val configStore: ConfigStore = object : ConfigStore {
        override suspend fun save(config: EventConfig) {
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

    /**
     * Forge the joined layer's NOT-STARTED health (capability `sync-status-screen`) by giving the config a
     * **future** `startsAt` — NOT by fabricating the health value.
     *
     * Forging the *input* rather than the *output* is what keeps the harness honest: it exercises the real
     * reduction and its real precedence, so a regression in either shows up here. Compose it with a
     * not-granted permission preset to see `NeedsAccess` correctly outrank the clock line.
     */
    fun showNotStarted() {
        configState.value = NOT_STARTED_CONFIG
        creationState.value = CreationStatus.Idle
    }

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
        // A stand-in config so the "joined an event" step shows connected; never used to upload. The
        // name gives the joined layer a title to review.
        val CANNED_CONFIG = EventConfig(
            eventId = "00000000-0000-4000-8000-000000000000",
            name = "Anna's Birthday",
            // A membership always carries a cutoff (capability `photo-selection-policy`); the forge never uploads.
            minPhotoDate = "2026-01-01T00:00:00Z",
        )

        /**
         * An event that has not begun. Its `startsAt` is far enough out to stay in the future for the life
         * of the project, so the preset needs no clock. `minPhotoDate == startsAt` because that is exactly
         * what the join-time clamp produces pre-start (`max(chosen, startsAt) == startsAt`) — a config that
         * could not arise in production would forge a state the real reduction never sees.
         */
        val NOT_STARTED_CONFIG = EventConfig(
            eventId = "00000000-0000-4000-8000-000000000000",
            name = "New Year's Eve",
            minPhotoDate = "2099-12-31T23:59:59Z",
            startsAt = "2099-12-31T23:59:59Z",
        )
    }
}
