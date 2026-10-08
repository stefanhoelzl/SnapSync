package app.snapsync.presentation

import app.snapsync.feature.download.readmodel.DownloadProgress
import app.snapsync.feature.membership.readmodel.RenameFailureReason
import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.model.Direction
import app.snapsync.model.DirectionCount
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Layer
import app.snapsync.model.MemberCounts
import app.snapsync.model.RenameState
import app.snapsync.model.ScreenMessage
import app.snapsync.model.SyncHealth
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
            inviteKey = MutableStateFlow(inviteKey),
        ),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = testCommands(),
        queries = noQueries,
        diagnostics = testDiagnostics(),
    )

    private fun StatusContainerHost.layer(): Layer.Joined = assertIs<Layer.Joined>(container.stateFlow.value.layer)

    // ---- who the event is waiting for ------------------------------------------------------------

    @Test
    fun `who the event waits for is told only to an in-sync member of an ended open event that still waits`() = runTest {
        val waiting = MemberCounts(active = 3, settled = 1)
        assertEquals(waiting, joined(backgroundScope, ENDED.copy(members = waiting)).layer().waiting)

        val notWhen = mapOf(
            "the event is still under way" to joined(backgroundScope, MEMBERSHIP.copy(members = waiting)),
            "the event has closed" to joined(backgroundScope, ENDED.copy(members = waiting, closed = true)),
            "this member is still sharing" to joined(
                backgroundScope,
                ENDED.copy(members = waiting),
                snapshot = SyncStatus.Ready(SyncProgress(1, 3, 4, 0, active = true, estimatedRemaining = null)),
            ),
            "nobody is left to wait for" to joined(backgroundScope, ENDED.copy(members = MemberCounts(3, 3))),
            "the counts are unknown" to joined(backgroundScope, ENDED),
        )
        for ((case, host) in notWhen) assertNull(host.layer().waiting, case)
    }

    // ---- the invite --------------------------------------------------------------------------------

    @Test
    fun `an encrypted event's invite carries its key`() = runTest {
        val host = joined(backgroundScope, MEMBERSHIP.copy(keyId = "k1"), inviteKey = INVITE_KEY)

        val layer = host.container.stateFlow.first { (it.layer as? Layer.Joined)?.inviteUrl?.contains("#k=") == true }
            .layer as Layer.Joined

        assertEquals(INVITE_KEY, decodedKey(layer.inviteUrl))
    }

    @Test
    fun `a plain event's invite carries no key — even one read for it`() = runTest {
        // The key cell still holds a key once the membership names no encrypted event — the invite must drop it.
        val config = MutableStateFlow<EventConfig?>(MEMBERSHIP.copy(keyId = "k1"))
        val host = joined(backgroundScope, inviteKey = INVITE_KEY, configFlow = config)
        host.container.stateFlow.first { (it.layer as? Layer.Joined)?.inviteUrl?.contains("#k=") == true }

        config.value = MEMBERSHIP.copy(name = "Plain Weekend")
        val layer = host.container.stateFlow.first {
            (it.layer as? Layer.Joined)?.membership?.name == "Plain Weekend"
        }.layer as Layer.Joined

        assertNull(decodedKey(layer.inviteUrl))
        assertFalse("#k=" in layer.inviteUrl)
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
