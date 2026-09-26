package app.snapsync.compose

import app.snapsync.feature.upload.WalkMemo
import app.snapsync.feature.upload.WalkMemoUse
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.UploadDiscovery
import co.touchlab.kermit.Logger

/**
 * Whether the app process's walk memo **serves** walks, or only shadows them (capability `photo-sharing`, "An
 * unchanged library is answered from the walk memo").
 *
 * [WalkMemoUse.SERVE]: the memo's soundness rests on a change made outside the process always moving the change
 * token, and that is now measured (`changes/own-work-per-wake`, task 7.4, recorded in that requirement): on the
 * SE2 (iOS 26.6.2) a photo taken with the Camera app moved the token (the next walk was a memo miss and found the
 * new photo), and on the simulator 15/15 external adds, favourites and deletes did, with the app foregrounded or
 * suspended. Before that the memo shipped in [WalkMemoUse.SHADOW] and compared instead of serving.
 *
 * It is a constant rather than a rig or runtime switch on purpose: what it gates is a deletion authority, and a
 * build either relies on the token or it does not. Going back to SHADOW is the one-line revert, should field
 * evidence ever show a stale answer.
 */
val APP_WALK_MEMO_USE: WalkMemoUse = WalkMemoUse.SERVE

/**
 * The app process's upload discovery binding: [walk] behind the walk memo (decision record
 * `changes/own-work-per-wake`, D9). The **one** place a [WalkMemo] is built, and called only from the app's
 * uploader — the upload extension binds its walk bare and walks afresh on every `process()` call (capability
 * `background-upload`; `WalkMemoContainmentTest` pins both sides).
 *
 * [changeToken] is the app's `Gallery` — the extension's `GalleryReader` has none to offer — and
 * [grant] the grant [walk] itself decides its authority by, so the memo keys on the same answer the walk reports
 * under.
 */
fun appUploadDiscovery(
    walk: UploadDiscovery,
    changeToken: LibraryChangeTokenRead,
    grant: PhotoGrantRead,
    log: Logger,
): UploadDiscovery = WalkMemo(walk, changeToken, grant, APP_WALK_MEMO_USE, log)
