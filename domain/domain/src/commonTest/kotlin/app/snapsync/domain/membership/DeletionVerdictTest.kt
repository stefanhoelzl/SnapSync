package app.snapsync.domain.membership

import app.snapsync.domain.event.EventLookupFact
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.event.ts
import app.snapsync.domain.service.ServiceFault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A deletion needs a witness no backend fault can manufacture (capability `manage-membership`). */
class DeletionVerdictTest {

    private val event = anEvent()
    private val beforeDeadline = ts("2026-08-21T18:00:00Z")
    private val afterDeadline = ts("2026-08-21T18:00:01Z")

    private fun found(completed: Boolean) = EventLookupFact.Found(event, completed)

    @Test
    fun `the server's word that the event completed confirms it, whenever it comes`() {
        val verdict = assertIs<DeletionVerdict.Confirmed>(DeletionVerdict.of(event, found(true), beforeDeadline))
        assertEquals(event.id, verdict.evidence.event)
    }

    @Test
    fun `an event still on the server is not deleted`() {
        assertEquals(DeletionVerdict.NotDeleted(event), DeletionVerdict.of(event, found(false), afterDeadline))
    }

    @Test
    fun `not found confirms it only past the membership's own deadline`() {
        assertEquals(DeletionVerdict.Inconclusive, DeletionVerdict.of(event, EventLookupFact.NotFound, beforeDeadline))
        assertIs<DeletionVerdict.Confirmed>(DeletionVerdict.of(event, EventLookupFact.NotFound, afterDeadline))
    }

    @Test
    fun `a write the server refused because the photos are deleted confirms it, whenever it comes`() {
        assertEquals(event.id, DeletionVerdict.answeredOver(event).evidence.event)
    }

    @Test
    fun `no answer tells nothing`() {
        assertEquals(
            DeletionVerdict.Inconclusive,
            DeletionVerdict.of(event, EventLookupFact.Unanswered(ServiceFault.UNREACHABLE), afterDeadline),
        )
    }
}
