package app.snapsync.feature.membership

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.TimestampText
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Entering a new membership (capabilities `join-event`, `photo-sharing`): the order is the rule. */
class MembershipEntryTest {

    private val cfg = EventConfig(
        eventId = testEventId("NEW"),
        name = "New",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-08T00:00:00Z")),
        direction = Direction.Both,
        saveToAlbum = false,
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
    )

    private fun entry(order: MutableList<String>) = MembershipEntry(
        stopUploads = { order += "stop" },
        notifyLeave = { order += "leave:${nameOf(it)}" },
        loadShareSet = { order += "load" },
        adoptReceived = { order += "adopt:${nameOf(it.eventId)}" },
        saveConfig = { order += "save:${nameOf(it.eventId)}" },
        startUploads = { order += "start" },
    )

    @Test
    fun `a switch stops the previous uploads then leaves then loads then adopts then saves then starts the new uploads`() = runTest {
        val order = mutableListOf<String>()
        entry(order).enter(testEventId("OLD"), cfg)
        assertEquals(listOf("stop", "leave:OLD", "load", "adopt:NEW", "save:NEW", "start"), order)
    }

    @Test
    fun `a first join has nothing to stop or leave`() = runTest {
        val order = mutableListOf<String>()
        entry(order).enter(null, cfg)
        assertEquals(listOf("load", "adopt:NEW", "save:NEW", "start"), order)
    }

    @Test
    fun `provisioning another event switches - none enters - the joined one only saves`() = runTest {
        val switched = mutableListOf<String>()
        entry(switched).provision(testEventId("OLD"), cfg)
        assertEquals(listOf("stop", "leave:OLD", "load", "adopt:NEW", "save:NEW", "start"), switched)
        val joined = mutableListOf<String>()
        entry(joined).provision(null, cfg)
        assertEquals(listOf("load", "adopt:NEW", "save:NEW", "start"), joined)
        val stayed = mutableListOf<String>()
        entry(stayed).provision(testEventId("NEW"), cfg)
        assertEquals(listOf("save:NEW"), stayed, "re-provisioning the joined event leaves and loads nothing")
    }

    @Test
    fun `a share-only join adopts too`() = runTest {
        val order = mutableListOf<String>()
        entry(order).enter(null, cfg.copy(direction = Direction.UploadOnly))
        assertEquals(listOf("load", "adopt:NEW", "save:NEW", "start"), order)
    }

    /** The fixture name an event id stands for, so the recorded order reads as the events' names. */
    private fun nameOf(id: EventId): String = listOf("NEW", "OLD").single { testEventId(it) == id }
}
