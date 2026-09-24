package app.snapsync.compose

import app.snapsync.feature.upload.WalkMemo
import app.snapsync.feature.upload.WalkMemoUse
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.UploadDiscovery
import co.touchlab.kermit.Logger

/**
 * Whether the app process's walk memo **serves** walks, or only shadows them (capability `sync-ledger`, "An
 * unchanged library is answered from the walk memo").
 *
 * [WalkMemoUse.SHADOW] until the external-change device check of `changes/own-work-per-wake` (task 7.4) is
 * recorded in that requirement: the memo's soundness rests on a change made outside the process — a Camera photo,
 * an iCloud sync — always moving the change token, which no probe has yet shown. Until then every walk enumerates,
 * and the memo only compares what it would have served with what the walk returned, logging a disagreement at
 * `Error` (crash reporting sees it) — the same evidence, from the field.
 *
 * **Flipping it** to [WalkMemoUse.SERVE] is a one-line change here, made in the same change that records the
 * device result in the `sync-ledger` spec. It is a constant rather than a rig or runtime switch on purpose: what it
 * gates is a deletion authority, and a build either relies on the token or it does not.
 */
val APP_WALK_MEMO_USE: WalkMemoUse = WalkMemoUse.SHADOW

/**
 * The app process's upload discovery binding: [walk] behind the walk memo (decision record
 * `changes/own-work-per-wake`, D9). The **one** place a [WalkMemo] is built, and called only from the app's
 * uploader — the upload extension binds its walk bare and walks afresh on every `process()` call (capability
 * `ios-photokit-upload`; `WalkMemoContainmentTest` pins both sides).
 *
 * [changeToken] and [grant] are platform reads: the library's change token, and the grant [walk] itself decides
 * its authority by, so the memo keys on the same answer the walk reports under.
 */
fun appUploadDiscovery(
    walk: UploadDiscovery,
    changeToken: LibraryChangeTokenRead,
    grant: PhotoGrantRead,
    log: Logger,
): UploadDiscovery = WalkMemo(walk, changeToken, grant, APP_WALK_MEMO_USE, log)
