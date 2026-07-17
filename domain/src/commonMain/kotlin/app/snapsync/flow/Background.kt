package app.snapsync.flow

/**
 * The **background** OS-callback trigger flow (spec `module-architecture`, "Rules in features, order
 * in flows"). On backgrounding: stop listening for the cross-process liveness ding (a suspended app
 * cannot act on it) and queue the download import-tail backstop so any staged-but-unimported foreign
 * assets get imported at the next idle/charging window even if no further download wakes the app
 * (capability `photo-download`, 5.4).
 *
 * Pure ordering of two platform effects — both irreducibly the shell's ([unregisterLiveness] a
 * `CFNotificationCenter` deregistration, [scheduleBackstop] a `BGTaskScheduler` submit), so each
 * arrives as a `compose/`-built effect lambda. The forge guard, the entry-point log wrap, and the
 * "entering background" banner stay in the shell.
 */
class Background(
    private val unregisterLiveness: () -> Unit,
    private val scheduleBackstop: () -> Unit,
) {
    fun run() {
        unregisterLiveness()
        scheduleBackstop()
    }
}
