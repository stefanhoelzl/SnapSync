package app.snapsync.flow

import app.snapsync.feature.status.StatusCountsPoller

/**
 * The **background** OS-callback trigger flow (spec `module-architecture`, "Rules in features, order
 * in flows"). On backgrounding: stop the foreground status poll — a suspended app cannot act on
 * fresher counts, and the next foreground entry's refresh re-reads them.
 *
 * It arms nothing. It used to queue the download import-tail backstop `BGTask` here; that task found
 * work in 0 of 108 field runs and is deleted — imports left staged are drained by the first unit of any
 * later wake's tail, and by foreground (capability `photo-download`; decision record
 * `changes/own-work-per-wake`, D7).
 *
 * [statusPoller] is `feature/status`'s poll: its cadence is the feature's rule, and this flow only
 * orders its lifecycle against the OS callback. The entry-point log wrap and the "entering background"
 * banner stay with the inbound port's implementation.
 */
class Background(
    private val statusPoller: StatusCountsPoller,
) {
    suspend fun run() {
        statusPoller.stop()
    }
}
