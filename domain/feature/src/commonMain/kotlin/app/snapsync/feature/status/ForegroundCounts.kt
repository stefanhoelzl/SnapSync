package app.snapsync.feature.status

import app.snapsync.services.foreground.AppForeground

/**
 * The counts a wake's steps refresh as they go, so the screen a person is looking at moves with them — only while the
 * app is in the foreground (design D11): in the background nobody sees them, and the next foreground entry re-reads
 * them anyway.
 */
class ForegroundCounts(private val foreground: AppForeground, private val counts: ReadingLedgerCountsSource) {
    suspend fun refresh() {
        if (foreground.isForeground) counts.refresh()
    }
}
