package app.snapsync.status

/**
 * The vocabulary of the [SyncStatusSource] seam (not the ledger's). A source backed by persisted
 * state cannot read it synchronously at construction — the first read is inherently asynchronous —
 * so the honest synchronous value at that moment is [Loading], not a guessed snapshot.
 *
 * [Loading] is a real, source-derived value ("persisted state not yet read"), never a placeholder.
 * Once a source reaches [Ready] it MUST NOT regress to [Loading]. A source that knows its truth
 * synchronously (an in-memory fake) seeds [Ready] immediately and never shows [Loading].
 */
sealed interface SyncStatus {
    data object Loading : SyncStatus
    data class Ready(val progress: SyncProgress) : SyncStatus
}
