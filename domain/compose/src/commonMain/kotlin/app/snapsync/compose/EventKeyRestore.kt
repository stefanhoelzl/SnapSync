package app.snapsync.compose

import app.snapsync.feature.upload.TailTrigger
import app.snapsync.model.runCatchingCancellable
import kotlinx.coroutines.launch

/**
 * Keep [linkKey] as the joined event's key — only when the device has LOST it and [linkKey] is that event's own
 * (capability `join-event`, "Reopening the current event's invite changes nothing") — then read the key's presence
 * again and run the foreground's work, so sharing and receiving resume without waiting for the next wake. Answers
 * whether it was kept. Top-level builders rather than [AppCore] bodies because `AppCore` is measured.
 */
internal suspend fun AppCore.restoreEventKey(linkKey: String): Boolean {
    val keys = services.eventKeys
    val opens = keys.lostKeyIdOf(services.config.config.value)?.let { it == keys.idOf(linkKey) } == true
    val kept = opens && keepRestored(linkKey)
    if (kept) resumeAfterRestore()
    return kept
}

private fun AppCore.keepRestored(linkKey: String): Boolean =
    runCatchingCancellable { services.eventKeys.keep(linkKey) }
        .onFailure { log.w { "the reopened invite's key could not be kept: $it" } }
        .isSuccess

private fun AppCore.resumeAfterRestore() {
    eventKeyReads.reread()
    val wake = tail.hold("restoreEventKey")
    scope.launch {
        runCatchingCancellable { foregroundFlow.run() }
            .onFailure { log.w(it) { "the foreground flow after a restored key failed; its tail still runs" } }
        wake.thenTail(TailTrigger.FOREGROUND)
    }
}
