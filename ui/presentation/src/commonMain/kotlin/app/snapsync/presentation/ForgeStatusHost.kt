package app.snapsync.presentation

import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.JoinLoad
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.PermissionStatus
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.feature.status.SyncStatusSource
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
 * reached with only permission, config, and sync-status forged. The [cutoffFormatter] arrives from
 * the shell (migration step 9: presentation constructs no system-clock-reading formatter — the
 * `create` shot legitimately renders the wall clock, and the shell binds the real clock/zone).
 *
 * Returns `null` for an unrecognized name, the signal the app shell falls back on to render the live
 * production stack.
 */
/**
 * Whether [state] names a recognized forge state — so the app shell can decide it is forging (and skip
 * booting the live stack from OS lifecycle hooks) without constructing the host.
 */
fun isForgeState(state: String): Boolean = ForgePreset.byId(state) != null

fun forgeStatusHost(state: String, scope: CoroutineScope, cutoffFormatter: CutoffFormatter): StatusContainerHost? {
    val preset = ForgePreset.byId(state) ?: return null
    val host = StatusContainerHost(
        syncSource = ConstSyncStatusSource(preset.sync),
        permission = MutableStateFlow(preset.permission),
        config = MutableStateFlow(preset.config),
        scope = scope,
        // The join gate's details fetch (capability `join-event`), forged to a `Found` so the gate can
        // reach its confirmation surface with no backend. `commitJoin` stays inert by default — a
        // screenshot never confirms a join.
        loadJoinDetails = { JoinLoad.Found(EVENT_NAME, EVENT_START, EVENT_END, EVENT_DELETES) },
        cutoffFormatter = cutoffFormatter,
    )
    // Drive the real join gate by feeding it the very input a scanned QR delivers: the event's own
    // INTERACTIVE invite link (no `autoJoin`, so it opens the confirmation instead of provisioning
    // silently). With config absent and permission granted, `readyOrExplain` lands on `JoinPhase.Ready`
    // — the gate reduces itself; the state is never fabricated.
    if (preset.openInvite) host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
    return host
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
    /**
     * Open the event's own interactive invite link after construction, driving the real join gate
     * to its confirmation surface (the screen a scanned QR opens).
     */
    val openInvite: Boolean = false,
) {
    /** The create/landing screen. Config absent is the create layer's only precondition; permission
     *  and sync are irrelevant behind it. */
    CREATE("create", PermissionStatus.GRANTED, null, ready(completed = 0, total = 0)),

    /** The full-screen "Join event" confirmation a scanned QR opens (capability `join-event`). Config
     *  absent makes it a first join (not a switch), and a granted permission makes `readyOrExplain`
     *  pick `JoinPhase.Ready` — the loaded gate showing the event and its confirm affordance. */
    JOINING("joining", PermissionStatus.GRANTED, null, ready(completed = 0, total = 0), openInvite = true),

    /** Joined and settled — everything shared and received, so both arrows collapse to `InSync`. */
    IN_SYNC("in_sync", PermissionStatus.GRANTED, EVENT, ready(completed = 34, total = 34)),
    ;

    companion object {
        fun byId(id: String): ForgePreset? = entries.firstOrNull { it.id == id }
    }
}

internal const val EVENT_ID = "00000000-0000-4000-8000-000000000000"
internal const val EVENT_NAME = "Anna's Birthday"

/** The event's start, as the join gate's details fetch reports it. */
internal const val EVENT_START = "2026-07-20T18:00:00Z"

/** The event's end (a five-day window), as the join gate's details fetch reports it — the range row's
 *  upper default/ceiling and the "Event end" preset. */
internal const val EVENT_END = "2026-07-25T18:00:00Z"

/** The event's retention deadline, as the details fetch reports it (capability `event-limits`): the
 *  30-day lifetime measured from the event's start. The join gate states it before confirm. */
internal const val EVENT_DELETES = "2026-08-19T18:00:00Z"

/**
 * The canned joined event. Its `startsAt` defaults to `minPhotoDate` (both in the past), so the event
 * has begun (no `NotStarted` clock line) and the membership carries a cutoff exactly as production
 * requires — a config that could arise in production, never one the real reduction never sees.
 */
private val EVENT = EventConfig(
    eventId = EVENT_ID,
    name = EVENT_NAME,
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
