package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.album.PlaceOwnPhotos
import app.snapsync.application.sharing.CheckSettleNeed
import app.snapsync.application.sharing.PublishManifest
import app.snapsync.application.upload.BeforeJob
import app.snapsync.application.upload.CreateJobs
import app.snapsync.application.upload.DeriveContribution
import app.snapsync.application.upload.PlanTopUp
import app.snapsync.application.upload.ReconcileUploader
import app.snapsync.application.upload.RecordWalk
import app.snapsync.application.upload.WalkOwnPhotos
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.sharing.Contribution
import app.snapsync.data.upload.UploadGateRead
import app.snapsync.data.wake.WakeArming
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.sharing.SettleNeed
import app.snapsync.domain.sharing.SettlingLook
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadPass
import app.snapsync.domain.upload.UploaderArming
import app.snapsync.domain.upload.UploaderChange
import app.snapsync.feature.status.ForegroundCounts
import app.snapsync.scheduling.Delivery
import app.snapsync.scheduling.InboundEntryPoint
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.Sequence
import app.snapsync.scheduling.orIfCut

// ── This device's own uploads, as the wake's sequences run them ─────────────────────────────────────────────────────
//
// Each pass reads its gate first: a join, a leave or a switch takes effect on the next pass without a relaunch. The
// top-up is the ONE sequence that creates jobs, so the process never creates twice at once; every other sequence that
// wants rows created runs it.

/**
 * **The top-up**: job creation from the ledger — what a freed upload slot needs, and the first step of every wake, so
 * the photos already known move before a walk the operating system may cut. The rows the policy admits now are placed
 * in the event album before any of their jobs exists, then created until the platform refuses. No walk, no publish.
 */
class TopUp(
    private val gate: UploadGateRead,
    private val contribute: DeriveContribution,
    private val plan: PlanTopUp,
    private val create: CreateJobs,
    private val album: AlbumPlacement,
    private val counts: ForegroundCounts,
) : Sequence<UploadPass>("top-up") {
    @OptIn(EntryPointCall::class)
    override suspend fun pass(on: Run) = on.steps {
        val passed = when (val gate = required { gate.read() }) {
            is UploadGate.Run -> required { contributing(gate, on) }
            is UploadGate.Stop -> gate.pass
        }
        isolated("counts") { counts.refresh() }
        passed
    }

    @OptIn(EntryPointCall::class)
    private suspend fun contributing(run: UploadGate.Run, on: Run) = on.steps {
        val contribution = required { contribute(run.membership) }
        when (contribution.policy.contributes) {
            true -> required { created(run.membership, contribution, on) }
            false -> UploadPass.SKIPPED
        }
    }

    @OptIn(EntryPointCall::class)
    private suspend fun created(membership: Membership.Active, contribution: Contribution, on: Run) = on.steps {
        val planned = required { plan(membership.event.id, contribution.policy) }
        required { create(membership.event.id, planned, album.before(membership, on)) }
    }
}

/** What a walk → publish pass came to: its [pass], and whether it [added] rows a top-up is due for at once. */
class WalkPass(val pass: UploadPass, val added: Boolean)

/**
 * **The walk → manifest publish**: the member's photos walked and recorded — the photos whose bare rows it dates placed
 * in the event album first — and the device manifest published. It creates no job: the rows it recorded are the next
 * top-up's work. Also the own work of a selection change under a partial grant, and the end of a wake's final publish.
 * A membership that shares nothing publishes an EMPTY manifest, its honest statement; a pass its gate stopped
 * publishes nothing.
 */
class WalkAndPublish(
    private val gate: UploadGateRead,
    private val contribute: DeriveContribution,
    private val walk: WalkOwnPhotos,
    private val record: RecordWalk,
    private val album: AlbumPlacement,
    private val publish: PublishManifest,
) : Sequence<WalkPass>("walk-and-publish") {
    @OptIn(EntryPointCall::class)
    override suspend fun pass(on: Run) = on.steps {
        when (val gate = required { gate.read() }) {
            is UploadGate.Run -> required { contributing(gate, on) }
            is UploadGate.Stop -> WalkPass(gate.pass, added = false)
        }
    }

    @OptIn(EntryPointCall::class)
    private suspend fun contributing(run: UploadGate.Run, on: Run) = on.steps {
        val contribution = required { contribute(run.membership) }
        when (contribution.policy.contributes) {
            true -> required { walked(run, contribution, on) }
            false -> required { declined(run, contribution, on) }
        }
    }

    @OptIn(EntryPointCall::class)
    private suspend fun walked(run: UploadGate.Run, contribution: Contribution, on: Run) = on.steps {
        val event = run.membership.event.id
        val walked = required { walk(event, contribution) }
        required { album.place(run.membership, walked.diff.dated, on) }
        val recorded = required { record(event, walked) }
        isolated("publish") { publish(run, contribution.policy, walked.look) }
        WalkPass(UploadPass.CAUGHT_UP, recorded.added)
    }

    @OptIn(EntryPointCall::class)
    private suspend fun declined(run: UploadGate.Run, contribution: Contribution, on: Run) = on.steps {
        isolated("publish") { publish(run, contribution.policy, SettlingLook.NothingToLook) }
        WalkPass(UploadPass.SKIPPED, added = false)
    }
}

