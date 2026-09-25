package app.snapsync.services.preferences

import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import co.touchlab.kermit.Logger

/**
 * The preferences key the retired join marker lived under. Nothing reads or writes it: the upload cycle no longer
 * detects membership changes, because a join loads the ledger itself (capability `photo-sharing`). It survives
 * only as the target of [removeOrphanedJoinMarker], and stays a pinned runtime identity (`docs/architecture.md`)
 * because a drifted literal would make that removal a silent no-op.
 */
private const val RETIRED_JOIN_MARKER_KEY: String = "rejoin.joinedEventId"

/**
 * Remove the retired join marker's orphaned key (capability `sync-status`). Called on every app process start;
 * removing an absent key is a no-op, so this keeps no record of having run.
 *
 * The reason is **rollback**. A build from before `changes/join-loads-leave-clears` compares the configured event
 * with this marker on every cycle. With the key gone it finds no marker, reconciles once and resets the ledger from
 * the device's stored-file listing — correct whatever this build left behind. Left in place, a device that left
 * and re-joined the **same** event under this build would present a matching marker to a reverted build, which
 * would skip its seed and re-upload the device's whole window.
 */
fun removeOrphanedJoinMarker(preferences: Preferences, log: Logger = Logger.withTag("joinMarker")) {
    val removed = preferences.remove(RETIRED_JOIN_MARKER_KEY)
    if (removed != WriteOutcome.Ok) log.w { "the retired join marker could not be removed ($removed); retried next launch" }
}
