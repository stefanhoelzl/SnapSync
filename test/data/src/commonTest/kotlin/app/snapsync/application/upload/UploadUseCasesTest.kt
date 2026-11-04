package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.text
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.sharing.BEFORE
import app.snapsync.data.sharing.DURING
import app.snapsync.data.sharing.Library
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.ExtensionRegistrar
import app.snapsync.data.upload.FinishedAnswer
import app.snapsync.data.upload.JobAcknowledgement
import app.snapsync.data.upload.JobOwners
import app.snapsync.data.upload.OsQueueDrain
import app.snapsync.data.upload.OsQueueTier
import app.snapsync.data.upload.OwnPhotoResources
import app.snapsync.data.upload.RecordingQueue
import app.snapsync.data.upload.RowTransition
import app.snapsync.data.upload.SessionEnd
import app.snapsync.data.upload.SessionWorld
import app.snapsync.data.wake.WakeArming
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.MintsConfirmedNotJoined
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.upload.CreatedJob
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.MintsCreatedJob
import app.snapsync.domain.upload.MintsEndedJob
import app.snapsync.domain.upload.ReportedLanding
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadPass
import app.snapsync.domain.upload.UploadRow
import app.snapsync.domain.upload.UploaderArming
import app.snapsync.domain.upload.UploaderChange
import app.snapsync.format.TimestampText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.mobileDataAnswering
import app.snapsync.mock.osUploaderOver
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.RegistrationAnswer
import app.snapsync.ports.RegistrationState
import app.snapsync.ports.ScheduleResult
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import app.snapsync.ports.WakeId
import app.snapsync.ports.WakeTrigger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The upload's use cases over the real stores and tiers: a walk decided and recorded, a top-up planned by the policy
 * and created until the platform refuses, the OS queue settled, a session end recorded, in-flight rows settled from the
 * backend's listing, and what a membership transition does to the two uploaders.
 */
@OptIn(EntryPointCall::class, MintsConfirmedNotJoined::class, MintsCreatedJob::class, MintsEndedJob::class)
class UploadUseCasesTest {

    private val world = SessionWorld()
    private val library = Library(LibraryAssets.photo("A1", DURING))
    private val resources = OwnPhotoResources(library.own)
    private val walk = WalkOwnPhotos(library.own, world.reads, resources)
    private val recent = app.snapsync.data.upload.RecentWalk()
    private val record = RecordWalk(world.walk, recent)
    private val plan = PlanTopUp(world.reads)
    private val create = CreateJobs(world.reads, world.tier, resources, world.walk, recent)

    /** The photos placed just before their jobs, in order. */
    private val placed = mutableListOf<AssetId>()
    private val before = object : BeforeJob {
        override suspend fun before(asset: AssetId) {
            placed += asset
        }
    }

    private fun key(asset: String) = UploadKey.of(AssetId.parse(asset)!!, ResourceRole.PRIMARY, "IMG.JPG")

    private fun rowOf(asset: String): UploadRow? =
        (
            world.reads.rows(
                world.event,
            ) as app.snapsync.data.upload.RowsRead.Read
            ).rows.firstOrNull { it.key == key(asset) }

    private fun dated(at: String) = RowDetail(CaptureDate.Dated(TimestampText.parse(at)), "image/jpeg", "IMG.JPG")

    @Test
    fun `a walk records the photos it admits, a top-up creates their jobs from the handles it read`() = runTest {
        world.joined()
        val walked = walk(world.event, library.contribution())

        assertEquals(listOf(key("A1")), walked.diff.fresh.map { it.key })
        assertTrue(record(world.event, walked).added)
        val planned = plan(world.event, library.contribution().policy)
        assertEquals(listOf(key("A1")), planned.rows.map { it.key })

        assertEquals(UploadPass.CAUGHT_UP, create(world.event, planned, before))
        assertIs<UploadRow.Requested>(rowOf("A1"))

        val again = walk(world.event, library.contribution())
        assertEquals(emptyList(), again.diff.fresh)
        assertEquals(false, record(world.event, again).added)
    }

    @Test
    fun `a photo that left the library is withdrawn by the walk that shows it gone`() = runTest {
        world.joined()
        record(world.event, walk(world.event, library.contribution()))
        library.photos.value = emptyList()

        val gone = walk(world.event, library.contribution())

        assertEquals(listOf(key("A1")), gone.diff.departed)
        record(world.event, gone)
        assertNull(rowOf("A1"))
    }