/**
 * **This device's share settled at the end of a wake**: once the member's own range has ended and no settled share
 * was sent, the walk → publish runs — its look, taken now, is what settles it. Under a partial grant a wake walks
 * nothing of its own, so this is what makes sure every device eventually says it has settled; a selection not read yet
 * is no look, and the next wake tries again. Nothing while not joined, or once the event's photos are deleted.
 */
class SettleShare(
    private val membership: MembershipBranch,
    private val need: CheckSettleNeed,
    private val walkAndPublish: WalkAndPublish,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (required { need(joined) }) {
                    SettleNeed.DUE -> run(walkAndPublish).let { }
                    SettleNeed.NOT_DUE -> Unit
                }
            }
        }
    }
}

/**
 * **This device's own photos, as a wake uploads them**: top up first, so the photos already known move before a walk
 * the operating system may cut; then walk and publish, under a full grant only — under a partial grant the selection is
 * the scope, and a selection change walks it as its own work; then top up again for what the walk recorded.
 */
class UploadOwn(
    private val topUp: TopUp,
    private val walkAndPublish: WalkAndPublish,
    private val grant: GrantRead,
) : Sequence<UploadPass>("upload-own") {
    override suspend fun pass(on: Run) = on.steps {
        // A cut pass says nothing about whether work remains, so it does.
        val first = run(topUp).orIfCut(UploadPass.WORK_LEFT)
        val walked = when (grant.access()) {
            PhotoAccess.FULL -> run(walkAndPublish).orIfCut(WalkPass(UploadPass.WORK_LEFT, added = false))
            PhotoAccess.LIMITED, PhotoAccess.NONE -> null
        }
        val again = when (walked?.added) {
            true -> run(topUp).orIfCut(UploadPass.WORK_LEFT)
            false, null -> null
        }
        UploadPass.of(first, walked?.pass, again)
    }
}

/**
 * **Event-album placement of the member's own photos** — before any job of theirs exists, and before a bare row is
 * dated: creating a job records it requested durably, and once dated a row is no longer bare, so a death between the
 * two would leave the photo unplaced by this path. Placed first, a creation that fails or is cut leaves the row as it
 * was, and the next pass places it again for free: adding an asset already in the collection is a no-op (measured,
 * simulator, iOS 26.5). Whether and where is [PlaceOwnPhotos]' — the one album use case both processes hold;
 * best-effort,
 * never gating a creation.
 */
class AlbumPlacement(private val own: PlaceOwnPhotos) {
    @OptIn(EntryPointCall::class)
    suspend fun place(membership: Membership.Active, assets: Set<AssetId>, on: Run) = on.steps {
        isolated("album") { own(membership, assets.sorted()) }
    }

    /** Each photo placed just before its own job is created — once it is found, and only while its row needs one. */
    fun before(membership: Membership.Active, on: Run): BeforeJob = object : BeforeJob {
        override suspend fun before(asset: AssetId) = place(membership, setOf(asset), on)
    }
}

/**
 * **What a membership transition arms the app's own uploader with** ([UploaderArming]): armed, its first wake
 * requested, on its own run; disarmed, its scheduled wakes cancelled — nothing else: in-flight transfers finish and
 * record. Nothing, when the transition left it as it was ([UploaderArming.UNCHANGED]).
 */
class UploaderArm(private val runtime: Runtime, private val wake: WakeWork, private val arming: WakeArming) {
    suspend fun apply(armed: UploaderArming) = when (armed) {
        UploaderArming.ARM -> runtime.detached("OnArm") { run -> wake.full(WakeKind.ARM, run) }
        UploaderArming.DISARM -> arming.cancelAll()
        UploaderArming.UNCHANGED -> Unit
    }
}

/**
 * **The app launched with its UI, or the photo grant really changed**: what that does to the two uploaders — the
 * launch reconciles by comparison, and only a join forces a repair. Never the grant's replayed value: the value the
 * launch reconciled against is not a change, and riding it would force the extension's re-registration — wiping its
 * in-flight jobs — on every launch.
 */
class OnUploaderChange(
    runtime: Runtime,
    private val reconcile: ReconcileUploader,
    private val arm: UploaderArm,
) : InboundEntryPoint<UploaderChange>(runtime) {
    @OptIn(EntryPointCall::class)
    override fun Delivery.run(arg: UploaderChange) {
        detachedSteps {
            val armed = required { reconcile(arg) }
            isolated("arm") { arm.apply(armed) }
        }
    }
}
