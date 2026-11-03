package app.snapsync.data.upload

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.SharedRecordsReadiness
import app.snapsync.domain.upload.GateStep
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadHost
import app.snapsync.domain.upload.UploadPause

/**
 * **The gate of an upload pass** — read once per pass, so a join, a leave or a switch takes effect on the next pass
 * without a relaunch; decided by the domain ([UploadGate]). In order: whether the shared records live in the database
 * yet (an empty table there before the app copied them would read as "not joined"); the entry's reads
 * ([PassEntryRead]) and this process's admission; and, only for a pass that may run, the echo-suppression store and
 * the recognition of the event's received photos — so a process that may not create never opens that store.
 */
class UploadGateRead(
    private val records: SharedDatabase,
    private val entry: PassEntryRead,
    private val host: UploadHost,
    private val admission: UploaderAdmission,
    private val received: ReceivedPhotos,
) {
    /** The gate of a pass starting now. */
    suspend fun read(): UploadGate = when (records.readiness()) {
        SharedRecordsReadiness.LegacyPending -> UploadGate.Paused(UploadPause.LEGACY_RECORDS)
        SharedRecordsReadiness.Ready, is SharedRecordsReadiness.Unavailable -> entered(entry.read())
    }

    private suspend fun entered(read: PassEntry): UploadGate =
        when (val step = UploadGate.entry(read.read, host, read.presence, admission.admission())) {
            is GateStep.Proceed -> suppressed(step.run)
            is GateStep.Stop -> step.gate
        }

    private suspend fun suppressed(run: UploadGate.Run): UploadGate =
        when (val step = UploadGate.suppression(run, received.readiness())) {
            is GateStep.Proceed -> UploadGate.recognition(step.run, received.recognised(step.run.membership.event.id))
            is GateStep.Stop -> step.gate
        }
}