    @Test
    fun `a walk withdraws only within its window - a row before the range or still bare is kept`() = runTest {
        world.joined()
        world.walk.discover(
            world.event,
            listOf(
                FoundResource(key("EARLY"), dated(BEFORE)),
                FoundResource(key("BARE"), RowDetail(CaptureDate.Undated, "image/jpeg", "IMG.JPG")),
                FoundResource(key("GONE"), dated(DURING)),
            ),
        )

        val walked = walk(world.event, library.contribution())

        assertEquals(listOf(key("GONE")), walked.diff.departed, "only the in-range row the walk did not see")
        record(world.event, walked)
        assertIs<UploadRow.Discovered>(rowOf("EARLY"), "a row before the range is not the walk's to judge")
        assertIs<UploadRow.Discovered>(rowOf("BARE"), "a bare row is never withdrawn by a walk")
        assertNull(rowOf("GONE"))
    }

    @Test
    fun `a walk that cannot read the library or the rows writes nothing`() = runTest {
        world.joined()
        library.grant.value = LibraryGrant.DENIED
        val unread = walk(world.event, library.contribution())
        assertEquals(emptyList(), unread.diff.fresh + unread.diff.bare)

        world.databases.lock()
        assertEquals(
            emptyList(),
            walk(world.event, library.contribution()).diff.departed,
            "rows it cannot read: nothing walked",
        )
        assertTrue(record(world.event, unread).added.not(), "nothing to add, and a failed write logged")
        assertEquals(emptyList(), plan(world.event, library.contribution().policy).rows)
    }

    @Test
    fun `a top-up admits by the policy now, looks a photo up again, withdraws a gone one and stops when full`() = runTest {
        world.joined()
        world.walk.discover(
            world.event,
            listOf(
                FoundResource(key("A1"), dated(DURING)),
                FoundResource(key("GONE"), dated(DURING)),
                FoundResource(key("BEFORE"), dated("2026-06-01T00:00:00Z")),
            ),
        )
        val planned = plan(world.event, library.contribution().policy)
        assertEquals(listOf(key("A1"), key("GONE")), planned.rows.map { it.key })

        assertEquals(UploadPass.CAUGHT_UP, create(world.event, planned, before))
        assertIs<UploadRow.Requested>(rowOf("A1"))
        assertNull(rowOf("GONE"), "a photo that resolves to nothing has left the library")
        assertEquals(listOf(AssetId.parse("A1")!!), placed, "placed just before its job — never a photo that is gone")

        world.walk.discover(world.event, listOf(FoundResource(key("A2"), dated(DURING))))
        world.session.answer = CreateOutcome.LIMIT_EXCEEDED
        val full = plan(world.event, library.contribution().policy)
        assertEquals(listOf(key("A2")), full.rows.map { it.key })
        recent.put(mapOf(key("A2") to SessionWorld.From))
        assertEquals(UploadPass.WORK_LEFT, create(world.event, full, before))
        assertIs<UploadRow.Discovered>(rowOf("A2"), "a refused create leaves the row needing a job")
    }

    @Test
    fun `the platform's first refusal ends the top-up - no later row is tried, placed or looked up`() = runTest {
        world.joined()
        world.walk.discover(world.event, listOf("A1", "A2", "A3").map { FoundResource(key(it), dated(DURING)) })
        recent.put(mapOf(key("A1") to SessionWorld.From))
        world.session.answer = CreateOutcome.LIMIT_EXCEEDED

        assertEquals(
            UploadPass.WORK_LEFT,
            create(world.event, plan(world.event, library.contribution().policy), before),
        )

        assertEquals(1, world.session.made.size, "one refusal is the signal")
        assertEquals(listOf(AssetId.parse("A1")!!), placed)
        listOf("A1", "A2", "A3").forEach { assertIs<UploadRow.Discovered>(rowOf(it), it) }
    }

