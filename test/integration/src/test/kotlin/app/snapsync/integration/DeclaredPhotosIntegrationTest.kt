package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **What there is to receive is what the others declared** (change `count-declared-photos`): a fellow member's photos
 * still uploading on its phone count toward this member's received total, the line says it waits for them, and they
 * stop counting when that member leaves before they arrive — read off the joined screen.
 */
class DeclaredPhotosIntegrationTest {

    @Test
    @Verifies(spec = "sync-status", requirement = "Progress counts only what this membership shares")
    fun photos_still_uploading_elsewhere_are_counted_and_waited_for_until_they_land() = rigTest {
        val event = createAndJoin()
        val all = (1..10).map { "P$it" }
        foreignDevice(DEV_F, *all.toTypedArray(), event = event, uploading = listOf("P8", "P9", "P10"))
        reconcile()
        stage()

        val waiting = awaitState { it.joined?.counts?.received == DirectionCount.Progress(done = 7, total = 10) }
        assertEquals(
            SyncHealth.Syncing(upload = Arrow.HIDDEN, download = Arrow.STATIC, waitingForOthers = true),
            waiting.joined!!.health,
            "7 of 10 received, and nothing left this device can do",
        )

        foreignDevice(DEV_F, *all.toTypedArray(), event = event) // the rest finishes uploading
        reconcile()
        stage()

        awaitInSync()
        assertEquals(DirectionCount.Progress(done = 10, total = 10), awaitState { true }.joined!!.counts?.received)
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "Progress counts only what this membership shares")
    fun a_member_leaving_before_its_photos_arrive_takes_them_out_of_the_total() = rigTest {
        val event = createAndJoin()
        foreignDevice(DEV_F, "A", "B", "C", "D", event = event, uploading = listOf("C", "D"))
        reconcile()
        stage()
        awaitState { it.joined?.counts?.received == DirectionCount.Progress(done = 2, total = 4) }

        publicBackend.foreignDeviceLeave(DEV_F)
        reconcile()

        awaitInSync()
        assertEquals(
            DirectionCount.Progress(done = 2, total = 2),
            awaitState { true }.joined!!.counts?.received,
            "what arrived stays counted; what never will is gone",
        )
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "Direction arrows show remaining work and live transfer")
    fun photos_leaving_while_everything_has_arrived_are_on_their_way_not_waited_for() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(DEV_F, "A", event = event)
        reconcile()
        stage()
        addPhoto("MINE")
        refresh()
        cycle()

        assertEquals(
            SyncHealth.Syncing(upload = Arrow.PULSING, download = Arrow.HIDDEN),
            awaitHealth { (it as? SyncHealth.Syncing)?.upload == Arrow.PULSING },
            "only this device's upload runs: the line is the one for a running transfer, never waiting for photos",
        )
    }
}
