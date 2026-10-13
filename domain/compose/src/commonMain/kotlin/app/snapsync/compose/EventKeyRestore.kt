package app.snapsync.compose

/**
 * Keep [linkKey] as the joined event's key — only when the device has LOST it and [linkKey] is that event's own,
 * since otherwise reopening the current event's invite changes nothing — then read the key's presence again and run
 * the foreground's work, so sharing and receiving resume without waiting for the next wake. Answers

 * whether it was kept ([EventKeys.restoreLost][app.snapsync.services.crypto.EventKeys.restoreLost]). Top-level builders
 * rather than [AppCore] bodies because `AppCore` is measured.
 */
internal suspend fun AppCore.restoreEventKey(linkKey: String): Boolean =
    services.eventKeys.restoreLost(linkKey, services.config.config.value, log, onRestored = ::resumeAfterRestore)

private fun AppCore.resumeAfterRestore() {
    eventKeyReads.reread()
    work.keyRestored.requested()
}