    @Test
    fun `a row the other uploader took, or a walk withdrew, since the plan is not created again`() = runTest {
        world.joined()
        world.walk.discover(
            world.event,
            listOf("A1", "A2", "A3").map { FoundResource(key(it), dated(DURING)) },
        )
        val planned = plan(world.event, library.contribution().policy)
        recent.put(listOf("A1", "A2", "A3").associate { key(it) to SessionWorld.From })
        // Both uploaders write the one shared ledger: the OS's queue took A1's job while this pass planned.
        world.transitions.requested(world.event, CreatedJob(key("A1")), DestinationPath("/other"))
        world.walk.withdraw(world.event, listOf(key("A2")))
        // A3's job was made and has already landed.
        world.transitions.requested(world.event, CreatedJob(key("A3")), DestinationPath("/other"))
        world.transitions.landed(world.event, ReportedLanding(key("A3")))

        assertEquals(UploadPass.CAUGHT_UP, create(world.event, planned, before))
        assertTrue(world.session.made.isEmpty(), "no second job for the same bytes")
        assertTrue(placed.isEmpty(), "and nothing placed for a job not made")

        world.databases.lock()
        val unread = create(world.event, planned, before)
        assertEquals(UploadPass.CAUGHT_UP, unread, "a row that cannot be read is not created")
    }

    @Test
    fun `a top-up keeps a row whose library cannot be read now`() = runTest {
        world.joined()
        world.walk.discover(world.event, listOf(FoundResource(key("A1"), dated(DURING))))
        val planned = plan(world.event, library.contribution().policy)
        library.grant.value = LibraryGrant.DENIED

        assertEquals(UploadPass.CAUGHT_UP, create(world.event, planned, before))

        assertIs<UploadRow.Discovered>(rowOf("A1"))
        assertEquals(emptyList(), world.session.made)
    }

    @Test
    fun `the OS queue is settled - retries re-pointed, ends recorded, held failures re-created, as the gate allows`() =
        runTest {
            world.joined()
            val queue = RecordingQueue()
            val tier =
                OsQueueTier(queue, world.requests, world.sealing, mobileDataAnswering { world.network }, world.record)
            val drain = OsQueueDrain(queue, JobOwners(world.reads), JobAcknowledgement(queue), world.record)
            val settle = SettlePresentedJobs(drain, tier, world.reads)
            tier.create(world.event, world.discovered("A1"), SessionWorld.From)
            tier.create(world.event, world.discovered("A2"), SessionWorld.From)
            val url = { i: Int -> queue.created[i].second.url }
            fun job(i: Int, held: Boolean = true) =
                OsUploadJob(
                    RecordingQueue.JobHandle,
                    url(i),
                    null,
                    UploadJobState.FAILED,
                    null,
                    if (held) SessionWorld.Handle else null,
                )

            queue.offered = listOf(job(0))
            queue.terminals = listOf(job(1))
            assertEquals(UploadPass.CAUGHT_UP, settle(world.event, Presented.RUN))
            assertEquals(1, queue.retried.size)
            assertEquals(3, queue.created.size, "the held failure is re-created at once")

            queue.terminals = listOf(job(2, held = false))
            assertEquals(UploadPass.CAUGHT_UP, settle(world.event, Presented.DECLINED))
            assertEquals(3, queue.created.size, "a retry-spent job waits for the top-up")

            queue.terminals = listOf(job(1))
            queue.answer = CreateOutcome.LIMIT_EXCEEDED
            tier.create(world.event, world.discovered("A3"), SessionWorld.From)
            assertEquals(UploadPass.CAUGHT_UP, settle(world.event, Presented.WITHHELD))
            assertEquals(1, queue.retried.size, "a withheld pass re-points nothing")
        }

    @Test
    fun `a held failure whose row moved on, or cannot be read, is not re-created`() = runTest {
        world.joined()
        val queue = RecordingQueue()
        val tier =
            OsQueueTier(queue, world.requests, world.sealing, mobileDataAnswering { world.network }, world.record)
        val drain = OsQueueDrain(queue, JobOwners(world.reads), JobAcknowledgement(queue), world.record)
        tier.create(world.event, world.discovered("A1"), SessionWorld.From)
        val failed = {
            OsUploadJob(
                RecordingQueue.JobHandle,
                queue.created[0].second.url,
                null,
                UploadJobState.FAILED,
                null,
                SessionWorld.Handle,
            )
        }
        val elsewhere = SessionWorld().reads
        val locked = SessionWorld().also { it.databases.lock() }.reads

        queue.terminals = listOf(failed())
        listOf(elsewhere, locked).forEach { rows ->
            assertEquals(UploadPass.CAUGHT_UP, SettlePresentedJobs(drain, tier, rows)(world.event, Presented.RUN))
        }
        assertEquals(1, queue.created.size, "a row another process withdrew, or one that cannot be read")

        tier.create(world.event, world.row("A1") as UploadRow.Discovered, SessionWorld.From)
        world.record.ended(world.event, world.key("A1"), app.snapsync.domain.upload.TransferEnd.LANDED)
        assertEquals(UploadPass.CAUGHT_UP, SettlePresentedJobs(drain, tier, world.reads)(world.event, Presented.RUN))
        assertEquals(2, queue.created.size, "a row a landing settled first is not re-created")
    }

