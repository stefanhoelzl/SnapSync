package app.snapsync.domain.wake

import app.snapsync.domain.mobiledata.PhotoNetwork
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.upload.OsUploaderStanding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The heartbeat's cadence after a wake (decision record `changes/timely-background-receiving`, D1, D3). */
class HeartbeatTest {

    /** A sharing member under a full grant, caught up, nothing watching the library, the event running. */
    private val looking = CadenceFacts(
        membership = CadenceMembership.SHARES,
        event = EventRunning.RUNNING,
        access = PhotoAccess.FULL,
        osUploader = OsUploaderStanding.NOT_CONFIRMED,
        uploads = UploadsLeft.CAUGHT_UP,
        imports = StagedImports.NONE,
        libraryWatch = LibraryWatch.NONE,
    )

    private val busy = HeartbeatVerdict.Arm(HeartbeatCadence.BUSY)
    private val idle = HeartbeatVerdict.Arm(HeartbeatCadence.IDLE)

    @Test
    fun `a device that is not joined keeps no heartbeat whatever is left`() {
        val left = looking.copy(uploads = UploadsLeft.WORK_LEFT, imports = StagedImports.REMAIN)
        assertEquals(HeartbeatVerdict.None, left.copy(membership = CadenceMembership.NOT_JOINED).cadence())
    }

    @Test
    fun `work left keeps the heartbeat busy for every kind of member`() {
        for (membership in listOf(CadenceMembership.SHARES, CadenceMembership.RECEIVES)) {
            val member = looking.copy(membership = membership, access = PhotoAccess.LIMITED, event = EventRunning.ENDED)
            assertEquals(busy, member.copy(uploads = UploadsLeft.WORK_LEFT).cadence(), "$membership, uploads")
            assertEquals(busy, member.copy(imports = StagedImports.REMAIN).cadence(), "$membership, imports")
        }
    }

    @Test
    fun `a full-grant sharer with nothing watching looks for its own photos until the end`() {
        assertEquals(busy, looking.cadence())
        assertEquals(idle, looking.copy(event = EventRunning.ENDED).cadence())
    }

    @Test
    fun `a library-change wake or a confirmed OS uploader lets a caught-up sharer idle`() {
        assertEquals(idle, looking.copy(libraryWatch = LibraryWatch.STANDING).cadence())
        assertEquals(idle, looking.copy(osUploader = OsUploaderStanding.CONFIRMED).cadence())
    }

    @Test
    fun `receive-only, held back and partial-grant members idle`() {
        assertEquals(idle, looking.copy(membership = CadenceMembership.RECEIVES).cadence())
        assertEquals(idle, looking.copy(uploads = UploadsLeft.NOT_CONTRIBUTING).cadence())
        assertEquals(idle, looking.copy(access = PhotoAccess.LIMITED).cadence())
        assertEquals(idle, looking.copy(access = PhotoAccess.NONE).cadence())
    }

    @Test
    fun `busy is a minute, idle an hour, and only a busy one waits out mobile data`() {
        assertEquals(60.seconds, HeartbeatCadence.BUSY_EARLIEST)
        assertEquals(1.hours, HeartbeatCadence.IDLE_EARLIEST)
        assertEquals(
            HeartbeatTrigger(60.seconds, HeartbeatNetwork.UNRESTRICTED, HeartbeatCadence.BUSY),
            HeartbeatCadence.BUSY.trigger(PhotoNetwork.UNRESTRICTED_ONLY),
        )
        assertEquals(HeartbeatNetwork.ANY, HeartbeatCadence.BUSY.trigger(PhotoNetwork.ANY).network)
        for (photos in PhotoNetwork.entries) {
            assertEquals(
                HeartbeatTrigger(1.hours, HeartbeatNetwork.ANY, HeartbeatCadence.IDLE),
                HeartbeatCadence.IDLE.trigger(photos),
            )
        }
    }

    @Test
    fun photos_staged_to_import_remain_while_any_waits() {
        assertEquals(StagedImports.NONE, StagedImports.of(0))
        assertEquals(StagedImports.REMAIN, StagedImports.of(3))
    }
}
