package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.AccessFix
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SharingNote
import app.snapsync.ui.state.SyncHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val EVENT_ID = "44444444-4444-4444-8444-444444444444"

/** An open membership whose event is under way at the fixed clock (2026-07-09T12:00Z). */
private val MEMBERSHIP = TestMembership(
    eventId = testEventId(EVENT_ID),
    name = "Lake Weekend",
    minPhotoDate = CaptureCutoff(timestamp("2026-07-06T00:00:00Z")),
    startsAt = EventStart(timestamp("2026-07-06T00:00:00Z")),
    endsAt = EventEnd(timestamp("2026-07-13T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(timestamp("2026-07-13T00:00:00Z")),
    deletesAt = DeletesAt(timestamp("2026-08-05T00:00:00Z")),
)

/** The same membership once its event has ended (before the fixed clock), the event still open. */
private val ENDED = MEMBERSHIP.copy(
    endsAt = EventEnd(timestamp("2026-07-08T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(timestamp("2026-07-08T00:00:00Z")),
)

/** Everything of the member's shared: the snapshot that reads in sync. */
private val SETTLED = SharingProgressRead.Read(SharingProgress(shared = 4, total = 4, handedOver = 0))

/**
 * What the joined layer carries beside its health (capability `sync-status`, `manage-membership`): who the event is
 * still waiting for, the invite, the rename dialog's state, the counts line's receive side, and the health rungs that
 * hold a membership on "loading". Each is read off the FIRST state the container holds — the reduction runs on the
 * sources' current values at construction, so no intent has to run for these.
 */
class JoinedLayerTest {

    private fun joined(
        scope: CoroutineScope,
        config: TestMembership = MEMBERSHIP,
        snapshot: SharingProgressRead = SETTLED,
        download: DownloadProgress = DownloadProgress(0, 0),
        permission: PhotoGrant = PhotoGrant.FULL,
        commands: TestCommands = TestCommands(),
        configFlow: MutableStateFlow<TestMembership?> = MutableStateFlow(config),
    ) = testHost(
        testQueries(
            FixedSync(snapshot),
            MutableStateFlow(permission),
            configFlow.held(),
            requests = commands.requests.flows,
            download = MutableStateFlow(download),
            eventKey = configFlow.plainKeys(),
            network = AlwaysOnline,
            mobileData = AlwaysAllowed,
        ),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = commands.door,
        shareableCount = noQueries,
        joinDetails = unanswered,
        reduction = Dispatchers.Default,
    )

    private fun StatusContainerHost.layer(): Layer.Joined = assertIs<Layer.Joined>(screen.value.layer)

    // ---- who the event is waiting for ------------------------------------------------------------

    @Test
    fun `who the event waits for is told only to an in-sync member of an ended event`() = runTest {
        val waiting = MemberCounts(active = 3, settled = 1, waiting = 2)
        assertEquals(
            SharingNote.WaitingFor(2),
            joined(backgroundScope, ENDED.copy(members = waiting)).layer().sharingNote,
        )

        val notWhen = mapOf(
            "the event is still under way" to joined(backgroundScope, MEMBERSHIP.copy(members = waiting)),
            "the event's photos were deleted" to joined(backgroundScope, ENDED.copy(members = waiting, deleted = true)),
            "this member is still sharing" to joined(
                backgroundScope,
                ENDED.copy(members = waiting),
                snapshot = SharingProgressRead.Read(SharingProgress(shared = 3, total = 4, handedOver = 1)),
            ),
            "the counts are unknown" to joined(backgroundScope, ENDED),
            "photo access is missing" to joined(
                backgroundScope,
                ENDED.copy(members = waiting),
                permission = PhotoGrant.REFUSED,
            ),
        )
        for ((case, host) in notWhen) assertNull(host.layer().sharingNote, case)
    }

    @Test
    fun `once nobody is left to wait for everyone has shared and a late guest turns it back`() = runTest {
        val config = MutableStateFlow<TestMembership?>(ENDED.copy(members = MemberCounts(3, 3, waiting = 0)))
        val host = joined(backgroundScope, configFlow = config)
        assertEquals(SharingNote.EveryoneShared, host.layer().sharingNote)

        config.value = ENDED.copy(members = MemberCounts(4, 3, waiting = 1))
        assertEquals(
            SharingNote.WaitingFor(1),
            host.screen.first { (it.layer as? Layer.Joined)?.sharingNote is SharingNote.WaitingFor }
                .let { (it.layer as Layer.Joined).sharingNote },
        )
    }

    // ---- members, the deleted event, the back-dated line --------------------------------------------

    @Test
    fun `a deleted event's counts are what it came to, whatever its line says — once they are counted`() = runTest {
        val over = ENDED.copy(deleted = true)
        val received = DownloadProgress(downloaded = 2, total = 3)
        // No library access is a line of its own, which on a live event hides the counts.
        val counted = joined(backgroundScope, over, permission = PhotoGrant.REFUSED, download = received).layer()
        assertEquals(DirectionCount.Progress(done = 4, total = 4), counted.counts?.shared)
        assertEquals(DirectionCount.Progress(done = 2, total = 3), counted.counts?.received)

        val uncounted = joined(backgroundScope, over, download = DownloadProgress.UNREAD).layer()
        assertNull(uncounted.counts, "no count this process never read")
    }

    @Test
    fun `what gives the app its access is read from the grant, whatever the line — and not while both are off`() =
        runTest {
            assertNull(joined(backgroundScope, permission = PhotoGrant.FULL).layer().accessFix)
            assertEquals(AccessFix.ASK, joined(backgroundScope, permission = PhotoGrant.UNDECIDED).layer().accessFix)
            assertEquals(AccessFix.SETTINGS, joined(backgroundScope, permission = PhotoGrant.REFUSED).layer().accessFix)
            val off = MEMBERSHIP.copy(direction = Direction.Neither)
            assertNull(
                joined(backgroundScope, off, permission = PhotoGrant.REFUSED).layer().accessFix,
                "a choice, not a fix",
            )
        }

    @Test
    fun `the member count is the event's present members and none once its photos are deleted`() = runTest {
        assertEquals(4, joined(backgroundScope, MEMBERSHIP.copy(members = MemberCounts(4, 0))).layer().members)
        assertNull(joined(backgroundScope, MEMBERSHIP).layer().members, "not read yet")
        val deleted = joined(backgroundScope, ENDED.copy(members = MemberCounts(4, 4), deleted = true)).layer()
        assertTrue(deleted.deleted)
        assertNull(deleted.members)
    }

    @Test
    fun `a back-dated event says until when others can join while that day is ahead`() = runTest {
        // Clock: 2026-07-09T12:00Z. ENDED ends 2026-07-08.
        val backDated = ENDED.copy(openUntil = OpenUntil(timestamp("2026-07-15T12:00:00Z")))
        assertEquals(
            LocalDateTime.parse("2026-07-15T12:00"),
            joined(backgroundScope, backDated).layer().joinableUntil,
        )
        val notWhen = mapOf(
            "a regular event" to ENDED.copy(openUntil = OpenUntil(ENDED.endsAt.at)),
            "no open-until date served" to ENDED,
            "the day has passed" to ENDED.copy(openUntil = OpenUntil(timestamp("2026-07-09T06:00:00Z"))),
            "the event's photos were deleted" to backDated.copy(deleted = true),
        )
        for ((case, config) in notWhen) assertNull(joined(backgroundScope, config).layer().joinableUntil, case)
    }

    // ---- the invite --------------------------------------------------------------------------------

    // ---- the rename dialog -------------------------------------------------------------------------

    @Test
    fun `the rename dialog reads every rename answer and names why one failed`() = runTest {
        assertEquals(RenameState.Idle, joined(backgroundScope).layer().renameState, "before any rename")
        val expected = mapOf(
            null to RenameState.InFlight,
            RenameResult.Renamed to RenameState.Succeeded,
            RenameResult.InvalidName to RenameState.Failed(ScreenMessage.RENAME_NAME_REFUSED),
            RenameResult.Failed() to RenameState.Failed(ScreenMessage.RENAME_FAILED),
            // A failing service is told as what it is (decision record `changes/tell-service-faults`).
            RenameResult.Failed(ServiceFault.UPDATING) to RenameState.Failed(ScreenMessage.SERVICE_UPDATING),
            RenameResult.EventOver to RenameState.Failed(ScreenMessage.EVENT_OVER),
        )
        for ((answer, state) in expected) {
            val host = joined(backgroundScope, commands = TestCommands(rename = { _, _ -> answer }))
            host.onRenameEvent(MEMBERSHIP.eventId.value, "Lake week")
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { host.screen.first { (it.layer as? Layer.Joined)?.renameState == state } }
            }
        }
    }

    // ---- the counts line's receive side -------------------------------------------------------------

    @Test
    fun `a share-only membership still counts photos it is receiving rather than calling receiving off`() = runTest {
        // Receiving was switched off with downloads still draining: the line shows them, it does not hide them.
        val host = joined(
            backgroundScope,
            MEMBERSHIP.copy(direction = Direction.UploadOnly),
            download = DownloadProgress(downloaded = 1, total = 3),
        )

        assertEquals(DirectionCount.Progress(done = 1, total = 3), host.layer().counts?.received)
    }

    @Test
    fun `receiving switched off once everything arrived keeps the count of what arrived`() = runTest {
        val host = joined(
            backgroundScope,
            MEMBERSHIP.copy(direction = Direction.UploadOnly),
            download = DownloadProgress(downloaded = 40, total = 40),
        )

        assertEquals(DirectionCount.Kept(40), host.layer().counts?.received)
    }

    @Test
    fun `receiving switched off having received nothing is only off`() = runTest {
        val host = joined(backgroundScope, MEMBERSHIP.copy(direction = Direction.UploadOnly))

        assertEquals(DirectionCount.Off, host.layer().counts?.received)
    }

    // ---- the others' declared photos (change `count-declared-photos`) --------------------------------

    @Test
    fun `photos still uploading on another phone count toward what there is to receive`() = runTest {
        // The sharer's 200, 7 offered here, 6 received: 6 of 200, and only the others are left.
        val host = joined(backgroundScope, download = DownloadProgress(downloaded = 6, total = 6, awaited = 194))

        assertEquals(DirectionCount.Progress(done = 6, total = 200), host.layer().counts?.received)
        assertEquals(
            SyncHealth.Syncing(upload = Arrow.HIDDEN, download = Arrow.STATIC, waitingForOthers = true),
            host.layer().health,
        )
    }

    @Test
    fun `this device's own work is never taken for waiting on the others`() = runTest {
        val awaiting = DownloadProgress(downloaded = 6, total = 6, awaited = 10)
        val notWaitingForOthers = mapOf(
            "a photo of its own waits to upload" to joined(
                backgroundScope,
                snapshot = SharingProgressRead.Read(SharingProgress(shared = 3, total = 4, handedOver = 0)),
                download = awaiting,
            ),
            "an offered photo is not here yet" to joined(
                backgroundScope,
                download = DownloadProgress(downloaded = 5, total = 6, awaited = 10),
            ),
            "a download is running" to joined(
                backgroundScope,
                download = DownloadProgress(downloaded = 5, total = 6, inFlight = 1, awaited = 10),
            ),
        )
        for ((case, host) in notWaitingForOthers) {
            assertFalse(assertIs<SyncHealth.Syncing>(host.layer().health).waitingForOthers, case)
        }
    }

    @Test
    fun `an ended event says whom it waits for beside waiting for photos too`() = runTest {
        val host = joined(
            backgroundScope,
            ENDED.copy(members = MemberCounts(active = 2, settled = 1, waiting = 1)),
            download = DownloadProgress(downloaded = 3, total = 3, awaited = 4),
        )

        assertEquals(SharingNote.WaitingFor(1), host.layer().sharingNote)
    }

    @Test
    fun `nothing awaited and everything here reads up to date`() = runTest {
        val host = joined(backgroundScope, download = DownloadProgress(downloaded = 6, total = 6, awaited = 0))

        assertEquals(SyncHealth.InSync, host.layer().health)
        assertEquals(DirectionCount.Progress(done = 6, total = 6), host.layer().counts?.received)
    }

    // ---- what holds a membership on loading --------------------------------------------------------

    @Test
    fun `a read snapshot waits on the receive side's first read`() = runTest {
        val host = joined(backgroundScope, download = DownloadProgress(0, 0, read = false))

        assertEquals(SyncHealth.Loading, host.layer().health)
    }

    @Test
    fun `a membership that neither shares nor receives is loading until read whatever else is wrong`() = runTest {
        // Access, the network and verification concern photos that no longer move; until the snapshot is read,
        // there is nothing to say yet — not a missing permission.
        val host = joined(
            backgroundScope,
            MEMBERSHIP.copy(direction = Direction.Neither),
            snapshot = SharingProgressRead.NotYetRead,
            permission = PhotoGrant.REFUSED,
        )

        assertEquals(SyncHealth.Loading, host.layer().health)
    }
}