    @Test
    fun `a held failure the queue takes no more of leaves work`() = runTest {
        world.joined()
        val queue = RecordingQueue()
        val tier =
            OsQueueTier(queue, world.requests, world.sealing, mobileDataAnswering { world.network }, world.record)
        val drain = OsQueueDrain(queue, JobOwners(world.reads), JobAcknowledgement(queue), world.record)
        tier.create(world.event, world.discovered("A1"), SessionWorld.From)
        queue.terminals = listOf(
            OsUploadJob(RecordingQueue.JobHandle, queue.created[0].second.url, null, UploadJobState.FAILED, null, SessionWorld.Handle),
        )
        queue.answer = CreateOutcome.LIMIT_EXCEEDED

        assertEquals(UploadPass.WORK_LEFT, SettlePresentedJobs(drain, tier, world.reads)(world.event, Presented.RUN))
    }

    @Test
    fun `a session end is recorded against the event joined now, and against none after a leave`() = runTest {
        val finished = RecordFinishedUpload(world.ends, world.membership)
        assertEquals(FinishedAnswer.Unjoined, finished(SessionEnd("x", null)))

        world.joined()
        world.tier.create(world.event, world.discovered(), SessionWorld.From)
        val answer = finished(SessionEnd(world.tag(), TransferFailure("cut")))
        assertEquals(RowTransition.Transitioned, assertIs<FinishedAnswer.Recorded>(answer).recorded)

        world.databases.lock()
        assertEquals(FinishedAnswer.Unjoined, finished(SessionEnd(world.tag(), null)))
    }

    @Test
    fun `in-flight rows the backend already stores are settled, and nothing is asked with none in flight`() = runTest {
        world.joined()
        val listing =
            DeviceListing(BackendGateway(exchangeOver(world.transport), ScriptedCredential("T1")), world.identity)
        val settle = SettleStoredUploads(world.reads, listing, world.transitions)
        world.transport.answer = { json(200, """[{"assetId":"A1","role":"primary","filename":"IMG.MOV"}]""") }

        settle(world.event)
        assertEquals(emptyList(), world.transport.calls)

        world.tier.create(world.event, world.discovered(), SessionWorld.From)
        settle(world.event)
        assertIs<UploadRow.Completed>(world.row())

        world.tier.create(world.event, world.discovered("A2"), SessionWorld.From)
        world.transport.answer = { text(502, "down") }
        settle(world.event)
        assertIs<UploadRow.Requested>(world.row("A2"))

        // A listing that names other keys, or that this build cannot read, settles nothing.
        world.transport.answer = { json(200, """[{"assetId":"A9","role":"primary","filename":"IMG.MOV"}]""") }
        settle(world.event)
        assertIs<UploadRow.Requested>(world.row("A2"))
        world.transport.answer = { json(200, """[{"assetId":"bad id!","role":"primary","filename":"IMG.MOV"}]""") }
        settle(world.event)
        assertIs<UploadRow.Requested>(world.row("A2"))

        // While nothing is joined, nothing is asked.
        val asked = world.transport.calls.size
        settle(null)
        assertEquals(asked, world.transport.calls.size)

        world.databases.lock()
        settle(world.event)
    }

    @Test
    fun `a listing that never answers is given up on, settling nothing`() = runTest {
        world.joined()
        world.tier.create(world.event, world.discovered(), SessionWorld.From)
        val stuck = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResult = awaitCancellation()
        }
        val listing = DeviceListing(BackendGateway(exchangeOver(stuck), ScriptedCredential("T1")), world.identity)

        SettleStoredUploads(world.reads, listing, world.transitions)(world.event)

