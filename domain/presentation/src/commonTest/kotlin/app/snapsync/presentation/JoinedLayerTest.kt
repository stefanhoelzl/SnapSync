package app.snapsync.presentation

import app.snapsync.feature.download.readmodel.DownloadProgress
import app.snapsync.feature.membership.readmodel.RenameFailureReason
import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.MemberCounts
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SharingNote
import app.snapsync.ui.state.SyncHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val EVENT_ID = "44444444-4444-4444-8444-444444444444"

/** An open membership whose event is under way at the fixed clock (2026-07-09T12:00Z). */
private val MEMBERSHIP = EventConfig(
    eventId = EVENT_ID,
    name = "Lake Weekend",
    minPhotoDate = captureCutoff("2026-07-06T00:00:00Z"),
    startsAt = eventStart("2026-07-06T00:00:00Z"),
    endsAt = eventEnd("2026-07-13T00:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"),
    deletesAt = deletesAt("2026-08-05T00:00:00Z"),
)

/** The same membership once its event has ended (before the fixed clock), the event still open. */
private val ENDED = MEMBERSHIP.copy(
    endsAt = eventEnd("2026-07-08T00:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-08T00:00:00Z"),
)

/** Everything of the member's shared: the snapshot that reads in sync. */
private val SETTLED = SyncStatus.Ready(SyncProgress(0, 4, 4, 0, active = false, estimatedRemaining = null))

/**
 * What the joined layer carries beside its health (capability `sync-status`, `manage-membership`): who the event is
 * still waiting for, the invite, the rename dialog's state, the counts line's receive side, and the health rungs that
 * hold a membership on "loading". Each is read off the FIRST state the container holds — the reduction runs on the
 * sources' current values at construction, so no intent has to run for these.
 */
class JoinedLayerTest {

    private fun joined(
        scope: CoroutineScope,
        config: EventConfig = MEMBERSHIP,
        snapshot: SyncStatus = SETTLED,
        download: DownloadProgress = DownloadProgress(0, 0),
        permission: GalleryAccess = GalleryAccess.GRANTED,
        rename: RenameStatus = RenameStatus.Idle,
        inviteKey: String? = null,
        configFlow: MutableStateFlow<EventConfig?> = MutableStateFlow(config),
    ) = StatusContainerHost(
        StatusSources(
            FixedSync(snapshot),
            MutableStateFlow(permission),
            configFlow,
            rename = MutableStateFlow(rename),
            download = MutableStateFlow(download),
            eventKey = EventKeyView(inviteKey = MutableStateFlow(inviteKey)),
        ),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = testCommands(),
        queries = noQueries,
        diagnostics = testDiagnostics(),
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
                snapshot = SyncStatus.Ready(SyncProgress(1, 3, 4, 0, active = true, estimatedRemaining = null)),
            ),
            "the counts are unknown" to joined(backgroundScope, ENDED),
            "photo access is missing" to joined(
                backgroundScope,
                ENDED.copy(members = waiting),
                permission = GalleryAccess.DENIED,
            ),
        )
        for ((case, host) in notWhen) assertNull(host.layer().sharingNote, case)
    }

    @Test
    fun `once nobody is left to wait for everyone has shared and a late guest turns it back`() = runTest {
        val config = MutableStateFlow<EventConfig?>(ENDED.copy(members = MemberCounts(3, 3, waiting = 0)))
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
        val backDated = ENDED.copy(openUntil = eventEnd("2026-07-15T12:00:00Z"))
        assertEquals(
            LocalDateTime.parse("2026-07-15T12:00"),
            joined(backgroundScope, backDated).layer().joinableUntil,
        )
        val notWhen = mapOf(
            "a regular event" to ENDED.copy(openUntil = ENDED.endsAt),
            "no open-until date served" to ENDED,
            "the day has passed" to ENDED.copy(openUntil = eventEnd("2026-07-09T06:00:00Z")),
            "the event's photos were deleted" to backDated.copy(deleted = true),
        )
        for ((case, config) in notWhen) assertNull(joined(backgroundScope, config).layer().joinableUntil, case)
    }

    // ---- the invite --------------------------------------------------------------------------------

    @Test
    fun `an encrypted event's invite carries its key`() = runTest {
        val host = joined(backgroundScope, MEMBERSHIP.copy(keyId = "k1"), inviteKey = INVITE_KEY)

        val layer = host.screen.first { (it.layer as? Layer.Joined)?.inviteUrl?.contains("#k=") == true }
            .layer as Layer.Joined

        assertEquals(INVITE_KEY, decodedKey(layer.inviteUrl!!))
    }

    @Test
    fun `a plain event's invite carries no key — even one read for it`() = runTest {
        // The key cell still holds a key once the membership names no encrypted event — the invite must drop it.
        val config = MutableStateFlow<EventConfig?>(MEMBERSHIP.copy(keyId = "k1"))
        val host = joined(backgroundScope, inviteKey = INVITE_KEY, configFlow = config)
        host.screen.first { (it.layer as? Layer.Joined)?.inviteUrl?.contains("#k=") == true }

        config.value = MEMBERSHIP.copy(name = "Plain Weekend")
        val layer = host.screen.first {
            (it.layer as? Layer.Joined)?.membership?.name == "Plain Weekend"
        }.layer as Layer.Joined

        assertNull(decodedKey(layer.inviteUrl!!))
        assertFalse("#k=" in layer.inviteUrl!!)
    }

    // ---- the rename dialog -------------------------------------------------------------------------

    @Test
    fun `the rename dialog reads every rename status and names why one failed`() = runTest {
        val expected = mapOf(
            RenameStatus.Idle to RenameState.Idle,
            RenameStatus.InFlight to RenameState.InFlight,
            RenameStatus.Succeeded to RenameState.Succeeded,
            RenameStatus.Failed(RenameFailureReason.INVALID_NAME) to RenameState.Failed(ScreenMessage.RENAME_NAME_REFUSED),
            RenameStatus.Failed(RenameFailureReason.SERVER) to RenameState.Failed(ScreenMessage.RENAME_FAILED),
        )
        for ((status, state) in expected) {
            assertEquals(state, joined(backgroundScope, rename = status).layer().renameState, "$status")
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
                snapshot = SyncStatus.Ready(SyncProgress(0, 3, 4, 0, active = false, estimatedRemaining = null)),
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
            snapshot = SyncStatus.Loading,
            permission = GalleryAccess.DENIED,
        )

        assertEquals(SyncHealth.Loading, host.layer().health)
    }

    private fun decodedKey(url: String): String? =
        assertIs<app.snapsync.model.ConfigDecodeResult.Success>(decodeEventUrl(url)).payload.key

    private companion object {
        val INVITE_KEY = app.snapsync.model.encodeEventKey(ByteArray(32) { (it * 7).toByte() })
    }
}
