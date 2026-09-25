package app.snapsync.feature.membership

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Entering a new membership (capabilities `join-event`, `photo-sharing`): the order is the rule. */
class MembershipEntryTest {

    private val cfg = EventConfig(
        eventId = "NEW",
        name = "New",
        minPhotoDate = CaptureCutoff(CaptureDate("2026-07-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(CaptureDate("2026-07-08T00:00:00Z")),
        direction = Direction.Both,
        saveToAlbum = false,
    )

    private fun entry(order: MutableList<String>) = MembershipEntry(
        stopUploads = { order += "stop" },
        notifyLeave = { order += "leave:$it" },
        loadShareSet = { order += "load" },
        saveConfig = { order += "save:${it.eventId}" },
        startUploads = { order += "start" },
    )

    @Test
    fun `a switch stops the previous uploads then leaves then loads then saves then starts the new uploads`() = runTest {
        val order = mutableListOf<String>()
        entry(order).enter("OLD", cfg)
        assertEquals(listOf("stop", "leave:OLD", "load", "save:NEW", "start"), order)
    }

    @Test
    fun `a first join has nothing to stop or leave`() = runTest {
        val order = mutableListOf<String>()
        entry(order).enter(null, cfg)
        assertEquals(listOf("load", "save:NEW", "start"), order)
    }
}
