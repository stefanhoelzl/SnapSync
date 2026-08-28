package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `LedgerEntry`'s equality is HAND-WRITTEN, deliberately (it is not a data class), which means a field
 * added to the row and forgotten here compares equal to one that differs — the row the ledger is
 * supposed to notice changing. That is a silent failure with no compiler behind it, so the enumeration
 * below is the guard: one case per field, each differing in exactly that field.
 *
 * `destinationPath` is the field this change added, and the reason the enumeration is worth writing out
 * rather than trusting: it is the one the OS-driven tier matches a returned upload job against
 * (capability `sync-ledger`).
 */
class LedgerEntryEqualityTest {

    private val base = LedgerEntry(
        key = "A-primary.jpg",
        assetId = "A",
        state = LedgerState.COMPLETED,
        attempt = 1,
        eventId = "E",
        creationDate = "2026-08-28T10:00:00Z",
        role = ResourceRole.PRIMARY,
        contentType = "image/jpeg",
        originalFilename = "IMG_0042.JPG",
        absent = false,
        destinationPath = "/api/v2/files/devices/D/A/primary",
    )

    @Test
    fun an_identical_row_is_equal() {
        assertEquals(base, base.copyWith())
        assertEquals(base.hashCode(), base.copyWith().hashCode())
    }

    @Test
    fun a_row_differing_in_any_single_field_is_not_equal() {
        val variants = mapOf(
            "key" to base.copyWith(key = "B-primary.jpg"),
            "assetId" to base.copyWith(assetId = "B"),
            "state" to base.copyWith(state = LedgerState.REQUESTED),
            "attempt" to base.copyWith(attempt = 2),
            "eventId" to base.copyWith(eventId = "E2"),
            "creationDate" to base.copyWith(creationDate = "2026-08-29T10:00:00Z"),
            "role" to base.copyWith(role = ResourceRole.LIVE),
            "contentType" to base.copyWith(contentType = "image/heic"),
            "originalFilename" to base.copyWith(originalFilename = "IMG_0043.JPG"),
            "absent" to base.copyWith(absent = true),
            "destinationPath" to base.copyWith(destinationPath = "/api/v2/files/devices/D/B/primary"),
        )
        for ((field, variant) in variants) {
            assertNotEquals(base, variant, "rows differing in `$field` compared equal")
        }
    }

    @Test
    fun a_null_destination_differs_from_a_recorded_one() {
        // The two states the column really has: a row written by a build that records the destination,
        // and one written before it did (or by a rolled-back build). They are not the same row.
        assertNotEquals(base, base.copyWith(destinationPath = null))
    }

    @Test
    fun it_is_not_equal_to_another_type() {
        assertTrue(!base.equals("A-primary.jpg"), "equality must not reduce to the key alone")
    }

    @Test
    fun marking_absent_changes_only_absent() {
        val marked = base.markedAbsent()
        assertTrue(marked.absent)
        assertEquals(base, marked.copyWith(absent = false), "nothing else about the row may move")
    }

    private fun LedgerEntry.copyWith(
        key: String = this.key,
        assetId: String = this.assetId,
        state: LedgerState = this.state,
        attempt: Int = this.attempt,
        eventId: String = this.eventId,
        creationDate: String = this.creationDate,
        role: ResourceRole? = this.role,
        contentType: String = this.contentType,
        originalFilename: String = this.originalFilename,
        absent: Boolean = this.absent,
        destinationPath: String? = this.destinationPath,
    ) = LedgerEntry(
        key, assetId, state, attempt, eventId, creationDate, role, contentType, originalFilename,
        absent, destinationPath,
    )
}
