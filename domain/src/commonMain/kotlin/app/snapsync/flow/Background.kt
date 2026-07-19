package app.snapsync.flow

import app.snapsync.feature.status.LedgerCountsPoller

/**
 * The **background** OS-callback trigger flow (spec `module-architecture`, "Rules in features, order
 * in flows"). On backgrounding: stop the foreground status poll (a suspended app cannot act on
 * fresher counts; the next foreground entry's refresh is the backstop) and queue the download
 * import-tail backstop so any staged-but-unimported foreign assets get imported at the next
 * idle/charging window even if no further download wakes the app (capability `photo-download`, 5.4).
 *
 * Ordering of one feature stop and one platform effect: [statusPoller] is `feature/status`'s poll
 * (its cadence is the feature's rule; this flow only orders its lifecycle against the OS callback),
 * [scheduleBackstop] is irreducibly the shell's (a `BGTaskScheduler` submit), arriving as a
 * `compose/`-built effect lambda. The forge guard, the entry-point log wrap, and the "entering
 * background" banner stay in the shell.
 */
class Background(
    private val statusPoller: LedgerCountsPoller,
    private val scheduleBackstop: () -> Unit,
) {
    fun run() {
        statusPoller.stop()
        scheduleBackstop()
    }
}
