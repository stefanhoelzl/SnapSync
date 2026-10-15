package app.snapsync.levers

import app.snapsync.ui.state.UiState
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
 * `LIMITED` access collapses the same way: measured on the SE2 (iOS 27.0.1), a record made under a full grant read
 * `false` once access was narrowed, and `true` again once it was widened, with no write in between. ⏰ One cell is
 * unmeasured: a record left by a differently-signed build.
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
