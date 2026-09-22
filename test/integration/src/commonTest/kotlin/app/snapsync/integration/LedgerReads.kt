package app.snapsync.integration

import app.snapsync.model.LedgerState
import app.snapsync.ports.LedgerStore

/**
 * The keys of the rows with a job in flight — a test read. The port has no such read any more: its only
 * production reader was the stranded repair (decision record `changes/both-uploaders-active`).
 */
internal suspend fun LedgerStore.requestedKeys(): Set<String> =
    manifestRows().filter { it.state == LedgerState.REQUESTED }.mapTo(mutableSetOf()) { it.key }
