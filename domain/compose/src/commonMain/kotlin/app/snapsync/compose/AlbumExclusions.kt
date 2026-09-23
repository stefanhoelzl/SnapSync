package app.snapsync.compose

import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.DENYLISTED_ALBUM_TITLES
import app.snapsync.ports.AlbumManager
import co.touchlab.kermit.Logger

/**
 * How an uploader answers a denylisted-album lookup that fails (capability `photo-selection-policy`).
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
 */
internal suspend fun denylistedAlbumMembers(
    manager: AlbumManager,
    cutoff: CaptureCutoff,
    onFailure: AlbumLookupFailure,
    log: Logger,
): Set<String> = when (onFailure) {
    AlbumLookupFailure.FailCycle -> manager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff.at.iso)
    AlbumLookupFailure.AdmitOnDoubt ->
        runCatchingCancellable { manager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff.at.iso) }
            .onFailure { log.w(it) { "denylisted-album lookup failed — admitting on doubt this cycle" } }
            .getOrDefault(emptySet())
}
