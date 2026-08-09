package app.snapsync.rig

import app.snapsync.compose.AppCore
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
internal suspend fun readState(core: AppCore, host: StatusContainerHost): RigState {
    core.ledgerCounts.refresh()
    val counts = core.ledgerCounts.counts.value
    val progress = core.downloadStatus.progress.value
    val config = host.membership.value
    return RigState(
        ui = host.container.stateFlow.value,
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
        inviteUrl = host.inviteUrl.value,
        eventName = host.eventName.value,
        transientError = host.transientError.value,
    )
}
