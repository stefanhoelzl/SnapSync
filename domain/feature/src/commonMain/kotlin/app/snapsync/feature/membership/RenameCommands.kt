package app.snapsync.feature.membership

/**
 * The command port for renaming the joined event: fire-and-forget, like `EventCreator`. It MUST NOT
 * return a value and MUST NOT suspend; the outcome arrives exclusively via [RenameStatusSource].
 *
 * [name] is passed as typed; the use-case trims it (the same split `EventCreator`/`CreateEvent` use).
 */
interface EventRenamer {
    suspend fun rename(eventId: String, name: String)
}

/**
 * The command that returns [RenameStatusSource] to [RenameStatus.Idle] — the latch-clearing half of
 * [RenameStatus.Succeeded]. Fired by the screen after it consumes a terminal status, so a second rename
 * starts from a clean sequence rather than re-reading the previous one's outcome.
 */
interface ResetRename {
    fun reset()
}

/** A no-op [EventRenamer] for hosts/tests that forge [RenameStatus] directly (e.g. the harness). */
object NoOpEventRenamer : EventRenamer {
    override suspend fun rename(eventId: String, name: String) = Unit
}

/** A no-op [ResetRename], the twin of [NoOpEventRenamer]. */
object NoOpResetRename : ResetRename {
    override fun reset() = Unit
}
