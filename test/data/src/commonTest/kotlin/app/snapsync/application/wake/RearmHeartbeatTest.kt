package app.snapsync.application.wake

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.Device
import app.snapsync.application.membership.EnterMembership
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.event
import app.snapsync.data.membership.share
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.wake.WakeArming
import app.snapsync.data.wake.WakeRequest
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsEnrolled
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.wake.HeartbeatCadence
import app.snapsync.domain.wake.StagedImports
import app.snapsync.domain.wake.UploadsLeft
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.osUploaderOver
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.ScheduleResult
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import app.snapsync.ports.WakeId
import app.snapsync.ports.WakeNetwork
import app.snapsync.ports.WakeTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * The re-arm a wake owes the next one (decision record `changes/timely-background-receiving`, D1): the library-change
 * wake first for a membership that contributes, then the heartbeat at the cadence the device's facts answer.
 */
@OptIn(EntryPointCall::class, MintsEnrolled::class)
class RearmHeartbeatTest {

    private class RecordingWake(var library: ScheduleResult = ScheduleResult.Unsupported) : Wake {
        val requests = mutableListOf<Pair<WakeId, WakeTrigger>>()
        var heartbeat: ScheduleResult = ScheduleResult.Scheduled

        @CompositionOnly
        override fun inbound(handlers: WakeHandlers) = Unit

        override suspend fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult {
            requests += id to trigger
            return if (id == WakeId.Heartbeat) heartbeat else library
        }

        override fun cancel(id: WakeId) = Unit
    }

    private val device = Device().apply { clock.now = Instant.parse("2026-07-16T12:00:00Z") }
    private val wake = RecordingWake()
    private val grant = MutableStateFlow(LibraryGrant.GRANTED)
    private val preferences = mutableMapOf<String, String>()
    private val rearm = RearmHeartbeat(
        WakeArming(wake),
        device.membership,
        device.wallClock,
        GrantRead(grant),
        osUploaderOver(supported = false, grant),
        MobileDataStore(inMemoryPreferences(preferences)),
    )

    private suspend fun joined(direction: Direction = Direction.Both) {
        val held = event().let { Membership.Active(it, share(it, direction)) }
        EnterMembership(device.membership)(enrolled(held))
    }

    private fun heartbeats() = wake.requests.filter { it.first == WakeId.Heartbeat }.map { it.second }

    @Test
    fun `a device that is not joined arms no heartbeat`() = runTest {
        device.membership.reload()
        assertEquals(RearmOutcome.NotJoined, rearm(UploadsLeft.WORK_LEFT, StagedImports.REMAIN))
        assertEquals(emptyList(), heartbeats())
    }

    @Test
    fun `a sharer that can only look for its own photos stays busy, and asks for the library wake first`() = runTest {
        joined()
        assertEquals(RearmOutcome.Armed(HeartbeatCadence.BUSY), rearm(UploadsLeft.CAUGHT_UP, StagedImports.NONE))
        assertEquals(WakeId.LibraryChanged, wake.requests.first().first)
        assertEquals(WakeNetwork.ANY, (heartbeats().single() as WakeTrigger.After).network)
    }

    @Test
    fun `a standing library wake lets a caught-up sharer idle`() = runTest {
        joined()
        wake.library = ScheduleResult.Scheduled
        assertEquals(RearmOutcome.Armed(HeartbeatCadence.IDLE), rearm(UploadsLeft.CAUGHT_UP, StagedImports.NONE))
    }

    @Test
    fun `a membership that contributes nothing watches nothing and idles`() = runTest {
        joined(Direction.DownloadOnly)
        assertEquals(RearmOutcome.Armed(HeartbeatCadence.IDLE), rearm(UploadsLeft.NOT_CONTRIBUTING, StagedImports.NONE))
        assertEquals(listOf(WakeId.Heartbeat), wake.requests.map { it.first })
    }

    @Test
    fun `staged imports keep it busy, and with mobile data off it waits for an unrestricted network`() = runTest {
        joined(Direction.DownloadOnly)
        MobileDataStore(inMemoryPreferences(preferences)).write(MobileDataChoice.WIFI_ONLY)
        assertEquals(
            RearmOutcome.Armed(HeartbeatCadence.BUSY),
            rearm(UploadsLeft.NOT_CONTRIBUTING, StagedImports.REMAIN),
        )
        assertEquals(WakeNetwork.UNRESTRICTED, (heartbeats().single() as WakeTrigger.After).network)
    }

    @Test
    fun `before the event's start a sharer looks for its own photos as during it`() = runTest {
        joined()
        device.clock.now = Instant.parse("2026-07-10T12:00:00Z")
        assertEquals(RearmOutcome.Armed(HeartbeatCadence.BUSY), rearm(UploadsLeft.CAUGHT_UP, StagedImports.NONE))
    }

    @Test
    fun `after the event's end a caught-up sharer idles`() = runTest {
        joined()
        device.clock.now = Instant.parse("2026-07-22T12:00:00Z")
        assertEquals(RearmOutcome.Armed(HeartbeatCadence.IDLE), rearm(UploadsLeft.CAUGHT_UP, StagedImports.NONE))
    }

    @Test
    fun `a heartbeat the operating system refuses is not armed`() = runTest {
        joined()
        wake.heartbeat = ScheduleResult.Refused("refused")
        val outcome = assertIs<RearmOutcome.NotArmed>(rearm(UploadsLeft.WORK_LEFT, StagedImports.NONE))
        assertEquals(WakeRequest.Refused("refused"), outcome.answer)
    }

    @Test
    fun `an unreadable membership arms nothing`() = runTest {
        joined()
        device.databases.lock()
        val locked = RearmHeartbeat(
            WakeArming(wake),
            MembershipRepository(device.shared),
            device.wallClock,
            GrantRead(grant),
            osUploaderOver(supported = false, grant),
            MobileDataStore(inMemoryPreferences(preferences)),
        )
        assertEquals(RearmOutcome.NotJoined, locked(UploadsLeft.CAUGHT_UP, StagedImports.NONE))
    }

    @Test
    fun `a platform with no heartbeat is not armed either`() = runTest {
        joined()
        wake.heartbeat = ScheduleResult.Unsupported
        assertEquals(RearmOutcome.NotArmed(WakeRequest.Unsupported), rearm(UploadsLeft.WORK_LEFT, StagedImports.NONE))
    }
}
