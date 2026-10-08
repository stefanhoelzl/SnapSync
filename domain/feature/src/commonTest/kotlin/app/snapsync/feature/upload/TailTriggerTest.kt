package app.snapsync.feature.upload

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What each tail trigger asks of the end-of-wake step (capability `sync-status`): whether it checks the member's photos,
 * and whether its read of the event's state is bounded to once an hour. Asserted over every trigger, so a new one
 * states its answers here.
 */
class TailTriggerTest {

    @Test
    fun `every trigger but the arm checks the photos`() {
        assertEquals(
            setOf(TailTrigger.ARM),
            TailTrigger.entries.filterNot { it.checksPhotos }.toSet(),
        )
    }

    @Test
    fun `only a trigger whose reason is to ask the event now reads it unbounded`() {
        assertEquals(
            setOf(TailTrigger.SILENT_PUSH, TailTrigger.FOREGROUND, TailTrigger.NETWORK, TailTrigger.ARM),
            TailTrigger.entries.filterNot { it.boundsEventRead }.toSet(),
        )
        assertEquals(
            TailTrigger.entries.size - 4,
            TailTrigger.entries.count { it.boundsEventRead },
            "every other trigger's read is bounded",
        )
    }
}
