package app.snapsync.flow

import app.snapsync.feature.status.ForegroundWatches

/**
 * The **background** OS-callback trigger flow (`docs/architecture.md`, "Rules in features, order in flows"). On
 * backgrounding: stop the foreground status poll — a suspended app cannot act on fresher counts, and the next
 * foreground entry's refresh re-reads them — and the network watch, whose notice nothing renders in the background.
 *
 * It arms nothing. It used to queue the download import-tail backstop `BGTask` here; that task found work in 0 of 108
 * field runs and is deleted — imports left staged are drained by the first unit of any later wake's tail, and by
 * foreground (decision record `changes/own-work-per-wake`, D7).
 *
 * [watches] are `feature/status`'s poll and network watch: their cadence and grace are the feature's rules, and this
 * flow only orders their lifecycle against the OS callback. The entry-point log wrap and the "entering background"
 * banner stay with the background handler.
 */
class Background(
    private val watches: ForegroundWatches,
) {
    suspend fun run() {
        watches.stop()
    }
}
