package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {

    @Test
    fun replacesAPlainUuid() {
        assertEquals(
            "reconcile(eventId=‹uuid›)",
            redactUuids("reconcile(eventId=550e8400-e29b-41d4-a716-446655440000)"),
        )
    }

    @Test
    fun replacesUppercaseAndMixedCaseUuids() {
        assertEquals("‹uuid›", redactUuids("550E8400-E29B-41d4-A716-446655440000"))
    }

    @Test
    fun replacesUuidsEmbeddedInUrlsAndPaths() {
        assertEquals(
            "GET https://host/events/‹uuid›/devices/‹uuid›/manifest → 404",
            redactUuids(
                "GET https://host/events/550e8400-e29b-41d4-a716-446655440000" +
                    "/devices/123e4567-e89b-12d3-a456-426614174000/manifest → 404",
            ),
        )
    }

    @Test
    fun replacesEveryOccurrenceOnOneLine() {
        val line = "join 550e8400-e29b-41d4-a716-446655440000 as 123e4567-e89b-12d3-a456-426614174000"
        assertEquals("join ‹uuid› as ‹uuid›", redactUuids(line))
    }

    @Test
    fun leavesNearMissesIntact() {
        // Too-short group, non-hex characters, missing hyphen: none may be touched, because a
        // false positive would eat real diagnostic text (filenames, sizes, hashes).
        val nearMisses = listOf(
            "550e8400-e29b-41d4-a716-44665544000", // last group 11 chars
            "550e8400-e29b-41d4-a716-4466554400zz", // non-hex
            "550e8400e29b-41d4-a716-446655440000", // missing first hyphen
            "IMG_4021.HEIC 3024x4032",
        )
        for (text in nearMisses) assertEquals(text, redactUuids(text))
    }

    @Test
    fun leavesTextThePreCheckSkipsIntact() {
        // The cheap pre-check skips the regex for text that cannot hold a UUID (under 36 characters, or
        // fewer than four hyphens). These sit right at its edges — it must only ever skip, never change
        // the answer.
        val skipped = listOf(
            "",
            "550e8400-e29b-41d4-a716-44665544000", // 35 characters, four hyphens
            "550e8400e29b41d4a716446655440000xyzw", // 36 characters, no hyphens
            "550e8400-e29b-41d4-a716446655440000", // 35 characters, three hyphens
            "550e8400-e29b-41d4-a716x446655440000", // 36 characters, three hyphens
            "a-b-c-d", // four hyphens, far too short
        )
        for (text in skipped) assertEquals(text, redactUuids(text))
    }

    @Test
    fun redactsAUuidThatExactlyFillsTheText(): Unit =
        assertEquals("‹uuid›", redactUuids("550e8400-e29b-41d4-a716-446655440000"))

    @Test
    fun hyphensElsewhereDoNotMakeANearMissMatch() {
        // Enough length and hyphens to pass the pre-check, still no UUID: the regex decides, as before.
        val text = "2026-09-23 upload-cycle: re-try of IMG-4021.HEIC (size 3024-4032)"
        assertEquals(text, redactUuids(text))
    }

    @Test
    fun aUuidWhoseHyphensComeLateInALongLineIsStillFound() {
        val line = "x".repeat(200) + " 550e8400-e29b-41d4-a716-446655440000"
        assertEquals("x".repeat(200) + " ‹uuid›", redactUuids(line))
    }

    @Test
    fun redactsAUuidTouchingWordCharacters() {
        // Log lines interpolate without spaces ("id=<uuid>," or "…/<uuid>)"). The rule is
        // content-blind: even a UUID glued to other characters is scrubbed.
        assertEquals(
            "id=‹uuid›,next",
            redactUuids("id=550e8400-e29b-41d4-a716-446655440000,next"),
        )
    }

    @Test
    fun leavesUuidFreeTextUntouched(): Unit = assertEquals(
        "gallery: enumerated 12 resource(s) (3 origin-excluded) → N=9",
        redactUuids("gallery: enumerated 12 resource(s) (3 origin-excluded) → N=9"),
    )

    // The exemption marker (capability `privacy-security`): an event declares itself exempt, and
    // the scrubbing step consults this predicate. Pinned here because the failure is silent at every
    // other layer — a dump whose marker went missing arrives redacted, with no failing request.

    @Test
    fun anEventCarryingTheMarkerIsExempt(): Unit =
        assertFalse(redactsMessages(mapOf(NON_REDACTED_TAG to "1")))

    @Test
    fun anEventWithNoTagsIsRedacted(): Unit = assertTrue(redactsMessages(emptyMap()))

    @Test
    fun unrelatedTagsDoNotExempt(): Unit =
        assertTrue(redactsMessages(mapOf("process" to "app.snapsync", "release" to "0.2")))

    @Test
    fun theMarkerMustCarryItsExactValue() {
        // A tag that is merely PRESENT does not exempt: the SDK and the operator both set tags, and
        // an exemption that triggered on presence alone would widen every time a new tag appeared.
        assertTrue(redactsMessages(mapOf(NON_REDACTED_TAG to "0")))
        assertTrue(redactsMessages(mapOf(NON_REDACTED_TAG to "")))
        assertTrue(redactsMessages(mapOf(NON_REDACTED_TAG to "true")))
    }
}
