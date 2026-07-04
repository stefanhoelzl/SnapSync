package app.snapsync.presentation

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfig
import app.snapsync.config.EventLinkPayload
import app.snapsync.config.decodeConfigUrl
import app.snapsync.config.encodeConfigUrl
import app.snapsync.eventcreation.CreationFailureReason
import app.snapsync.eventcreation.CreationStatus
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.MutableCreationStatusSource
import app.snapsync.eventcreation.NoOpEventCreator
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.DownloadProgress
import app.snapsync.status.DownloadStatusSource
import app.snapsync.status.InMemoryDownloadStatusSource
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class StatusContainerHost(
    syncSource: SyncStatusSource,
    permissionSource: PermissionStatusSource,
    private val requester: PermissionRequester,
    configSource: ConfigSource,
    private val store: ConfigStore,
    scope: CoroutineScope,
    // The create-event seams. Defaults make the create layer inert (always-Idle source, no-op
    // creator) so non-iOS hosts and tests that don't exercise create construct unchanged; iOS injects
    // the same instance the create use-case drives, and the real `EventCreator`.
    creationStatusSource: CreationStatusSource = MutableCreationStatusSource(),
    private val creator: EventCreator = NoOpEventCreator,
    // The leave action, injected as a plain suspend lambda (not the `LeaveEvent` type) so this
    // Compose-free module gains no engine/gallery dependency. Defaults to a no-op so non-iOS hosts
    // and tests construct unchanged and a confirmed leave there is inert; iOS binds it to
    // `LeaveEvent.leave`.
    private val leave: suspend () -> Unit = {},
    // The share action, injected as a plain `(String) -> Unit` lambda (not a named seam type) — the
    // same shape as `leave`. Defaults to a no-op so non-iOS hosts and tests construct unchanged and a
    // share there is inert; iOS binds it to a `UIActivityViewController` presentation.
    private val share: (String) -> Unit = {},
    // Provisioning of a scanned event, injected as a suspend lambda over the decoded `eventId`. The
    // default just persists `EventConfig(eventId)` (name null); iOS binds it to the full provision —
    // switch-reset, `ConfigStore.save`, best-effort `GET /event/:id` name fetch, producer enable, and
    // download reconcile. Keeps this Compose-free module free of HTTP.
    private val provisionScanned: suspend (eventId: String) -> Unit = { store.save(EventConfig(it)) },
    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`).
    // Exposed as a screen-level StateFlow (like `inviteUrl`), NOT folded into `UiState` — it's an
    // independent indicator that doesn't gate upload classification. Defaults to inert (always 0 of 0)
    // so non-iOS hosts/tests construct unchanged; iOS injects the store-backed source.
    downloadSource: DownloadStatusSource = InMemoryDownloadStatusSource(),
) : ContainerHost<UiState, SetupEffect> {

    override val container: Container<UiState, SetupEffect> =
        scope.container(
            // All five seams hold their current truth synchronously, so the first state the
            // screen can ever render derives from real values — never a guess or a placeholder.
            reduceFrom(
                configSource.config.value,
                permissionSource.permission.value,
                syncSource.status.value,
                creationStatusSource.creationStatus.value,
                downloadSource.progress.value,
            ),
        ) {
            intent {
                // The sources combine into a holder; each new value reduces straight to a UI state.
                // The screen reports no relative time, so there is no clock and no periodic re-render —
                // only a real source change re-emits.
                combine(
                    configSource.config,
                    permissionSource.permission,
                    syncSource.status,
                    creationStatusSource.creationStatus,
                    downloadSource.progress,
                ) { config, permission, snapshot, creation, download ->
                    reduceFrom(config, permission, snapshot, creation, download)
                }
                    .collect { ui -> reduce { ui } }
            }
        }

    /**
     * The event's invite deeplink, derived from the persisted config's `eventId` via
     * `encodeConfigUrl(EventLinkPayload(eventId))` — the inverse of the decode run on a scanned QR.
     * One source feeding both the rendered QR and the share action so the two can never drift; `null`
     * whenever no event is configured. Deterministic — the same URL a scanner of the event's QR would
     * receive.
     */
    val inviteUrl: StateFlow<String?> =
        configSource.config
            .map { it?.inviteUrl() }
            .stateIn(scope, SharingStarted.Eagerly, configSource.config.value?.inviteUrl())

    /**
     * The joined event's human-readable name for the screen title (fetched by id after joining, so it
     * may be `null` until a foreground refresh fills it). A screen-level param like [inviteUrl] — it
     * does not enter `UiState`, so the reduction gains no branch for it.
     */
    val eventName: StateFlow<String?> =
        configSource.config
            .map { it?.name }
            .stateIn(scope, SharingStarted.Eagerly, configSource.config.value?.name)

    /**
     * Create a new event with [name] (event-creation-ui). Delegates to the injected [EventCreator]
     * (fire-and-forget): it mints the event and, on success, provisions it through the same path a
     * scanned QR uses (config goes present, the reduction leaves the create layer). Permission is not
     * consulted here — a missing grant surfaces afterward via `PermissionBlocked`. The in-flight and
     * failure outcomes arrive back through `CreationStatusSource`; nothing is reduced here.
     */
    fun onCreateEvent(name: String) = intent { creator.create(name) }

    fun onRequestPermission() = intent { requester.request() }

    fun onOpenSettings() = intent { requester.openSettings() }

    /**
     * Leave the configured event (confirmed in the UI before this fires). Delegates to the injected
     * leave action, which disables the producer and clears the persisted config (the extension resets
     * its own private ledger on its next cycle). The config going `null` makes the reduction fall back
     * to the setup gate — no new `UiState` and no reduction branch here.
     */
    fun onLeaveEvent() = intent { leave() }

    /**
     * Share the event's invite deeplink (the joined-layer share action). Hands the current invite URL
     * to the injected platform share; fire-and-forget — no result is observed, and `UiState` is
     * unaffected (the system share UI is presented over the screen, not part of it). Inert when no
     * event is configured (no URL) or no real share is bound (the no-op default).
     */
    fun onShareInvite() = intent { inviteUrl.value?.let { share(it) } }

    /**
     * A deeplink arrived (forwarded raw from the platform). Decode it with the shared codec; a
     * valid config is persisted via the store (its change arrives back through ConfigSource), an
     * invalid one flashes the transient error without touching persisted state.
     */
    fun onOpenUrl(raw: String) = intent {
        when (val result = decodeConfigUrl(raw)) {
            // A valid scan provisions the event; the injected provision hook owns any switch-reset,
            // best-effort name fetch, and producer enable (composition root). The default just
            // persists the eventId (name null, filled by a later foreground refresh).
            is ConfigDecodeResult.Success -> provisionScanned(result.payload.eventId)
            is ConfigDecodeResult.Failure -> postSideEffect(SetupEffect.InvalidConfigLink)
        }
    }
}

// Config presence is the top rung: without a connected event there is nothing to share, so the create
// layer replaces everything regardless of permission or snapshot. Once config is present the screen is
// ALWAYS the joined layer (name · QR · share · leave) — permission and sync activity are moods of the
// one-line status, never a hero-replacing gate. There is no join-status rung: reconciliation runs in
// the extension and status is read from the completeness listing.
private fun reduceFrom(
    config: EventConfig?,
    permission: PermissionStatus,
    snapshot: SyncStatus,
    creation: CreationStatus,
    download: DownloadProgress,
): UiState {
    if (config == null) {
        return when (creation) {
            CreationStatus.InFlight -> UiState.CreatingEvent
            is CreationStatus.Failed -> UiState.CreateEvent(error = creation.reason.message())
            CreationStatus.Idle -> UiState.CreateEvent()
        }
    }
    val health = when {
        // Missing permission is the sole attention state — the only reason contribution cannot run.
        permission != PermissionStatus.GRANTED -> SyncHealth.NeedsAccess(permission)
        // Joined but persisted state not read yet — a neutral first frame (the joined chrome still shows).
        snapshot is SyncStatus.Loading -> SyncHealth.Loading
        snapshot is SyncStatus.Ready -> syncHealth(snapshot.progress, download)
        else -> SyncHealth.Loading
    }
    return UiState.Joined(health)
}

// Shown tracks completeness (never lies about "everything up/received"); pulse tracks live activity
// (never fakes motion). In sync exactly when both directions are settled.
private fun syncHealth(progress: SyncProgress, download: DownloadProgress): SyncHealth {
    val upload = arrowOf(shown = progress.synced < progress.total, pulsing = progress.pending > 0)
    val downloadArrow = arrowOf(shown = download.downloaded < download.total, pulsing = download.inFlight > 0)
    return if (upload == Arrow.HIDDEN && downloadArrow == Arrow.HIDDEN) {
        SyncHealth.InSync
    } else {
        SyncHealth.Syncing(upload = upload, download = downloadArrow)
    }
}

private fun arrowOf(shown: Boolean, pulsing: Boolean): Arrow =
    if (!shown) Arrow.HIDDEN else if (pulsing) Arrow.PULSING else Arrow.STATIC

// Derive the invite deeplink from the persisted config's eventId (the wire payload is eventId-only).
private fun EventConfig.inviteUrl(): String = encodeConfigUrl(EventLinkPayload(eventId))

// The inline create-error copy, formatted in presentation (UiState carries final display strings).
private fun CreationFailureReason.message(): String = when (this) {
    CreationFailureReason.INVALID_NAME -> "That name isn't valid."
    CreationFailureReason.SERVER -> "Couldn't reach the server."
}
