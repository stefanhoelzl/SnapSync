package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.crash.DescribeReporter
import app.snapsync.application.sharing.PublishManifest
import app.snapsync.application.upload.CreateJobs
import app.snapsync.application.upload.DeriveContribution
import app.snapsync.application.upload.PlanTopUp
import app.snapsync.application.upload.Presented
import app.snapsync.application.upload.RecordWalk
import app.snapsync.application.upload.SettlePresentedJobs
import app.snapsync.application.upload.WalkOwnPhotos
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.sharing.Contribution
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.UploadGateRead
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.SettlingLook
import app.snapsync.domain.upload.ExtensionAnswer
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadPass
import app.snapsync.entrypoints.MembershipEntryPoints.membership
import app.snapsync.inbound.Log
import app.snapsync.ports.ProcessingAnswer
import app.snapsync.scheduling.AnsweredEntryPoint
import app.snapsync.scheduling.Delivery
import app.snapsync.scheduling.InboundEntryPoint
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime

// ── What happens on each trigger of the upload extension ────────────────────────────────────────────────────────────
//
// The extension's process exists for one invocation at a time (iOS ≥26.1's PhotoKit background-upload extension). It
// is invoked, it answers, and its only other end is a kill.

/**
 * The operating system invokes the extension: one upload pass, answered on the invoking thread — completed, still
 * processing (work remains or a job is in flight, so invoke again), or failed. The pass's own gate withholds it without
 * a full grant: the operating system may invoke a registration made under one after the grant narrowed.
 */
class OnProcess(
    runtime: Runtime,
    private val credential: CachedCredentialStore,
    private val membership: MembershipRepository,
    private val keys: EventKeyStore,
    private val describe: DescribeReporter,
    private val pass: ExtensionPass,
) : AnsweredEntryPoint<Unit, ProcessingAnswer>(runtime, onFailure = ProcessingAnswer.FAILED) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: Unit, on: Run): ProcessingAnswer = on.steps {
        // The app renews into the shared protected item, which this process's copy cannot see: read it again.
        required { credential.reread() }
        required { membership.reload() }
        // Every report this invocation sends names the membership it works for. Never the pass's end: best effort.
        isolated("describe reporter") { describe() }
        required { keys.reload() }
        when (required { pass.run(on) }) {
            ExtensionAnswer.REST -> ProcessingAnswer.COMPLETED
            ExtensionAnswer.INVOKE_AGAIN -> ProcessingAnswer.PROCESSING
            ExtensionAnswer.COULD_NOT_LOOK -> ProcessingAnswer.FAILED
        }
    }
}

/**
 * **One invocation's upload pass**: the gate first; what the OS queue presents settled before anything else, as much
 * as the gate allows (owed whatever the pass does — error 50008 otherwise); then, for a membership that contributes,
 * the walk, the top-up and the publish ([ExtensionUploads]) — and for one that shares nothing, its empty manifest.
 */
class ExtensionPass(
    private val gate: UploadGateRead,
    private val contribute: DeriveContribution,
    private val settle: SettlePresentedJobs,
    private val uploads: ExtensionUploads,
    private val publish: PublishManifest,
    private val rows: OwnUploadRows,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run): ExtensionAnswer = on.steps {
        when (val gate = required { gate.read() }) {
            is UploadGate.Run -> required { contributing(gate, on) }
            is UploadGate.Withheld -> required { withheld(gate.event) }
            is UploadGate.Unreadable -> ExtensionAnswer.COULD_NOT_LOOK
            UploadGate.NotJoined, is UploadGate.Paused -> gate.pass.extensionAnswer(inFlight = 0)
        }
    }

    @OptIn(EntryPointCall::class)
    private suspend fun contributing(run: UploadGate.Run, on: Run): ExtensionAnswer = on.steps {
        val event = run.membership.event.id
        val contribution = required { contribute(run.membership) }
        val passed = when (contribution.policy.contributes) {
            true -> required { uploaded(run, contribution, on) }
            false -> required { declined(run, contribution, on) }
        }
        passed.extensionAnswer(rows.inFlight(event).count)
    }

    @OptIn(EntryPointCall::class)
    private suspend fun uploaded(run: UploadGate.Run, contribution: Contribution, on: Run): UploadPass = on.steps {
        val settled = required { settle(run.membership.event.id, Presented.Judged(contribution.policy)) }
        UploadPass.of(settled, required { uploads.run(run, contribution, on) })
    }

    @OptIn(EntryPointCall::class)
    private suspend fun declined(run: UploadGate.Run, contribution: Contribution, on: Run): UploadPass = on.steps {
        required { settle(run.membership.event.id, Presented.Judged(contribution.policy)) }
        isolated("publish") { publish(run, contribution.policy, SettlingLook.NothingToLook) }
        UploadPass.SKIPPED
    }

    @OptIn(EntryPointCall::class)
    private suspend fun withheld(event: EventId): ExtensionAnswer {
        settle(event, Presented.Withheld)
        return UploadPass.SKIPPED.extensionAnswer(inFlight = 0)
    }
}

/**
 * **The extension's walk, top-up and publish**, in one invocation: the member's photos walked and recorded — the
 * photos whose bare rows it dates placed in the event album first — then the rows the policy admits created until
 * the queue refuses, each photo placed before its job exists, and the device manifest published.
 */
class ExtensionUploads(
    private val walk: WalkOwnPhotos,
    private val record: RecordWalk,
    private val plan: PlanTopUp,
    private val create: CreateJobs,
    private val album: AlbumPlacement,
    private val publish: PublishManifest,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(run: UploadGate.Run, contribution: Contribution, on: Run): UploadPass = on.steps {
        val event = run.membership.event.id
        val walked = required { walk(event, contribution) }
        required { album.place(run.membership, walked.diff.dated, on) }
        required { record(event, walked) }
        val planned = required { plan(event, contribution.policy) }
        val created = required { create(event, planned, album.before(run.membership, on)) }
        isolated("publish") { publish(run, contribution.policy, walked.look) }
        created
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
