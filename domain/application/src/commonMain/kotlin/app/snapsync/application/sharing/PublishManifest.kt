package app.snapsync.application.sharing

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.sharing.ManifestBody
import app.snapsync.data.sharing.ManifestPublication
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.PublishRefusal
import app.snapsync.data.sharing.PublishReply
import app.snapsync.data.sharing.RecordRead
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.time.WallClock
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RowsRead
import app.snapsync.domain.membership.DeletionVerdict
import app.snapsync.domain.sharing.DeviceManifest
import app.snapsync.domain.sharing.PublishVerdict
import app.snapsync.domain.sharing.PublishedDeclaration
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.sharing.SettlingLook
import app.snapsync.domain.sharing.ShareDeclaration
import app.snapsync.domain.sharing.SharePhase
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadRow
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val log = Log("PublishManifest")

/**
 * **Publish this device's manifest** — the step every look at the member's photos ends
 * with, in both processes. The manifest declares every row the membership's one policy admits, with the version the
 * pass read first, and settles the share only on [ShareDeclaration.of]'s evidence: a look taken since the member's own
 * range ended, or a policy that shares nothing (change `settling-needs-a-real-look`).
 *
 * A manifest the record already holds is not sent again. Once the range has ended and a settled share was sent, a
 * publish that cannot prove it again is not sent either: it would only take the settlement back. A publish the backend
 * took is recorded before the pass can end — one step a cancellation cannot split, or the next pass would send it
 * again. The backend's answer that the event's photos were deleted marks the membership so, in either process.
 */
class PublishManifest(
    private val rows: OwnUploadRows,
    private val record: ManifestRecord,
    private val publication: ManifestPublication,
    private val membership: MembershipRepository,
    private val clock: WallClock,
) {
    /** Publish what [run]'s rows declare under [policy], after [look]. */
    @EntryPointCall
    suspend operator fun invoke(run: UploadGate.Run, policy: SelectionPolicy, look: SettlingLook): PublishOutcome {
        val phase = SharePhase.of(run.membership.share.range, clock.now())
        val attempt = Attempt(run, policy, phase, ShareDeclaration.of(phase, look))
        return when (val held = record.read(run.membership.event.id)) {
            is RecordRead.Held -> judged(attempt, held, held.declaration)
            RecordRead.None -> judged(attempt, held, PublishedDeclaration.NONE)
            is RecordRead.Unavailable -> notPublished("the manifest record is unreadable", held.detail)
        }
    }

    private suspend fun judged(attempt: Attempt, held: RecordRead, sent: PublishedDeclaration): PublishOutcome =
        when (PublishVerdict.of(attempt.phase, attempt.declaration, sent)) {
            PublishVerdict.SEND -> fromRows(attempt, held)
            PublishVerdict.KEEP_SETTLED -> PublishOutcome.KeptSettled.also {
                log.i("the look settled nothing, and a settled share was sent — kept")
            }
        }

    private suspend fun fromRows(attempt: Attempt, held: RecordRead): PublishOutcome =
        when (val read = rows.rows(attempt.run.membership.event.id)) {
            is RowsRead.Read -> projected(attempt, held, attempt.manifest(read.rows))
            is RowsRead.Unavailable -> notPublished("the upload rows could not be read", read.detail)
        }

    private suspend fun projected(attempt: Attempt, held: RecordRead, manifest: DeviceManifest): PublishOutcome {
        val body = ManifestBody.of(manifest)
        return when (holds(held, manifest, body)) {
            true -> PublishOutcome.Unchanged
            false -> withContext(NonCancellable) { answered(attempt, manifest, body) }
        }
    }

    // The version is in the comparison too: one version can name two snapshots, and only a higher one republishes the
    // newer when the older landed last (decision record `changes/manifest-versions`, D3).
    private fun holds(held: RecordRead, manifest: DeviceManifest, body: ManifestBody): Boolean =
        held is RecordRead.Held && held.version == manifest.version && held.body == body.text

    private suspend fun answered(attempt: Attempt, manifest: DeviceManifest, body: ManifestBody): PublishOutcome =
        when (val reply = publication.publish(attempt.run.membership.event.id, manifest, body)) {
            PublishReply.Published -> PublishOutcome.Published.also { recorded(attempt, manifest, body) }
            is PublishReply.Refused -> refused(attempt, manifest, body, reply.reason)
            is PublishReply.Unreachable -> notPublished("the backend could not be reached", reply.fault)
        }

    // A refusal for good is recorded like a publish: re-sending the same manifest could never change the answer.
    private suspend fun refused(
        attempt: Attempt,
        manifest: DeviceManifest,
        body: ManifestBody,
        reason: PublishRefusal,
    ) =
        when (reason) {
            PublishRefusal.EVENT_OVER -> over(attempt, manifest, body)
            PublishRefusal.SET_FIXED -> PublishOutcome.Published.also { recorded(attempt, manifest, body) }
            PublishRefusal.NOT_TAKEN -> notPublished("the backend refused the manifest", reason)
        }

    /** The server's word that the event's photos were deleted: recorded, and the membership marked so (over). */
    private suspend fun over(attempt: Attempt, manifest: DeviceManifest, body: ManifestBody): PublishOutcome {
        recorded(attempt, manifest, body)
        val run = attempt.run
        membership.markDeleted(run.membership, DeletionVerdict.answeredOver(run.membership.event).evidence)
        log.i("the event's photos are deleted — its membership is marked so")
        return PublishOutcome.EventOver
    }

    private suspend fun recorded(attempt: Attempt, manifest: DeviceManifest, body: ManifestBody) {
        val event = attempt.run.membership.event.id
        when (val written = record.record(event, manifest.version, manifest.declaration, manifest.assets.size, body)) {
            StoreWrite.Written -> log.i(
                "published",
                "version" of manifest.version.value,
                "assets" of manifest.assets.size,
                "settled" of (manifest.declaration is ShareDeclaration.Settled),
            )
            // Not recorded: the next pass publishes once more, which the backend takes as the same snapshot.
            is StoreWrite.Failed -> log.w("published, but not recorded", "detail" of written.detail)
        }
    }

    private suspend fun notPublished(why: String, detail: Any?): PublishOutcome =
        PublishOutcome.NotPublished.also { log.w("no manifest this pass", "why" of why, "detail" of detail) }

    /** One publish: its run and policy, and what it declares with the range standing at [phase]. */
    private class Attempt(
        val run: UploadGate.Run,
        val policy: SelectionPolicy,
        val phase: SharePhase,
        val declaration: ShareDeclaration,
    ) {
        fun manifest(rows: List<UploadRow>): DeviceManifest = DeviceManifest.of(
            run.device,
            rows,
            policy.admitRecorded(rows.map { it.recorded }),
            run.version,
            declaration,
        )
    }
}

/** What a publish came to. */
sealed interface PublishOutcome {
    /** The backend took it — or holds a set it will never change — and it is recorded. */
    data object Published : PublishOutcome

    /** The record already holds it: nothing was sent. */
    data object Unchanged : PublishOutcome

    /** The look settled nothing, and a settled share was sent: nothing was sent, which would only take it back. */
    data object KeptSettled : PublishOutcome

    /** The backend answered that the event's photos are deleted: recorded, and the membership marked so. */
    data object EventOver : PublishOutcome

    /** Nothing was published — something could not be read, or the backend did not take it: the next pass retries. */
    data object NotPublished : PublishOutcome
}
