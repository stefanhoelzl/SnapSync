package app.snapsync.data.upload

import app.snapsync.data.receiving.ReceivedPhotosRead
import app.snapsync.data.receiving.ReceivedReadiness
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.SharedRecordsReadiness
import app.snapsync.domain.upload.EchoReadiness
import app.snapsync.domain.upload.GateStep
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadHost
import app.snapsync.domain.upload.UploadPause
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

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
    private val received: ReceivedPhotosRead,
) {
    private val log = Log("UploadGate")

    /** The gate of a pass starting now — and why it stops, for the device's log. */
    suspend fun read(): UploadGate = gated().also { logged(it) }

    private suspend fun gated(): UploadGate = when (records.readiness()) {
        SharedRecordsReadiness.LegacyPending -> UploadGate.Paused(UploadPause.LEGACY_RECORDS)
        SharedRecordsReadiness.Ready, is SharedRecordsReadiness.Unavailable -> entered(entry.read())
    }

    private suspend fun logged(gate: UploadGate) = when (gate) {
        is UploadGate.Run -> Unit
        is UploadGate.Unreadable -> log.w("the pass could not look — nothing touched", "detail" of gate.detail)
        UploadGate.NotJoined -> log.i("not joined, or no upload host — nothing to upload")
        is UploadGate.Withheld -> log.i("this process may not create jobs now — the pass is withheld")
        is UploadGate.Paused -> log.i("waiting for another process", "reason" of gate.reason)
    }

    private suspend fun entered(read: PassEntry): UploadGate =
        when (val step = UploadGate.entry(read.read, host, read.presence, admission.admission())) {
            is GateStep.Proceed -> suppressed(step.run)
            is GateStep.Stop -> step.gate
        }

    private suspend fun suppressed(run: UploadGate.Run): UploadGate =
        when (val step = UploadGate.suppression(run, echoOf(received.readiness()))) {
            is GateStep.Proceed -> UploadGate.recognition(step.run, recognised(step.run))
            is GateStep.Stop -> step.gate
        }

    /** Recognised only on the store's own word: a recognition it could not read is none. */
    private suspend fun recognised(run: UploadGate.Run): Boolean =
        when (received.recognition(run.membership.event.id)) {
            is RecognitionRead.Recognised -> true
            RecognitionRead.NotRecognised, is RecognitionRead.Unavailable -> false
        }

    private fun echoOf(readiness: ReceivedReadiness): EchoReadiness = when (readiness) {
        ReceivedReadiness.Ready -> EchoReadiness.Ready
        ReceivedReadiness.OldSchema -> EchoReadiness.OldSchema
        is ReceivedReadiness.Unavailable -> EchoReadiness.Unavailable(readiness.detail)
    }
}
