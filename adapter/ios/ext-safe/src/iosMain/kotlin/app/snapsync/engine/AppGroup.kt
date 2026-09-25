package app.snapsync.engine

import platform.Foundation.NSUserDefaults

/** The App Group shared by the host app and the background-upload extension. */
const val LEDGER_APP_GROUP: String = "group.app.snapsync"

/**
 * The App-Group `NSUserDefaults` key the retired join marker lived under. Nothing reads or writes it: the
 * upload cycle no longer detects membership changes, because a join loads the ledger itself (capability
 * `photo-sharing`). It survives only as the target of [removeOrphanedJoinMarker], and stays a
 * pinned runtime identity (`docs/architecture.md`) because a drifted literal would make that
 * removal a silent no-op.
 */
private const val RETIRED_JOIN_MARKER_KEY: String = "rejoin.joinedEventId"

/**
 * Remove the retired join marker's orphaned key from the App-Group defaults (capability `sync-status`).
 * Called on every app process start; `removeObjectForKey` on an absent key is a no-op, so this keeps no
 * record of having run.
 *
 * The reason is **rollback**. A build from before `changes/join-loads-leave-clears` compares the configured
 * event with this marker on every cycle. With the key gone it finds no marker, reconciles once and resets
 * the ledger from the device's stored-file listing — correct whatever this build left behind. Left in
 * place, a device that left and re-joined the **same** event under this build would present a matching
 * marker to a reverted build, which would skip its seed and re-upload the device's whole window.
 */
fun removeOrphanedJoinMarker(suiteName: String = LEDGER_APP_GROUP) {
    NSUserDefaults(suiteName = suiteName).removeObjectForKey(RETIRED_JOIN_MARKER_KEY)
}
