package app.snapsync.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform's live report of resource keys whose upload has **succeeded but is not yet recorded
 * in the ledger** (e.g. iOS jobs the system marked succeeded but the extension has not acknowledged).
 * [keys] holds the current succeeded set; [refresh] re-reads the platform and **replaces** it (keys
 * drop out as the platform releases them — accumulation across refreshes is the projection's job, not
 * the source's). The source is observation-only: obtaining or refreshing the set never mutates the
 * ledger or the platform's job state, so the ledger's single writer is unaffected.
 */
interface ObservedCompletionsSource {
    val keys: StateFlow<Set<String>>
    suspend fun refresh()
}

/**
 * An [ObservedCompletionsSource] that observes nothing — its set is always empty, so the overlay is
 * the identity. Used where no platform observation exists (the desktop harness, or an OS without the
 * upload-job API).
 */
object NoObservedCompletions : ObservedCompletionsSource {
    override val keys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override suspend fun refresh() = Unit
}