        assertIs<UploadRow.Requested>(world.row())
    }

    private class Registry : ExtensionRegistry {
        val writes = mutableListOf<Boolean>()
        override val supported = true

        override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
            writes += enabled
            return RegistrationAnswer.Answered(ok = true, domain = null, code = null)
        }

        override fun isEnabled(): RegistrationState = RegistrationState.REGISTERED
    }

    @Test
    fun `a membership transition registers the extension where it calls for it, and arms by the grant`() = runTest {
        val grant = MutableStateFlow(LibraryGrant.GRANTED)
        var state = RegistrationState.NOT_REGISTERED
        val registry = Registry()
        val reconcile = ReconcileUploader(
            world.membership,
            osUploaderOver(supported = true, grant, registered = { state }),
            GrantRead(grant),
            ExtensionRegistrar(registry),
        )

        assertEquals(
            UploaderArming.UNCHANGED,
            reconcile(UploaderChange.RECONFIGURE),
            "no membership: nothing to arm for",
        )
        assertEquals(UploaderArming.ARM, reconcile(UploaderChange.JOIN))
        assertEquals(listOf(false, true), registry.writes, "a join forces the toggle")

        world.joined()
        assertEquals(UploaderArming.ARM, reconcile(UploaderChange.LAUNCH))
        assertEquals(4, registry.writes.size, "a record the OS reads absent is registered")
        state = RegistrationState.REGISTERED
        assertEquals(UploaderArming.ARM, reconcile(UploaderChange.GRANT_CHANGED))
        assertEquals(4, registry.writes.size, "a record the OS confirms is left")

        grant.value = LibraryGrant.DENIED
        assertEquals(UploaderArming.DISARM, reconcile(UploaderChange.RECONFIGURE))
        world.databases.lock()
        assertEquals(UploaderArming.UNCHANGED, reconcile(UploaderChange.LAUNCH), "an unreadable membership defers")
    }

    @Test
    fun `a grant that narrows between the admission and the register registers nothing`() = runTest {
        val grant = MutableStateFlow(LibraryGrant.GRANTED)
        val registry = Registry()
        val narrowed = MutableStateFlow(LibraryGrant.LIMITED)
        val reconcile = ReconcileUploader(
            world.membership,
            osUploaderOver(supported = true, grant),
            GrantRead(narrowed),
            ExtensionRegistrar(registry),
        )

        reconcile(UploaderChange.JOIN)

        assertEquals(emptyList(), registry.writes)
    }

    private class RecordingWake : Wake {
        val cancelled = mutableListOf<WakeId>()

        @CompositionOnly
        override fun inbound(handlers: WakeHandlers) = Unit

        override suspend fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult = ScheduleResult.Scheduled

        override fun cancel(id: WakeId) {
            cancelled += id
        }
    }

    @Test
    fun `a membership's uploads end - the extension deregistered, the wakes and the transfers cancelled`() = runTest {
        world.joined()
        world.tier.create(world.event, world.discovered(), SessionWorld.From)
        world.session.live = listOf(Transfer(app.snapsync.data.upload.RecordingSession.Handle, world.tag()))
        val registry = Registry()
        val wake = RecordingWake()

        StopUploads(ExtensionRegistrar(registry), WakeArming(wake), world.ends)(ConfirmedNotJoined())

        assertEquals(listOf(false), registry.writes)
        assertEquals<List<WakeId>>(WakeId.entries.toList(), wake.cancelled)
        assertEquals(world.session.live, world.session.cancelled)
    }

    @Test
    fun `a contribution is derived with the photos this device received suppressed`() = runTest {
        library.received.handles = app.snapsync.data.receiving.HandlesRead.Ok(setOf(AssetId.parse("A1")!!))
        world.joined()
        val contribution = DeriveContribution(library.own)(app.snapsync.data.membership.active())

        val walked = walk(world.event, contribution)
        assertEquals(emptyList(), walked.diff.fresh, "a received photo is never shared back")

        val receiving = app.snapsync.data.membership.active().let {
            it.copy(share = app.snapsync.data.membership.share(it.event, direction = Direction.DownloadOnly))
        }
        assertEquals(
            false,
            DeriveContribution(library.own)(receiving).policy.contributes,
            "it shares nothing",
        )
    }
}
