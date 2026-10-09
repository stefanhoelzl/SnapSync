package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.model.Layer
import app.snapsync.model.UiState
import app.snapsync.presentation.StatusContainerHost
import kotlinx.serialization.Serializable

/**
 * `/state`'s body: the **real** reduced [UiState] plus the one read-model the screen does not carry.
 *
 * Every field is a direct `.value` read of a flow the screen itself observes, or a fact of the build. Aggregation,
 * never transformation — the encoder for [ui] is compiler-generated from the declaration in `:domain:presentation`, so
 * there is no second rendering of the state that could disagree with the screen. That is the property that lets this
 * module carry no tests.
 *
 * **Narrowed in 11g2** (`docs/testing.md`, "The control channel"): the ledger counts, the permission, the invite URL,
 * the event name and the create error are gone. The last four are inside [ui], so a second copy of them could only
 * disagree with it; the ledger is the app's own bookkeeping, which no assertion may read (bytes that landed are read
 * from the backend instead).
 */
@Serializable
data class RigState(
    val ui: UiState,
    val ready: Readiness,
    val download: DownloadView,
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
 * this field with the grant themselves.
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

/** Foreign-photo download progress, the container's screen-level indicator. */
@Serializable
data class DownloadView(val downloaded: Int, val total: Int, val inFlight: Int)

/**
 * Read the whole snapshot: every field a flow's current value, or a fact of the build.
 *
 * Reading it is the screen's pull, as the phone's foreground poll pulls: the ledger-count read-model is refreshed first
 * (`ReadingLedgerCountsSource.refresh()`, the one consistent read the status source performs), so the reduced [UiState]
 * the next read returns reflects what the uploads did. The counts themselves are not reported.
 */
internal suspend fun readState(core: AppCore, host: StatusContainerHost, hooks: RigHooks): RigState {
    core.ledgerCounts.refresh()
    val progress = core.downloadStatusSource.progress.value
    // The membership, the invite URL and the inline create error all live INSIDE the UI state now,
    // so the rig reports exactly what the screen is rendering rather than a parallel
    // set of read-models that could disagree with it.
    val ui = host.container.stateFlow.value
    // `ui.layer`, never `ui`: the state wraps its layer, and a cast of the WRAPPER to a layer type is always null —
    // which the compiler only warns about. That is exactly how this read reported every joined device as
    // unresolved, with no invite URL and no name, until the JVM host's tests read it back.
    val joined = ui.layer as? Layer.Joined
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
        download = DownloadView(
            downloaded = progress.downloaded,
            total = progress.total,
            inFlight = progress.inFlight,
        ),
        build = hooks.buildFacts(),
        // The OS's answer is grant-dependent, so the grant it was read under travels with it (see [OsExtensionView]).
        osExtension = hooks.readOsExtension(core.photoPermission.value.name),
    )
}
