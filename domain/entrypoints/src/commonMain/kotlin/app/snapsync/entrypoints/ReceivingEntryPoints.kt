package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.receiving.HoldsEverything
import app.snapsync.application.receiving.ImportNextStaged
import app.snapsync.application.receiving.ImportPass
import app.snapsync.application.receiving.ImportReadiness
import app.snapsync.application.receiving.ImportReady
import app.snapsync.application.receiving.PlanIfDue
import app.snapsync.application.receiving.PlanReceiving
import app.snapsync.application.receiving.ReceivingOf
import app.snapsync.application.receiving.RecognitionAsk
import app.snapsync.application.receiving.RecognitionNeeded
import app.snapsync.application.receiving.RecognizeReceivedPhotos
import app.snapsync.application.receiving.ReleaseImported
import app.snapsync.application.receiving.ReportReceivedPosition
import app.snapsync.application.receiving.SettleInterruptedImports
import app.snapsync.application.receiving.SweepUnclaimedStaging
import app.snapsync.application.receiving.WithholdRecognition
import app.snapsync.data.sharing.SelectionState
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.membership.CheckCadence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.ReceivingVerdict
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.status.ForegroundCounts
import app.snapsync.query.membership.MembershipQuery
import app.snapsync.scheduling.Concurrent
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.Sequence

// ── Receiving, as the triggers order it ──────────────────────────────────────────────────────────────────────────────

/**
 * Plan what the joined membership receives, for [trigger] — nothing for one that shares only, or that lost its event's
 * key until its invite gives it back. [announced] is the position a push named.
 */
class PlanJoined(
    private val membership: MembershipBranch,
    private val receivingOf: ReceivingOf,
    private val plan: PlanReceiving,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run, trigger: UnionTrigger, announced: Long? = null) = on.steps {
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (val receiving = required { receivingOf(joined) }) {
                    is ReceivingVerdict.Receives -> isolated("plan") { plan(receiving.receiving, trigger, announced) }
                    is ReceivingVerdict.NotReceiving -> Unit
                }
            }
        }
    }
}

/**
 * A background wake's photo check: plan what the joined membership receives unless the union was read within the hour
 * (decision record `changes/timely-background-receiving`, D4).
 */
class PhotoCheck(
    private val membership: MembershipBranch,
    private val receivingOf: ReceivingOf,
    private val due: PlanIfDue,
    private val plan: PlanReceiving,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (val receiving = required { receivingOf(joined) }) {
                    is ReceivingVerdict.Receives -> when (required { due(receiving.receiving) }) {
                        CheckCadence.Due -> isolated("photo check") { plan(receiving.receiving, UnionTrigger.WAKE) }
                        CheckCadence.NotYet -> Unit
                    }
                    is ReceivingVerdict.NotReceiving -> Unit
                }
            }
        }
    }
}

/**
 * A recognition pass of the joined membership's received photos, when one is due for [ask]'s reason — single-flight
 * across the process, so the grant, a selection and a drain never read the library twice at once.
 */
class Recognition(
    private val membership: MembershipBranch,
    private val needed: RecognitionNeeded,
    private val recognize: RecognizeReceivedPhotos,
    private val ask: RecognitionAsk,
) : Sequence<Unit>("recognition-${ask.name.lowercase()}") {
    @OptIn(EntryPointCall::class)
    override suspend fun pass(on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (required { needed(joined, ask) }) {
                    true -> isolated("recognise") { recognize(joined, UnionTrigger.GRANT) }
                    false -> Unit
                }
            }
        }
    }
}

/**
 * Import what other members sent and is staged here: once the joined membership's received photos are recognised, one
 * photo at a time, each import outliving the pass — the platform cannot stop one once submitted. Yields to a join: an
 * import can wait on a library transaction that never reports, and a new request must not wait behind it — the stalled
 * import runs on, claimed, and the next pass imports the rest.
 */
