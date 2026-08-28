package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.presentation.Layer
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.UiState
import kotlinx.serialization.Serializable

/**
 * `/state`'s body: the **real** reduced [UiState] plus the read-models the container exposes beside it.
 *
 * Every field is a direct `.value` read of a flow the screen itself observes, or a direct read through
 * the same source the status screen uses. Aggregation, never transformation — the encoder for [ui] is
 * compiler-generated from the declaration in `:ui:presentation`, so there is no second rendering of the
 * state that could disagree with the screen. That is the property that lets this module carry no tests.
 */
@Serializable
data class RigState(
    val ui: UiState,
    val ready: Readiness,
    val ledger: LedgerView,
    val download: DownloadView,
    val permission: String,
    val inviteUrl: String?,
    val eventName: String?,
    val transientError: String?,
    /**
     * What this build IS — composition mode, upload tier, baked upload base. Moved here from `/health`,
     * which now answers only "is the channel up": a caller reading state should not need a second request
     * to learn which backend the build it is reading is pointed at.
     */
    val build: Map<String, String>,
    /** What the OS says about the upload-job extension. See [OsExtensionView]. */
    val osExtension: OsExtensionView,
)

/**
 * The OS's own answer to "is the upload-job extension registered" — reported as **what the OS reports**,
 * never as what the OS holds.
 *
 * Three-valued, and both of the reasons are measured rather than defensive.
 *
 * `isUploadJobExtensionEnabled` is a **26.1 selector** and the app deploys to min iOS 18, so calling it
 * unconditionally traps as an unrecognized selector. [enabled] is therefore `null` — rendered as
 * `notApplicable` — wherever the OS has no such selector. A bare `false` there would state "not registered"
 * about an OS on which registration could never occur.
 *
 * And the read is **grant-dependent**: measured on an SE2 (iOS 26.6), the OS reported `false` under
 * `NOT_DETERMINED` photo access for a record that was live in that same install and had survived a
 * delete-and-reinstall, then `true` for that same record once access was granted — one install, one
 * variable, minutes apart. So a `false` collapses "there is no record" with "I am not permitted to see one",
 * and [grantDependent] marks the answers where that collapse is live rather than leaving a reader to join
 * this field with `permission` themselves.
 *
 * ⏰ Two cells are unmeasured: `LIMITED` access, and a record left by a differently-signed build.
 */
@Serializable
data class OsExtensionView(
    val enabled: Boolean?,
    val notApplicableReason: String?,
    val grantDependent: Boolean,
)

/**
 * Has the membership resolved yet, and to what.
 *
 * This exists because of a measured ordering trap: `onForeground` fires **before** the persisted
 * membership is read back (17:53:25.23 against 17:53:27.45 in one measured run). A caller that triggers
 * and then asserts would read a membership-less state and conclude nothing happened. Stating the fact
 * turns a `sleep` into a poll on a condition.
 */
@Serializable
data class Readiness(
    val configResolved: Boolean,
    val eventId: String?,
    val direction: String?,
    val minPhotoDate: String?,
    val maxPhotoDate: String?,
)

/**
 * Upload-ledger aggregates — the counts [UiState] deliberately omits ("the screen answers *is it
 * healthy?*, not *how many of N*"), and the only assertion that can prove bytes actually landed.
 */
@Serializable
data class LedgerView(val completed: Int, val pending: Int)

/** Foreign-photo download progress, the container's screen-level indicator. */
@Serializable
data class DownloadView(val downloaded: Int, val total: Int, val inFlight: Int)

/**
 * Read the whole snapshot.
 *
 * Suspends only because the ledger read does: [AppCore.ledgerCounts] is a `ReadingLedgerCountsSource`,
 * whose `refresh()` performs the one consistent `aggregates()` read the status source performs — the
 * same seam, not a second query. Everything else is a flow's current value.
 */
internal suspend fun readState(core: AppCore, host: StatusContainerHost, hooks: RigHooks): RigState {
    core.ledgerCounts.refresh()
    val counts = core.ledgerCounts.counts.value
    val progress = core.downloadStatus.progress.value
    // The membership, the invite URL and the inline create error all live INSIDE the UI state now
    // (capability `sync-status-screen`), so the rig reports exactly what the screen is rendering rather
    // than a parallel set of read-models that could disagree with it.
    val ui = host.container.stateFlow.value
    val joined = ui as? Layer.Joined
    val config = joined?.membership
    return RigState(
        ui = ui,
        ready = Readiness(
            configResolved = config != null,
            eventId = config?.eventId,
            direction = config?.direction?.name,
            minPhotoDate = config?.minPhotoDate?.at?.iso,
            maxPhotoDate = config?.maxPhotoDate?.at?.iso,
        ),
        ledger = LedgerView(completed = counts.completed, pending = counts.pending),
        download = DownloadView(
            downloaded = progress.downloaded,
            total = progress.total,
            inFlight = progress.inFlight,
        ),
        permission = core.photoPermission.value.name,
        inviteUrl = joined?.inviteUrl,
        eventName = config?.name,
        transientError = (ui as? Layer.CreateEvent)?.error,
        build = hooks.buildFacts(),
        // The grant is read from the same value reported above, so the two cannot disagree within one
        // snapshot — which matters precisely because a `false` is only interpretable alongside it.
        osExtension = hooks.readOsExtension(core.photoPermission.value.name),
    )
}
