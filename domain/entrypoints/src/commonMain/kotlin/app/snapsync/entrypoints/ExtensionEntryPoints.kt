package app.snapsync.entrypoints

import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.feature.upload.answer
import app.snapsync.inbound.Log
import app.snapsync.model.ProcessingAnswer
import app.snapsync.scheduling.AnsweredEntryPoint
import app.snapsync.scheduling.Delivery
import app.snapsync.scheduling.InboundEntryPoint
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.upload.requeueWhilePending
import co.touchlab.kermit.Logger

// ── What happens on each trigger of the upload extension ────────────────────────────────────────────────────────────
//
// The extension's process exists for one invocation at a time (iOS ≥26.1's PhotoKit background-upload extension). It
// is invoked, it answers, and its only other end is a kill.

/**
 * The operating system invokes the extension: one upload cycle, answered on the invoking thread — completed, still
 * processing (pending work, so invoke again), or failed. The cycle's own entry gate withholds it without a full grant:
 * the operating system may invoke a registration made under one after the grant narrowed.
 */
class OnProcess(
    runtime: Runtime,
    private val credential: CachedCredentialStore,
    private val membership: MembershipRepository,
    private val keys: EventKeyStore,
    private val cycle: Lazy<UploadCycle>,
    private val ledger: LedgerService,
) : AnsweredEntryPoint<Unit, ProcessingAnswer>(runtime, onFailure = ProcessingAnswer.FAILED) {
    private val log = Logger.withTag("OnProcess")

    override suspend fun run(arg: Unit, on: Run): ProcessingAnswer = on.steps {
        // The app renews into the shared protected item, which this process's copy cannot see: read it again.
        required { credential.reread() }
        required { membership.reload() }
        required { keys.reload() }
        val ran = required { cycle.value.run() }
        required { ran.requeueWhilePending(ledger, log) }.answer()
    }
}

/**
 * The end of an invocation, NOT a kill: it follows every normal return of [OnProcess], and never the kill that ends a
 * call running past its budget — that kill sends nothing at all.
 */
class OnTerminate(runtime: Runtime) : InboundEntryPoint<Unit>(runtime) {
    private val log = Log("OnTerminate")

    override fun Delivery.run(arg: Unit) =
        log.unlabelled.i(
            "the OS ended this invocation — notifyTermination follows a normal return; a killed call gets none",
        )
}