class ImportReceived(
    private val membership: MembershipBranch,
    private val recognition: Recognition,
    private val ready: ImportReady,
    private val albums: AlbumCoordinator,
    private val importNext: ImportNextStaged,
    private val counts: ForegroundCounts,
) : Sequence<Unit>("import-received", yieldsToJoin = true) {
    @OptIn(EntryPointCall::class)
    override suspend fun pass(on: Run) = on.steps {
        run(recognition)
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (val readiness = required { ready(joined) }) {
                    is ImportReadiness.Ready -> required { drained(joined, readiness, on) }
                    ImportReadiness.NotReady -> Unit
                }
            }
        }
        isolated("counts") { counts.refresh() }
    }

    @OptIn(EntryPointCall::class)
    private suspend fun drained(joined: Membership.Active, readiness: ImportReadiness.Ready, on: Run) = on.steps {
        // The album is read once, before the drain, so the platform's change block does no lookup of its own.
        val pass =
            required { ImportPass(albums.albumIdFor(joined.event.id, joined.share.album == AlbumChoice.SaveToAlbum)) }
        drain { importNext(readiness.recognised, pass) }
        // How a folder album learns it has held a photo — what later tells an emptied one from a fresh one.
        pass.landedIn?.let { album -> isolated("album filled") { albums.onImportedInto(joined.event.id, album) } }
    }
}

/**
 * How far the joined membership holds the others' photos, as the leave question asks: whether leaving it now loses
 * nothing. No membership, or a union that cannot be read, holds nothing.
 */
class HeldCheck(
    private val membership: MembershipBranch,
    private val receivingOf: ReceivingOf,
    private val holdsEverything: HoldsEverything,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run): Boolean = on.steps {
        var held = false
        membership(membership, Freshness.Cached) {
            active { joined ->
                val receiving = required { receivingOf(joined) }
                held = required { holdsEverything(joined, receiving, UnionTrigger.LEAVE_CHECK).complete }
            }
        }
        held
    }
}

/**
 * Receiving's share of the foreground's work: a whole read of the union — the member is looking, and it heals
 * anything a delta missed — and the device's staged backlog reclaimed, which is the device's, not a membership's, so it
 * runs unjoined and under a share-only membership too. Its steps are children of the foreground's own concurrent step,
 * not a nested one: the union read starts with the foreground's other reads, so its transfers are under way as early
 * as the opening allows.
 */
class ForegroundReceiving(
    private val receive: PlanJoined,
    private val sweepUnclaimed: SweepUnclaimedStaging,
    private val releaseImported: ReleaseImported,
) {
    @OptIn(EntryPointCall::class)
    fun children(of: Concurrent, on: Run) = with(of) {
        child("receive") { receive.run(on, UnionTrigger.FOREGROUND) }
        child("unclaimed staging") { sweepUnclaimed() }
        child("reclaim") { releaseImported() }
    }
}

/**
 * The end of a wake for what this device received: a share-only membership tells its event at once that it receives
 * nothing; one that receives tells, once the event's range has ended, how far it holds the others' photos
 * ([ReportReceivedPosition]).
 */
class ReceivedPositionReport(
    private val membership: MembershipBranch,
    private val receivingOf: ReceivingOf,
    private val holds: HoldsEverything,
    private val report: ReportReceivedPosition,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            active { joined ->
                val receiving = required { receivingOf(joined) }
                when (required { report.due(joined, receiving) }) {
                    true -> required { reported(joined, receiving, on) }
                    false -> Unit
                }
            }
        }
    }

    @OptIn(EntryPointCall::class)
    private suspend fun reported(joined: Membership.Active, receiving: ReceivingVerdict, on: Run) = on.steps {
        val held = required { holds(joined, receiving, UnionTrigger.RECEIVED_CHECK) }
        required { report(joined, held) }
    }
}

/**
 * A selection the gallery is about to publish: when it holds marked photos the store does not record, the membership's
 * recognition is withheld BEFORE any reader sees the snapshot ([WithholdRecognition]).
 */
class SelectionRecognition(private val membership: MembershipQuery, private val withhold: WithholdRecognition) {
    @OptIn(EntryPointCall::class)
    suspend fun before(selection: SelectionState.Read) = withhold(membership.state.value, selection)
}

/** The photo grant became usable: the received photos an earlier install left are recognised now. */
class OnPhotoGrantUsable(private val runtime: Runtime, private val recognition: Recognition) {
    suspend fun requested() = runtime.detached("OnPhotoGrantUsable") { on -> on.steps { run(recognition) } }
}

/**
 * The process's one recovery pass, once its grant can be read: settle what a dead process's imports left
 * ([SettleInterruptedImports]), then drain — a cleared marker returns its row to importable work, and a clear nothing
 * imports moves the stall to some later trigger.
 */
class OnInheritedImports(
    private val runtime: Runtime,
    private val settle: SettleInterruptedImports,
    private val importReceived: ImportReceived,
) {
    @OptIn(EntryPointCall::class)
    suspend fun requested() = runtime.detached("OnInheritedImports") { on ->
        on.steps {
            isolated("settle") { settle() }
            run(importReceived)
        }
    }
}
