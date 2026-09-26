package app.snapsync.compose

import app.snapsync.model.AssetId
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.model.GalleryAccess
import app.snapsync.ports.AlbumManager
import co.touchlab.kermit.Logger

/**
 * How an uploader answers a denylisted-album lookup that fails (capability `photo-sharing`).
 *
 * The tiers answer differently, deliberately and as before: the app admits on doubt (a failed lookup must never
 * drop a real photo, and the same answer feeds the status total), while the extension lets the failure fail its
 * cycle so the next invocation retries. It is a declared value on the bundle rather than a behaviour hidden in
 * each root's lambda — which is what it used to be, and why the world could answer differently from the app
 * while claiming to be "the SAME policy wrapper".
 */
enum class AlbumLookupFailure { AdmitOnDoubt, FailCycle }

/**
 * The normalized ids of the photos in a messaging/social app's album since [cutoff] — the policy's album
 * denylist — read through the [AlbumManager] port and answered per [onFailure].
 *
 * **Asked only under a full grant**, and this is the ONE place that decides it, for every consumer: the upload
 * cycle on both tiers, the own-device status total, and the join preview. Under any other [grant] the answer is
 * the empty set with no platform call:
 *
 *  - **`LIMITED`** — the album structure is unreadable, so the user-album walk returns no albums and the lookup
 *    already answered the empty set (capability `photo-sharing`, "Under a limited grant the rule is
 *    inert" — measured on device). Asking only paid for an `assetsd` round-trip per cycle, and per status
 *    refresh, to learn nothing. The admitted set is therefore unchanged.
 *  - **`NOT_DETERMINED`** — a `PHAssetCollection` fetch issues a non-preflight TCC request and presents the
 *    photo-permission dialog (measured, simulator, iOS 26.4). The status readers already gated this; the cycle
 *    is withheld before its policy is built, so it never got here.
 *  - **`DENIED`** — nothing to read.
 *
 * The empty set is the honest answer, not a fallback: the denylist is a subtraction and the policy admits on
 * doubt.
 */
internal suspend fun denylistedAlbumMembers(
    manager: AlbumManager,
    cutoff: CaptureCutoff,
    grant: GalleryAccess,
    onFailure: AlbumLookupFailure,
    log: Logger,
): Set<AssetId> = when {
    grant != GalleryAccess.GRANTED -> emptySet()
    onFailure == AlbumLookupFailure.FailCycle -> manager.assetIdsInAlbums(SELECTION_CALIBRATION, cutoff)
    else ->
        runCatchingCancellable { manager.assetIdsInAlbums(SELECTION_CALIBRATION, cutoff) }
            .onFailure { log.w(it) { "denylisted-album lookup failed — admitting on doubt this cycle" } }
            .getOrDefault(emptySet())
}
